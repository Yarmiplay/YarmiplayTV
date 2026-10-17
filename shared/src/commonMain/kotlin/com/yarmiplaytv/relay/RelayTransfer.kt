package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where a relayed file can be read right now: the session and the file's id in the room. */
data class RelaySource(val session: YarmiplaySession, val fileId: String)

/**
 * Fetches one relayed file into its [cache] copy. In stream mode it keeps [READ_AHEAD] after the player's read
 * position filled (asking the server for `mode=stream`); with [download] on it also fetches every missing block
 * in the background (`mode=download`). Before the file plays, [prefetch] fills the parts a player reads first.
 */
class RelayTransfer(
    val key: String,
    val name: String,
    val size: Long,
    val cache: CachedFile,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val source: () -> RelaySource?,
) {
    /** Where the player reads; the stream loop fills ahead of it. */
    private val readerPos = MutableStateFlow(0L)
    /** One entry per player connection. mpv asks for several ranges at once, and a single position would cancel the others. */
    private val readers = ConcurrentHashMap<Int, Long>()
    private val readerIds = AtomicInteger()
    private var streamJob: Job? = null
    private var prefetchJob: Job? = null
    private val samples = ArrayDeque<Sample>()

    @Volatile var downloading = false
        private set

    @Volatile var lastError: String? = null
        private set

    private class Sample(val atNanos: Long, val bytes: Int, val busyNanos: Long)

    fun start() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch(Dispatchers.IO) { streamLoop() }
    }

    fun setDownload(on: Boolean) {
        downloading = on && !cache.complete
        if (downloading) start()
    }

    fun stop() {
        streamJob?.cancel()
        stopPrefetch()
        streamJob = null
        downloading = false
    }

    /**
     * Fetches the missing blocks of each byte range in [ranges], in order, with the server's [mode] (`prefetch`, or
     * `download` on servers without it). Gives up after a few failures: it only saves time, nothing waits for it.
     */
    fun prefetch(ranges: List<LongRange>, mode: String) {
        if (prefetchJob?.isActive == true || cache.complete) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            var failures = 0
            for (range in ranges) {
                if (range.isEmpty()) continue
                val last = cache.blockOf(range.last.coerceAtMost(size - 1))
                while (currentCoroutineContext().isActive && !cache.isDeleted) {
                    val first = cache.firstMissing(cache.blockOf(range.first))?.takeIf { it <= last } ?: break
                    if (fetch(first, runEnd(first, last + 1), mode, measure = false) { false }) continue
                    if (++failures >= MAX_PREFETCH_FAILURES) return@launch
                    delay(2_000L * failures)
                }
            }
            Logger.i(TAG, "Prefetched $name: ${cache.cachedBytes} of $size bytes")
        }
    }

    fun stopPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /** The player wants bytes from [offset]. */
    fun demand(offset: Long) {
        readerPos.value = offset.coerceIn(0, size)
    }

    /** A player connection that needs [offset]; [moveReader] follows it and [closeReader] drops it. */
    fun openReader(offset: Long): Int {
        val id = readerIds.incrementAndGet()
        readers[id] = offset.coerceIn(0, size)
        demand(offset)
        return id
    }

    fun moveReader(id: Int, offset: Long) {
        val at = offset.coerceIn(0, size)
        if (readers.replace(id, at) != null) demand(at)
    }

    fun closeReader(id: Int) {
        readers.remove(id)
    }

    /** Waits up to [timeoutMs] for bytes at [offset]; returns how many are there without a gap (0 on timeout). */
    suspend fun awaitAvailable(offset: Long, timeoutMs: Long): Long {
        cache.availableFrom(offset).takeIf { it > 0 }?.let { return it }
        demand(offset)
        return withTimeoutOrNull(timeoutMs) {
            cache.version.first { cache.isDeleted || cache.availableFrom(offset) > 0 }
            cache.availableFrom(offset)
        } ?: 0
    }

    /**
     * Bytes per second over the last [WINDOW_MS], counting only the time spent waiting on the network, or null when
     * the fetches were mostly idle.
     */
    @Synchronized
    fun rate(nowNanos: Long = System.nanoTime()): Double? {
        prune(nowNanos)
        val busy = samples.sumOf { it.busyNanos }
        if (busy < MIN_BUSY_NANOS) return null
        return (samples.sumOf { it.bytes.toLong() } / (busy / 1e9)).takeIf { it > 0 }
    }

    @Synchronized
    private fun record(bytes: Int, busyNanos: Long) {
        val now = System.nanoTime()
        samples.addLast(Sample(now, bytes, busyNanos))
        prune(now)
    }

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().atNanos > WINDOW_MS * 1_000_000) samples.removeFirst()
    }

    private suspend fun streamLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !cache.isDeleted) {
            val pos = focus()
            val from = cache.blockOf(pos)
            // Fill the read-ahead window from the play position first, then the rest of the file to the end and from 0.
            val first = cache.firstMissing(from) ?: cache.firstMissing(0) ?: break
            val limit = pos + READ_AHEAD
            val inWindow = first >= from && cache.blockStart(first) < limit
            val end = if (inWindow) {
                runEnd(first, cache.blockOf(minOf(limit, size - 1)) + 1)
            } else {
                runEnd(first, first + MAX_REQUEST_BLOCKS)
            }
            val mode = if (inWindow && !downloading) "stream" else "download"
            val ok = fetch(first, end, mode) {
                if (inWindow) !wanted(first, end) else urgentMissing()?.let { it != cache.firstMissing(first) } == true
            }
            failures = if (ok) 0 else failures + 1
            if (!ok) delay(minOf(5_000L, 500L * failures))
        }
        downloading = false
    }

    /** The first missing block within [READ_AHEAD] of [focus], if any. */
    private fun urgentMissing(): Int? {
        val pos = focus()
        val first = cache.firstMissing(cache.blockOf(pos)) ?: return null
        return first.takeIf { cache.blockStart(it) < pos + READ_AHEAD }
    }

    /** The earliest player connection that still needs bytes, else the last single demand. */
    private fun focus(): Long {
        val live = readers.values.toList()
        if (live.isEmpty()) return readerPos.value
        return live.filter { cache.availableFrom(it) == 0L }.minOrNull() ?: live.min()
    }

    /**
     * Keep this request while a player position still needs it. The position is often behind the request: the stream
     * fills [READ_AHEAD] ahead of where the player is, and dropping that looked like a seek and retried the same bytes.
     */
    private fun wanted(first: Int, end: Int): Boolean {
        val from = cache.blockStart(first)
        val to = cache.blockStart(end)
        val points = readers.values.toList().ifEmpty { listOf(readerPos.value) }
        return points.any { reader ->
            reader in from until to ||
                (from >= cache.blockStart(cache.blockOf(reader)) && from < reader + READ_AHEAD)
        }
    }

    /** The end (exclusive) of the run of missing blocks starting at [first], at most [limit] and [MAX_REQUEST_BLOCKS] long. */
    private fun runEnd(first: Int, limit: Int): Int {
        var end = first + 1
        val max = minOf(limit, first + MAX_REQUEST_BLOCKS, cache.blockOf(size - 1) + 1)
        while (end < max && !cache.has(end)) end++
        return end
    }

    /**
     * Fetches blocks `[first, end)` with the server's [mode]; returns false on an error. [abandon] stops early when the
     * player moved away. Only [measure]d fetches count toward [rate].
     */
    private suspend fun fetch(first: Int, end: Int, mode: String, measure: Boolean = true, abandon: () -> Boolean): Boolean {
        val src = source() ?: run {
            fail("No viewer is sharing this file right now")
            return false
        }
        val start = cache.blockStart(first)
        val last = minOf(size, cache.blockStart(end)) - 1
        val t0 = System.nanoTime()
        val request = Request.Builder()
            .url("${src.session.baseUrl}/yarmiplay/files/${src.fileId}?mode=$mode")
            .header("Authorization", src.session.authorization)
            .header("Range", "bytes=$start-$last")
            .build()
        val call = http.newCall(request)
        val cancelOnStop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.await().use { response ->
                if (response.code != 206 && response.code != 200) {
                    fail(
                        when (response.code) {
                            401 -> "The Syncplay server ended the session"
                            404 -> "No viewer is sharing this file right now"
                            else -> "The Syncplay server answered ${response.code}"
                        },
                        "$mode bytes=$start-$last of $name: HTTP ${response.code}",
                    )
                    return false
                }
                Logger.i(TAG, "Fetching $mode bytes=$start-$last of $name: HTTP ${response.code}")
                val body = response.body ?: return false
                val stream = body.byteStream()
                val buffer = ByteArray(BUFFER)
                var pos = if (response.code == 200) 0L else start
                var block = cache.blockOf(pos)
                while (pos <= last) {
                    val t0 = System.nanoTime()
                    val n = stream.read(buffer, 0, minOf(BUFFER.toLong(), last + 1 - pos).toInt())
                    if (n < 0) break
                    if (measure) record(n, System.nanoTime() - t0)
                    cache.write(pos, buffer, n)
                    pos += n
                    while (block < end && pos >= cache.blockStart(block) + cache.blockLength(block)) {
                        cache.markComplete(block)
                        block++
                    }
                    if (cache.isDeleted || abandon()) {
                        Logger.i(TAG, "Left $mode of $name at $pos (${elapsedMs(t0)} ms): the player moved")
                        return true
                    }
                    // Another fetch filled what comes next: stop here and let the loop pick the next gap.
                    if (block < end && pos == cache.blockStart(block) && cache.has(block)) return true
                }
                val ok = pos > last
                if (ok) {
                    lastError = null
                    Logger.i(TAG, "Got $mode bytes=$start-$last of $name in ${elapsedMs(t0)} ms")
                } else {
                    fail("The Syncplay server sent too little", "$mode of $name ended at $pos of bytes=$start-$last")
                }
                return ok
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                fail("Lost the connection to the Syncplay server", "$mode bytes=$start-$last of $name failed: $e")
            }
            return false
        } finally {
            cancelOnStop.dispose()
        }
    }

    /** Sets [lastError]; logs [detail] (or the error) each time, and the error only when it changes. */
    private fun fail(error: String, detail: String? = null) {
        if (detail != null || error != lastError) Logger.w(TAG, detail ?: "$name: $error")
        lastError = error
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    companion object {
        private const val TAG = "RelayTransfer"
        /** What the stream loop keeps filled after the play position: the window the server prioritises. */
        const val READ_AHEAD = 64L shl 20
        private const val MAX_REQUEST_BLOCKS = 32
        private const val BUFFER = 64 * 1024
        const val WINDOW_MS = 15_000L
        private const val MIN_BUSY_NANOS = 3_000_000_000L
        private const val MAX_PREFETCH_FAILURES = 3
    }
}
