package com.yarmiplaytv.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Browses several servers as one: libraries side by side, searches and shortcuts merged, and each
 * item's own calls routed back to the server it came from (by [MediaItem.sourceKey]).
 */
class CompositeMediaSource(val sources: List<MediaSource>) : MediaSource {
    init {
        require(sources.isNotEmpty())
    }

    override val key: String get() = KEY
    override val kind: String get() = KEY
    override val displayName: String get() = sources.joinToString(" & ") { it.displayName }

    fun sourceOf(item: MediaItem): MediaSource? = sources.firstOrNull { it.key == item.sourceKey }

    private fun route(item: MediaItem): MediaSource =
        sourceOf(item) ?: throw MediaSourceException("${item.name} belongs to a server that isn't connected")

    /** Runs [block] on every source at once; a failing server is skipped unless all of them fail. */
    private suspend fun <T> all(block: suspend (MediaSource) -> List<T>): List<List<T>> = coroutineScope {
        val results = sources.map { s -> async { runCatching { block(s) } } }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.map { it.getOrDefault(emptyList()) }
    }

    override suspend fun libraries(): List<MediaItem> = all { it.libraries() }.flatten()

    override suspend fun children(parent: MediaItem): List<MediaItem> = route(parent).children(parent)

    override suspend fun search(query: String, limit: Int): List<MediaItem> = merge(all { it.search(query, limit) }).take(limit)

    override suspend fun recent(limit: Int): List<MediaItem> = merge(all { it.recent(limit) }).take(limit)

    override suspend fun playable(item: MediaItem): PlayableMedia = route(item).playable(item)

    override suspend fun resolveByFilename(fileName: String): ResolveResult {
        for (source in sources) {
            val r = runCatching { source.resolveByFilename(fileName) }.getOrNull()
            if (r is ResolveResult.Found) return r
        }
        return ResolveResult.NotFound(fileName, "Not found in $displayName")
    }

    override suspend fun findExact(fileName: String): ResolveResult.Found? =
        sources.firstNotNullOfOrNull { runCatching { it.findExact(fileName) }.getOrNull() }

    override suspend fun reportPlayback(report: PlaybackReport) {
        throw MediaSourceException("Report to the item's own server, not the combined view")
    }

    override fun imageUrl(item: MediaItem, maxWidth: Int): String? = sourceOf(item)?.imageUrl(item, maxWidth)

    /** Interleaves the servers' lists; the same file on two servers is shown once (it plays and reports the same either way). */
    private fun merge(lists: List<List<MediaItem>>): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val files = HashSet<String>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (item in lists.mapNotNull { it.getOrNull(i) }) {
                val file = item.fileName?.lowercase()
                if (file == null || files.add(file)) out += item
            }
        }
        return out
    }

    companion object {
        const val KEY = "all"
    }
}
