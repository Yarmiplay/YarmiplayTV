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

/** Where a relayed file can be read right now: the session and the file's id in the room. */
data class RelaySource(val session: YarmiplaySession, val fileId: String)

/**
 * Fetches one relayed file into its [cache] copy. In stream mode it keeps [READ_AHEAD] after the player's read
 * position filled (asking the server for `mode=stream`); with [download] on it also fetches every missing block
 * in the background (`mode=download`).
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
    private var streamJob: Job? = null
    private var downloadJob: Job? = null
    private val samples = ArrayDeque<Sample>()

    @Volatile var downloading = false
        private set

    @Volatile var lastError: String? = null
        private set

    private class Sample(val atNanos: Long, val bytes: Int, val busyNanos: Long, val download: Boolean)

    fun start() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch(Dispatchers.IO) { streamLoop() }
    }

    fun setDownload(on: Boolean) {
        downloading = on
        if (on && downloadJob?.isActive != true && !cache.complete) {
            downloadJob = scope.launch(Dispatchers.IO) { downloadLoop() }
        } else if (!on) {
            downloadJob?.cancel()
            downloadJob = null
        }
    }

    fun stop() {
        streamJob?.cancel()
        downloadJob?.cancel()
        streamJob = null
        downloadJob = null
        downloading = false
    }

    /** The player wants bytes from [offset]. */
    fun demand(offset: Long) {
        readerPos.value = offset.coerceIn(0, size)
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
     * the fetches were mostly idle (the read-ahead was full, so the network keeps up).
     */
    @Synchronized
    fun rate(nowNanos: Long = System.nanoTime()): Double? {
        prune(nowNanos)
        if (samples.isEmpty()) return null
        var total = 0.0
        for (download in listOf(false, true)) {
            val s = samples.filter { it.download == download }
            val busy = s.sumOf { it.busyNanos }
            if (busy >= MIN_BUSY_NANOS) total += s.sumOf { it.bytes.toLong() } / (busy / 1e9)
        }
        return total.takeIf { it > 0 }
    }

    @Synchronized
    private fun record(bytes: Int, busyNanos: Long, download: Boolean) {
        val now = System.nanoTime()
        samples.addLast(Sample(now, bytes, busyNanos, download))
        prune(now)
    }

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().atNanos > WINDOW_MS * 1_000_000) samples.removeFirst()
    }

    private suspend fun streamLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !cache.isDeleted) {
            val pos = readerPos.value
            val first = cache.firstMissing(cache.blockOf(pos))
            val limit = pos + READ_AHEAD
            if (first == null || cache.blockStart(first) >= limit) {
                withTimeoutOrNull(1_000) { readerPos.first { it != pos } }
                continue
            }
            val end = runEnd(first, cache.blockOf(minOf(limit, size - 1)) + 1)
            val ok = fetch(first, end, download = false) { readerPos.value.let { it < cache.blockStart(first) || it > cache.blockStart(end) } }
            failures = if (ok) 0 else failures + 1
            if (!ok) delay(minOf(5_000L, 500L * failures))
        }
    }

    private suspend fun downloadLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !cache.isDeleted) {
            // The rest of the file from the play position on, then what's left before it.
            val from = cache.blockOf(readerPos.value)
            val first = cache.firstMissing(from) ?: cache.firstMissing(0) ?: break
            val end = runEnd(first, first + MAX_REQUEST_BLOCKS)
            val ok = fetch(first, end, download = true) { false }
            failures = if (ok) 0 else failures + 1
            if (!ok) delay(minOf(10_000L, 1_000L * failures))
        }
        downloading = downloading && !cache.complete
    }

    /** The end (exclusive) of the run of missing blocks starting at [first], at most [limit] and [MAX_REQUEST_BLOCKS] long. */
    private fun runEnd(first: Int, limit: Int): Int {
        var end = first + 1
        val max = minOf(limit, first + MAX_REQUEST_BLOCKS, cache.blockOf(size - 1) + 1)
        while (end < max && !cache.has(end)) end++
        return end
    }

    /** Fetches blocks `[first, end)`; returns false on an error. [abandon] stops early when the player moved away. */
    private suspend fun fetch(first: Int, end: Int, download: Boolean, abandon: () -> Boolean): Boolean {
        val src = source() ?: run {
            lastError = "No viewer is sharing this file right now"
            return false
        }
        val start = cache.blockStart(first)
        val last = minOf(size, cache.blockStart(end)) - 1
        val request = Request.Builder()
            .url("${src.session.baseUrl}/yarmiplay/files/${src.fileId}?mode=${if (download) "download" else "stream"}")
            .header("Authorization", src.session.authorization)
            .header("Range", "bytes=$start-$last")
            .build()
        val call = http.newCall(request)
        val cancelOnStop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.await().use { response ->
                if (response.code != 206 && response.code != 200) {
                    lastError = when (response.code) {
                        401 -> "The Syncplay server ended the session"
                        404 -> "No viewer is sharing this file right now"
                        else -> "The Syncplay server answered ${response.code}"
                    }
                    return false
                }
                val body = response.body ?: return false
                val stream = body.byteStream()
                val buffer = ByteArray(BUFFER)
                var pos = if (response.code == 200) 0L else start
                var block = cache.blockOf(pos)
                while (pos <= last) {
                    val t0 = System.nanoTime()
                    val n = stream.read(buffer, 0, minOf(BUFFER.toLong(), last + 1 - pos).toInt())
                    if (n < 0) break
                    record(n, System.nanoTime() - t0, download)
                    cache.write(pos, buffer, n)
                    pos += n
                    while (block < end && pos >= cache.blockStart(block) + cache.blockLength(block)) {
                        cache.markComplete(block)
                        block++
                    }
                    if (cache.isDeleted || abandon()) return true
                    // Another fetch filled what comes next: stop here and let the loop pick the next gap.
                    if (block < end && pos == cache.blockStart(block) && cache.has(block)) return true
                }
                lastError = null
                return pos > last
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                lastError = "Lost the connection to the Syncplay server"
                Logger.w(TAG, "Relay fetch of $name failed: ${e.message}")
            }
            return false
        } finally {
            cancelOnStop.dispose()
        }
    }

    companion object {
        private const val TAG = "RelayTransfer"
        /** What the stream loop keeps filled after the play position: the window the server prioritises. */
        const val READ_AHEAD = 64L shl 20
        private const val MAX_REQUEST_BLOCKS = 32
        private const val BUFFER = 64 * 1024
        const val WINDOW_MS = 15_000L
        private const val MIN_BUSY_NANOS = 3_000_000_000L
    }
}
