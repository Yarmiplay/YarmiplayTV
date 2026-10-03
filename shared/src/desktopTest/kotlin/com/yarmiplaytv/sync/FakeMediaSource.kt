package com.yarmiplaytv.sync

import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.media.PlaybackReport
import com.yarmiplaytv.media.ResolveResult
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

internal const val JF = "jellyfin:living"
internal const val JF2 = "jellyfin:office"
internal const val PX = "plex:den"

/** A server with a fixed set of files; [loose] files only match through [resolveByFilename]. */
internal class FakeMediaSource(
    override val key: String,
    private val exact: Map<String, Long> = emptyMap(),
    private val loose: Map<String, String> = emptyMap(),
    private val lookupDelayMs: Long = 0,
    private val failReports: Boolean = false,
) : MediaSource {
    override val kind = key.substringBefore(':')
    override val displayName = key.substringAfter(':').replaceFirstChar { it.uppercase() }

    val exactLookups = AtomicInteger()
    val looseLookups = AtomicInteger()
    val reports: MutableList<PlaybackReport> = Collections.synchronizedList(ArrayList())

    fun playableFor(fileName: String, size: Long = exact[fileName] ?: 0) =
        PlayableMedia(itemId = "$key:$fileName", url = "http://$key/$fileName", fileName = fileName, sizeBytes = size, durationSeconds = 100.0, title = fileName, sourceKey = key)

    private fun item(fileName: String) = MediaItem("$key:$fileName", fileName, MediaItemType.MOVIE, false, path = "/m/$fileName", sourceKey = key)

    override suspend fun findExact(fileName: String): ResolveResult.Found? {
        exactLookups.incrementAndGet()
        delay(lookupDelayMs)
        val name = exact.keys.firstOrNull { it.equals(fileName, ignoreCase = true) } ?: return null
        return ResolveResult.Found(item(name), playableFor(name), MatchKind.EXACT_FILENAME)
    }

    override suspend fun resolveByFilename(fileName: String): ResolveResult {
        looseLookups.incrementAndGet()
        findExact(fileName)?.let { return it }
        val name = loose[fileName] ?: return ResolveResult.NotFound(fileName, "Not in $displayName")
        return ResolveResult.Found(item(name), playableFor(name), MatchKind.PARSED_SEARCH)
    }

    override suspend fun reportPlayback(report: PlaybackReport) {
        if (failReports) throw MediaSourceException("$displayName is down")
        reports += report
    }

    override suspend fun libraries() = emptyList<MediaItem>()
    override suspend fun children(parent: MediaItem) = emptyList<MediaItem>()
    override suspend fun search(query: String, limit: Int) = emptyList<MediaItem>()
    override suspend fun recent(limit: Int) = emptyList<MediaItem>()
    override suspend fun playable(item: MediaItem) = playableFor(item.name)
    override fun imageUrl(item: MediaItem, maxWidth: Int): String? = null
}
