package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import com.yarmiplaytv.syncplay.GrowingMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

/** One stretch of a growing file's timeline: bytes `[offset, offset + length)` play for [duration] seconds. */
data class MediaSegment(val offset: Long, val length: Long, val duration: Double) {
    val end: Long get() = offset + length
}

/**
 * What is known of a growing file at one moment: its segments so far, whether it still grows, and its length.
 * [final] means the server's index said it stopped growing, so [segments] is the whole timeline.
 */
data class GrowingSnapshot(
    val segments: List<MediaSegment>,
    val growing: Boolean,
    val live: Boolean,
    val length: Long,
    val final: Boolean = false,
) {
    /** Seconds the listed segments cover. */
    val indexedSeconds: Double get() = segments.sumOf { it.duration }
}

/** One answer of the server's segment index: the segments from number [from] on. */
internal data class IndexPage(val from: Int, val segments: List<MediaSegment>, val growing: Boolean, val length: Long)

/**
 * A relay file the server is still writing: its state from the room's list ([update]) and its segment index, fetched
 * from the server a little at a time ([refresh]). The player gets the index as an HLS event playlist ([GrowingPlaylist])
 * over the one file, so it can start, seek and wait at the end of what exists the way it does for any growing stream.
 */
class GrowingIndex(
    initial: GrowingMedia,
    private val http: OkHttpClient,
    private val source: () -> RelaySource?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val segments = ArrayList<MediaSegment>()
    private val lock = Mutex()
    private var fetchedAt = 0L
    private val _state = MutableStateFlow(GrowingSnapshot(emptyList(), initial.growing, initial.live, initial.length))

    /** The latest snapshot; changes when the room's list or the index says more exists. */
    val state: StateFlow<GrowingSnapshot> = _state.asStateFlow()

    val contentType: String = initial.contentType

    /** This index's playlist for the player, kept rendered between its reloads. */
    internal val playlist = GrowingPlaylist.Renderer()

    /** The room's list said this about the file. Lengths only grow, and a finished file stays finished. */
    fun update(media: GrowingMedia) = synchronized(segments) {
        val old = _state.value
        _state.value = old.copy(growing = old.growing && media.growing, length = maxOf(old.length, media.length))
    }

    /**
     * Asks the server for the segments after the ones already known, at most every [minIntervalMs] (and never again
     * once the index is final); returns the snapshot. Errors leave what was known.
     */
    suspend fun refresh(minIntervalMs: Long = REFRESH_MS): GrowingSnapshot = lock.withLock {
        val snap = _state.value
        if (snap.final || (fetchedAt > 0 && now() - fetchedAt < minIntervalMs)) return snap
        val src = source() ?: return snap
        val from = synchronized(segments) { segments.size }
        val request = Request.Builder()
            .url("${src.session.baseUrl}/yarmiplay/files/${src.fileId}/segments?from=$from")
            .header("Authorization", src.session.authorization)
            .build()
        val call = http.newCall(request)
        val cancelOnStop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.await().use { response ->
                if (response.code != 200) {
                    Logger.w(TAG, "Segment index of ${src.fileId}: HTTP ${response.code}")
                    return snap
                }
                val page = parseIndex(response.body?.string().orEmpty()) ?: return snap
                fetchedAt = now()
                merge(page)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "Segment index of ${src.fileId} failed: $e")
        } finally {
            cancelOnStop.dispose()
        }
        _state.value
    }

    /**
     * Waits until at least one segment is listed (a player can't open an empty playlist), up to [timeoutMs];
     * returns the snapshot either way.
     */
    suspend fun awaitFirstSegment(timeoutMs: Long): GrowingSnapshot {
        val deadline = now() + timeoutMs
        var snap = refresh(0)
        while (snap.segments.isEmpty() && !snap.final && now() < deadline && currentCoroutineContext().isActive) {
            delay(POLL_MS)
            snap = refresh(0)
        }
        return snap
    }

    internal fun merge(page: IndexPage) = synchronized(segments) {
        // Only what continues the known list: an answer that overlaps it adds its new tail, one past it adds nothing.
        val known = segments.size
        if (page.from <= known) segments.addAll(page.segments.drop(known - page.from))
        val complete = page.from <= known
        val old = _state.value
        _state.value = GrowingSnapshot(
            // The snapshot already holds the known list when nothing was added: no need to copy it again.
            segments = if (segments.size == known && old.segments.size == known) old.segments else segments.toList(),
            growing = old.growing && page.growing,
            live = old.live,
            length = maxOf(old.length, page.length),
            final = old.final || (complete && !page.growing),
        )
    }

    companion object {
        private const val TAG = "GrowingIndex"
        /** How often a player's playlist reloads may ask the server for new segments. */
        const val REFRESH_MS = 1_000L
        private const val POLL_MS = 500L
        private val json = Json { ignoreUnknownKeys = true }

        /** The server's segment index answer; null when it isn't one (or a segment in it is malformed). */
        internal fun parseIndex(body: String): IndexPage? {
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val list = o["segments"] as? JsonArray ?: return null
            val segments = list.mapNotNull { e ->
                val a = (e as? JsonArray)?.takeIf { it.size >= 3 } ?: return@mapNotNull null
                val offset = (a[0] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val length = (a[1] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val duration = (a[2] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                // An infinite duration (or one that overflows to it, like 1e400) would make TARGETDURATION nonsense.
                MediaSegment(offset, length, duration).takeIf { offset >= 0 && length > 0 && duration >= 0 && duration.isFinite() }
            }
            // A dropped segment would leave a gap in the timeline: take the answer only when every one is whole.
            if (segments.size != list.size) return null
            return IndexPage(
                from = (o["from"] as? JsonPrimitive)?.longOrNull?.toInt()?.coerceAtLeast(0) ?: 0,
                segments = segments,
                growing = (o["growing"] as? JsonPrimitive)?.booleanOrNull ?: true,
                length = (o["length"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }
}

/**
 * The HLS playlist a player opens for a growing file: an event playlist (segments are only ever added) whose segments
 * are byte ranges of [mediaUri], the one file. It ends with `ENDLIST` once the index is final, and then plays to a
 * normal end.
 */
object GrowingPlaylist {
    fun render(snapshot: GrowingSnapshot, mediaUri: String): String = buildString {
        val target = ceil(snapshot.segments.maxOfOrNull { it.duration } ?: 1.0).toLong().coerceAtLeast(1)
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:4\n")
        append("#EXT-X-TARGETDURATION:$target\n")
        append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        for (s in snapshot.segments) {
            append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", s.duration)).append(",\n")
            append("#EXT-X-BYTERANGE:").append(s.length).append('@').append(s.offset).append('\n')
            append(mediaUri).append('\n')
        }
        if (snapshot.final) append("#EXT-X-ENDLIST\n")
    }

    /**
     * [render], kept between a player's reloads of one growing file's playlist: segments are only ever added, so each
     * is formatted once and a reload only adds the new ones. The text is the same as [render]'s.
     */
    class Renderer {
        private var mediaUri: String? = null
        private val lines = StringBuilder()
        private var count = 0
        private var first: MediaSegment? = null
        private var last: MediaSegment? = null
        private var maxDuration = 0.0
        private var rendered: String? = null
        private var renderedFinal = false

        @Synchronized
        fun render(snapshot: GrowingSnapshot, mediaUri: String): String {
            val segments = snapshot.segments
            // Anything but the same list with more at its end starts over.
            if (mediaUri != this.mediaUri || segments.size < count ||
                (count > 0 && (segments[0] != first || segments[count - 1] != last))
            ) {
                reset(mediaUri)
            }
            if (segments.size == count && rendered != null && renderedFinal == snapshot.final) return rendered!!
            for (i in count until segments.size) {
                val s = segments[i]
                maxDuration = if (i == 0) s.duration else maxOf(maxDuration, s.duration)
                appendSegment(lines, s, mediaUri)
            }
            count = segments.size
            first = segments.firstOrNull()
            last = segments.lastOrNull()
            val target = ceil(if (count == 0) 1.0 else maxDuration).toLong().coerceAtLeast(1)
            val text = buildString(lines.length + 160) {
                appendHeader(this, target)
                append(lines)
                if (snapshot.final) append("#EXT-X-ENDLIST\n")
            }
            rendered = text
            renderedFinal = snapshot.final
            return text
        }

        private fun reset(mediaUri: String) {
            this.mediaUri = mediaUri
            lines.setLength(0)
            count = 0
            first = null
            last = null
            maxDuration = 0.0
            rendered = null
        }
    }

    private fun appendHeader(out: StringBuilder, target: Long) {
        out.append("#EXTM3U\n")
        out.append("#EXT-X-VERSION:4\n")
        out.append("#EXT-X-TARGETDURATION:$target\n")
        out.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        out.append("#EXT-X-MEDIA-SEQUENCE:0\n")
    }

    private fun appendSegment(out: StringBuilder, s: MediaSegment, mediaUri: String) {
        out.append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", s.duration)).append(",\n")
        out.append("#EXT-X-BYTERANGE:").append(s.length).append('@').append(s.offset).append('\n')
        out.append(mediaUri).append('\n')
    }

    /** A file name extension players accept for segments of [contentType]. */
    fun extension(contentType: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
        "video/mp4", "audio/mp4" -> "mp4"
        "video/x-matroska" -> "mkv"
        "video/webm", "audio/webm" -> "webm"
        "audio/aac" -> "aac"
        "audio/mpeg" -> "mp3"
        else -> "ts"
    }
}
