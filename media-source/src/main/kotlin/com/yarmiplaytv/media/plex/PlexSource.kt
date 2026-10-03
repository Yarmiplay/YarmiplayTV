package com.yarmiplaytv.media.plex

import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.FilenameIndex
import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.media.PlaybackReport
import com.yarmiplaytv.media.ReportState
import com.yarmiplaytv.media.ResolveResult
import okhttp3.HttpUrl

class PlexSource(
    private val client: PlexClient,
    val session: PlexSession,
    clock: () -> Long = System::currentTimeMillis,
) : MediaSource {

    override val key: String = keyOf(session)
    override val kind: String get() = KIND
    override val displayName: String get() = session.serverName

    private val base get() = session.serverUrl
    private val token get() = session.serverToken

    private suspend fun container(path: String, query: HttpUrl.Builder.() -> Unit = {}): PlexContainer =
        client.get<PlexResponse>(base, token, path, query).container

    override suspend fun libraries(): List<MediaItem> =
        container("library/sections").directories
            .filter { it.type in VIDEO_SECTIONS }
            .map { d ->
                MediaItem(
                    id = SECTION_PREFIX + d.key,
                    name = d.title ?: "",
                    type = MediaItemType.LIBRARY,
                    isFolder = true,
                    collectionType = if (d.type == "show") "tvshows" else "movies",
                    imageTag = d.art ?: d.thumb,
                    sourceKey = key,
                )
            }

    override suspend fun children(parent: MediaItem): List<MediaItem> {
        val path = if (parent.id.startsWith(SECTION_PREFIX)) {
            "library/sections/${parent.id.removePrefix(SECTION_PREFIX)}/all"
        } else {
            "library/metadata/${parent.id}/children"
        }
        return container(path).metadata.map { it.toMediaItem() }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return container("hubs/search") {
            addQueryParameter("query", query.trim())
            addQueryParameter("limit", limit.toString())
        }.hubs
            .flatMap { it.metadata }
            .filter { it.type in SEARCH_TYPES }
            .take(limit)
            .map { it.toMediaItem() }
    }

    override suspend fun recent(limit: Int): List<MediaItem> {
        val onDeck = runCatching { container("library/onDeck").metadata }.getOrDefault(emptyList())
        val added = container("library/recentlyAdded") {
            addQueryParameter("X-Plex-Container-Start", "0")
            addQueryParameter("X-Plex-Container-Size", limit.toString())
        }.metadata
        return (onDeck + added)
            .filter { it.type in RECENT_TYPES }
            .distinctBy { it.ratingKey }
            .take(limit)
            .map { it.toMediaItem() }
    }

    override suspend fun playable(item: MediaItem): PlayableMedia {
        val meta = metadata(item.id) ?: throw MediaSourceException("Item ${item.name} no longer exists")
        return playableFrom(meta, item.fileName)
    }

    private suspend fun metadata(ratingKey: String): PlexMetadata? =
        container("library/metadata/$ratingKey").metadata.firstOrNull()

    /** Uses the part named [preferredFile] when the item has several (multi-part or multi-version). */
    private fun playableFrom(meta: PlexMetadata, preferredFile: String? = null): PlayableMedia {
        val parts = meta.media.flatMap { m -> m.parts.map { m to it } }.filter { it.second.key != null && it.second.file != null }
        val (media, part) = preferredFile?.let { want -> parts.firstOrNull { FileNames.baseName(it.second.file!!).equals(want, ignoreCase = true) } }
            ?: parts.firstOrNull()
            ?: throw MediaSourceException("${meta.title} has no file")
        val millis = part.duration ?: media.duration ?: meta.duration ?: 0L
        val item = meta.toMediaItem()
        return PlayableMedia(
            itemId = meta.ratingKey,
            url = client.url(base, part.key!!) { addQueryParameter("X-Plex-Token", token) }.toString(),
            fileName = FileNames.baseName(part.file!!),
            sizeBytes = part.size ?: 0L,
            durationSeconds = millis / 1000.0,
            title = item.seriesName?.let { "$it · ${item.displayTitle}" } ?: item.displayTitle,
            sourceKey = key,
        )
    }

    override fun imageUrl(item: MediaItem, maxWidth: Int): String? {
        val thumb = item.imageTag ?: return null
        return client.url(base, "photo/:/transcode") {
            addQueryParameter("width", maxWidth.toString())
            addQueryParameter("height", (maxWidth * 2).toString())
            addQueryParameter("url", thumb)
            addQueryParameter("X-Plex-Token", token)
        }.toString()
    }

    override suspend fun reportPlayback(report: PlaybackReport) {
        val state = when (report.state) {
            ReportState.STARTED, ReportState.PLAYING -> "playing"
            ReportState.PAUSED -> "paused"
            ReportState.STOPPED -> "stopped"
        }
        client.getRaw(base, token, ":/timeline", mapOf("X-Plex-Session-Identifier" to report.sessionId)) {
            addQueryParameter("ratingKey", report.itemId)
            addQueryParameter("key", "/library/metadata/${report.itemId}")
            addQueryParameter("state", state)
            addQueryParameter("time", (report.positionSeconds * 1000).toLong().toString())
            addQueryParameter("duration", (report.durationSeconds * 1000).toLong().toString())
        }
        if (report.markWatched) {
            client.getRaw(base, token, ":/scrobble") {
                addQueryParameter("key", report.itemId)
                addQueryParameter("identifier", "com.plexapp.plugins.library")
            }
        }
    }

    // --- Filename resolution (shared playlist auto-load) ------------------------------------

    private val index = FilenameIndex(clock, MIN_REFRESH_MS)

    /** Builds (or refreshes) the basename index of every movie and episode file on the server. */
    suspend fun refreshIndex(force: Boolean = false) = index.refresh(force) {
        val entries = ArrayList<FilenameIndex.Entry>()
        val sections = container("library/sections").directories.filter { it.type in VIDEO_SECTIONS }
        for (section in sections) {
            val type = if (section.type == "show") TYPE_EPISODE else TYPE_MOVIE
            var start = 0
            while (true) {
                val page = container("library/sections/${section.key}/all") {
                    addQueryParameter("type", type)
                    addQueryParameter("X-Plex-Container-Start", start.toString())
                    addQueryParameter("X-Plex-Container-Size", PAGE_SIZE.toString())
                }
                page.metadata.forEach { meta ->
                    meta.media.flatMap { it.parts }.forEach { part ->
                        part.file?.let { entries += FilenameIndex.Entry(meta.ratingKey, FileNames.baseName(it)) }
                    }
                }
                start += page.metadata.size
                if (page.metadata.isEmpty() || start >= (page.totalSize ?: start)) break
            }
        }
        entries
    }

    val indexedFileCount: Int get() = index.size

    override suspend fun findExact(fileName: String): ResolveResult.Found? {
        val name = FileNames.baseName(fileName.trim())
        if (name.isEmpty()) return null
        if (!index.isBuilt) runCatching { refreshIndex() }
        var candidates = index.exact(name)
        if (candidates.isEmpty()) {
            runCatching { refreshIndex() }
            candidates = index.exact(name)
        }
        return foundIn(candidates, MatchKind.EXACT_FILENAME)
    }

    override suspend fun resolveByFilename(fileName: String): ResolveResult {
        val name = FileNames.baseName(fileName.trim())
        if (name.isEmpty()) return ResolveResult.NotFound(fileName, "Empty file name")

        lookupIndex(name)?.let { return it }
        runCatching { refreshIndex() }
        lookupIndex(name)?.let { return it }

        return try {
            searchParsed(name) ?: ResolveResult.NotFound(name, "Not found in ${session.serverName}")
        } catch (e: MediaSourceException) {
            ResolveResult.NotFound(name, e.message ?: "Lookup failed")
        }
    }

    private suspend fun lookupIndex(name: String): ResolveResult? {
        if (!index.isBuilt) runCatching { refreshIndex() }
        val (candidates, kind) = index.lookup(name) ?: return null
        return foundIn(candidates, kind)
    }

    private suspend fun foundIn(candidates: List<FilenameIndex.Entry>, kind: MatchKind): ResolveResult.Found? {
        for (candidate in candidates) {
            val meta = runCatching { metadata(candidate.id) }.getOrNull() ?: continue
            val playable = runCatching { playableFrom(meta, candidate.fileName) }.getOrNull() ?: continue
            return ResolveResult.Found(meta.toMediaItem(), playable, kind)
        }
        return null
    }

    private suspend fun searchHits(title: String, types: Set<String>, limit: Int): List<PlexMetadata> =
        container("hubs/search") {
            addQueryParameter("query", title)
            addQueryParameter("limit", limit.toString())
        }.hubs.flatMap { it.metadata }.filter { it.type in types }

    private suspend fun searchParsed(name: String): ResolveResult? {
        val parsed = FileNames.parse(name)
        if (parsed.title.isBlank()) return null
        if (parsed.episode != null) {
            for (show in searchHits(parsed.title, setOf("show"), 5)) {
                val episodes = container("library/metadata/${show.ratingKey}/allLeaves").metadata
                val match = episodes.firstOrNull {
                    it.index == parsed.episode && (parsed.season == null || it.parentIndex == parsed.season)
                } ?: continue
                return ResolveResult.Found(match.toMediaItem(), playableFrom(match), MatchKind.PARSED_SEARCH)
            }
            return null
        }
        val movies = searchHits(parsed.title, setOf("movie", "clip"), 10)
        val match = movies.firstOrNull { parsed.year != null && it.year == parsed.year }
            ?: movies.singleOrNull()
            ?: return null
        val full = if (match.media.isEmpty()) metadata(match.ratingKey) ?: return null else match
        return ResolveResult.Found(full.toMediaItem(), playableFrom(full), MatchKind.PARSED_SEARCH)
    }

    private fun PlexMetadata.toMediaItem(): MediaItem {
        val t = when (type) {
            "movie" -> MediaItemType.MOVIE
            "show" -> MediaItemType.SERIES
            "season" -> MediaItemType.SEASON
            "episode" -> MediaItemType.EPISODE
            "clip" -> MediaItemType.VIDEO
            else -> MediaItemType.OTHER
        }
        val folder = t == MediaItemType.SERIES || t == MediaItemType.SEASON
        return MediaItem(
            id = ratingKey,
            name = title ?: "",
            type = t,
            isFolder = folder,
            overview = summary,
            year = year,
            indexNumber = index,
            parentIndexNumber = if (t == MediaItemType.EPISODE) parentIndex else null,
            seriesName = when (t) {
                MediaItemType.EPISODE -> grandparentTitle
                MediaItemType.SEASON -> parentTitle
                MediaItemType.SERIES -> title
                else -> null
            },
            seriesId = when (t) {
                MediaItemType.EPISODE -> grandparentRatingKey
                MediaItemType.SEASON -> parentRatingKey
                MediaItemType.SERIES -> ratingKey
                else -> null
            },
            durationSeconds = duration?.let { it / 1000.0 },
            path = media.firstOrNull()?.parts?.firstOrNull()?.file,
            imageTag = thumb,
            played = if (folder) leafCount != null && leafCount > 0 && leafCount == viewedLeafCount else (viewCount ?: 0) > 0,
            resumeSeconds = (viewOffset ?: 0L) / 1000.0,
            sourceKey = key,
        )
    }

    companion object {
        const val KIND = "plex"

        /** The [key] of [session]'s server; the same server reached at another address keeps it. */
        fun keyOf(session: PlexSession): String = "$KIND:${session.machineId.ifEmpty { session.serverUrl }}"

        /** The [key] [server] gets once it's added. */
        fun keyOf(server: PlexServer): String = "$KIND:${server.machineId}"
        private const val SECTION_PREFIX = "section:"
        private const val TYPE_MOVIE = "1"
        private const val TYPE_EPISODE = "4"
        private const val PAGE_SIZE = 1000
        private const val MIN_REFRESH_MS = 30_000L
        private val VIDEO_SECTIONS = setOf("movie", "show")
        private val SEARCH_TYPES = setOf("movie", "show", "episode", "clip")
        private val RECENT_TYPES = setOf("movie", "episode", "season", "clip")
    }
}
