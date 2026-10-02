package com.yarmiplaytv.media.jellyfin

import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.media.ResolveResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl

class JellyfinSource(
    private val client: JellyfinClient,
    val session: JellyfinSession,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaSource {

    override val displayName: String get() = session.serverName

    private val base get() = session.serverUrl
    private val token get() = session.accessToken

    private suspend fun items(query: HttpUrl.Builder.() -> Unit): List<BaseItemDto> =
        client.get<ItemsResult>(base, token, "Items") {
            addQueryParameter("userId", session.userId)
            query()
        }.items

    override suspend fun libraries(): List<MediaItem> {
        val views: ItemsResult = try {
            client.get(base, token, "UserViews") { addQueryParameter("userId", session.userId) }
        } catch (e: MediaSourceException) {
            if (e.httpCode != 404) throw e
            client.get(base, token, "Users/${session.userId}/Views") { }
        }
        return views.items
            .filter { it.collectionType == null || it.collectionType in VIDEO_COLLECTIONS }
            .map { it.toMediaItem(forceType = MediaItemType.LIBRARY) }
    }

    override suspend fun children(parent: MediaItem): List<MediaItem> {
        val dtos = when {
            parent.type == MediaItemType.SEASON && parent.seriesId != null ->
                client.get<ItemsResult>(base, token, "Shows/${parent.seriesId}/Episodes") {
                    addQueryParameter("userId", session.userId)
                    addQueryParameter("seasonId", parent.id)
                    addQueryParameter("fields", LIST_FIELDS)
                }.items
            parent.type == MediaItemType.LIBRARY && parent.collectionType == "tvshows" -> items {
                addQueryParameter("parentId", parent.id)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Series")
                addQueryParameter("sortBy", "SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
            parent.type == MediaItemType.LIBRARY && parent.collectionType == "movies" -> items {
                addQueryParameter("parentId", parent.id)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Movie")
                addQueryParameter("sortBy", "SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
            else -> items {
                addQueryParameter("parentId", parent.id)
                addQueryParameter("sortBy", if (parent.type == MediaItemType.SERIES) "SortName" else "IsFolder,SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
        }
        return dtos.map { it.toMediaItem() }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return items {
            addQueryParameter("searchTerm", query.trim())
            addQueryParameter("recursive", "true")
            addQueryParameter("includeItemTypes", "Series,Movie,Episode,Video")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("fields", LIST_FIELDS)
        }.map { it.toMediaItem() }
    }

    override suspend fun recent(limit: Int): List<MediaItem> {
        val resume = runCatching {
            client.get<ItemsResult>(base, token, "UserItems/Resume") {
                addQueryParameter("userId", session.userId)
                addQueryParameter("limit", limit.toString())
                addQueryParameter("mediaTypes", "Video")
                addQueryParameter("fields", LIST_FIELDS)
            }.items
        }.getOrElse {
            runCatching {
                client.get<ItemsResult>(base, token, "Users/${session.userId}/Items/Resume") {
                    addQueryParameter("limit", limit.toString())
                    addQueryParameter("fields", LIST_FIELDS)
                }.items
            }.getOrDefault(emptyList())
        }
        val latest = items {
            addQueryParameter("recursive", "true")
            addQueryParameter("includeItemTypes", "Episode,Movie")
            addQueryParameter("sortBy", "DateCreated")
            addQueryParameter("sortOrder", "Descending")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("fields", LIST_FIELDS)
        }
        return (resume + latest).distinctBy { it.id }.take(limit).map { it.toMediaItem() }
    }

    override suspend fun playable(item: MediaItem): PlayableMedia {
        val dto = items {
            addQueryParameter("ids", item.id)
            addQueryParameter("fields", "Path,MediaSources")
        }.firstOrNull() ?: throw MediaSourceException("Item ${item.name} no longer exists")
        return playableFrom(dto)
    }

    private fun playableFrom(dto: BaseItemDto): PlayableMedia {
        val source = dto.mediaSources?.firstOrNull { it.protocol == null || it.protocol == "File" }
            ?: dto.mediaSources?.firstOrNull()
        val path = source?.path ?: dto.path ?: throw MediaSourceException("${dto.name} has no file")
        val ticks = source?.runTimeTicks ?: dto.runTimeTicks ?: 0L
        return PlayableMedia(
            itemId = dto.id,
            url = streamUrl(dto.id, source?.id),
            fileName = FileNames.baseName(path),
            sizeBytes = source?.size ?: 0L,
            durationSeconds = ticks / TICKS_PER_SECOND,
            title = dto.toMediaItem().let { m -> m.seriesName?.let { "$it · ${m.displayTitle}" } ?: m.displayTitle },
        )
    }

    fun streamUrl(itemId: String, mediaSourceId: String?): String =
        client.url(base, "Videos/$itemId/stream") {
            addQueryParameter("static", "true")
            if (mediaSourceId != null) addQueryParameter("mediaSourceId", mediaSourceId)
            addQueryParameter("api_key", token)
        }.toString()

    override fun imageUrl(item: MediaItem, maxWidth: Int): String? {
        val tag = item.imageTag ?: return null
        return client.url(base, "Items/${item.id}/Images/Primary") {
            addQueryParameter("maxWidth", maxWidth.toString())
            addQueryParameter("quality", "90")
            addQueryParameter("tag", tag)
        }.toString()
    }

    // --- Filename resolution (shared playlist auto-load) ------------------------------------

    private data class IndexEntry(val id: String, val fileName: String)

    private val indexLock = Mutex()
    private var byName: Map<String, List<IndexEntry>> = emptyMap()
    private var byNormalized: Map<String, List<IndexEntry>> = emptyMap()
    private var indexBuiltAt = 0L

    /** Builds (or refreshes) the basename index of every video file across the user's libraries. */
    suspend fun refreshIndex(force: Boolean = false) = indexLock.withLock {
        if (!force && indexBuiltAt != 0L && clock() - indexBuiltAt < MIN_REFRESH_MS) return@withLock
        val entries = ArrayList<IndexEntry>()
        var start = 0
        while (true) {
            val page: ItemsResult = client.get(base, token, "Items") {
                addQueryParameter("userId", session.userId)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Movie,Episode,Video,MusicVideo")
                addQueryParameter("fields", "Path")
                addQueryParameter("enableImages", "false")
                addQueryParameter("enableUserData", "false")
                addQueryParameter("startIndex", start.toString())
                addQueryParameter("limit", PAGE_SIZE.toString())
            }
            page.items.forEach { dto -> dto.path?.let { entries += IndexEntry(dto.id, FileNames.baseName(it)) } }
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        byName = entries.groupBy { it.fileName.lowercase() }
        byNormalized = entries.groupBy { FileNames.normalize(it.fileName) }
        indexBuiltAt = clock()
    }

    val indexedFileCount: Int get() = byName.values.sumOf { it.size }

    override suspend fun resolveByFilename(fileName: String): ResolveResult {
        val name = FileNames.baseName(fileName.trim())
        if (name.isEmpty()) return ResolveResult.NotFound(fileName, "Empty file name")

        lookupIndex(name)?.let { return it }
        // A miss may just mean the index is stale (new file added since we built it).
        runCatching { refreshIndex() }
        lookupIndex(name)?.let { return it }

        return try {
            searchParsed(name) ?: ResolveResult.NotFound(name, "Not found in ${session.serverName}")
        } catch (e: MediaSourceException) {
            ResolveResult.NotFound(name, e.message ?: "Lookup failed")
        }
    }

    private suspend fun lookupIndex(name: String): ResolveResult? {
        if (indexBuiltAt == 0L) runCatching { refreshIndex() }
        val exact = byName[name.lowercase()]
        val (candidates, kind) = when {
            !exact.isNullOrEmpty() -> exact to MatchKind.EXACT_FILENAME
            else -> (byNormalized[FileNames.normalize(name)] ?: return null) to MatchKind.NORMALIZED_FILENAME
        }
        for (candidate in candidates) {
            val dto = runCatching {
                items {
                    addQueryParameter("ids", candidate.id)
                    addQueryParameter("fields", "Path,MediaSources,$LIST_FIELDS")
                }.firstOrNull()
            }.getOrNull() ?: continue
            val playable = runCatching { playableFrom(dto) }.getOrNull() ?: continue
            return ResolveResult.Found(dto.toMediaItem(), playable, kind)
        }
        return null
    }

    private suspend fun searchParsed(name: String): ResolveResult? {
        val parsed = FileNames.parse(name)
        if (parsed.title.isBlank()) return null
        val fields = "Path,MediaSources,$LIST_FIELDS"
        if (parsed.episode != null) {
            val series = items {
                addQueryParameter("searchTerm", parsed.title)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Series")
                addQueryParameter("limit", "5")
            }
            for (s in series) {
                val episodes = items {
                    addQueryParameter("parentId", s.id)
                    addQueryParameter("recursive", "true")
                    addQueryParameter("includeItemTypes", "Episode")
                    addQueryParameter("fields", fields)
                }
                val match = episodes.firstOrNull {
                    it.indexNumber == parsed.episode && (parsed.season == null || it.parentIndexNumber == parsed.season)
                } ?: episodes.firstOrNull { it.indexNumber == parsed.episode && parsed.season == 1 && it.parentIndexNumber == null }
                if (match != null) return ResolveResult.Found(match.toMediaItem(), playableFrom(match), MatchKind.PARSED_SEARCH)
            }
            return null
        }
        val movies = items {
            addQueryParameter("searchTerm", parsed.title)
            addQueryParameter("recursive", "true")
            addQueryParameter("includeItemTypes", "Movie,Video")
            addQueryParameter("limit", "10")
            addQueryParameter("fields", fields)
        }
        val match = movies.firstOrNull { parsed.year != null && it.productionYear == parsed.year }
            ?: movies.singleOrNull()
            ?: return null
        return ResolveResult.Found(match.toMediaItem(), playableFrom(match), MatchKind.PARSED_SEARCH)
    }

    private fun BaseItemDto.toMediaItem(forceType: MediaItemType? = null): MediaItem {
        val t = forceType ?: when (type) {
            "CollectionFolder", "UserView" -> MediaItemType.LIBRARY
            "Series" -> MediaItemType.SERIES
            "Season" -> MediaItemType.SEASON
            "Episode" -> MediaItemType.EPISODE
            "Movie" -> MediaItemType.MOVIE
            "Video", "MusicVideo" -> MediaItemType.VIDEO
            "Folder", "BoxSet" -> MediaItemType.FOLDER
            else -> if (isFolder == true) MediaItemType.FOLDER else MediaItemType.OTHER
        }
        return MediaItem(
            id = id,
            name = name ?: "",
            type = t,
            isFolder = isFolder ?: (t in setOf(MediaItemType.LIBRARY, MediaItemType.FOLDER, MediaItemType.SERIES, MediaItemType.SEASON)),
            collectionType = collectionType,
            overview = overview,
            year = productionYear,
            indexNumber = indexNumber,
            parentIndexNumber = parentIndexNumber,
            seriesName = seriesName,
            seriesId = seriesId ?: if (t == MediaItemType.SERIES) id else null,
            durationSeconds = runTimeTicks?.let { it / TICKS_PER_SECOND },
            path = path ?: mediaSources?.firstOrNull()?.path,
            imageTag = imageTags?.get("Primary"),
            played = userData?.played ?: false,
            resumeSeconds = (userData?.playbackPositionTicks ?: 0L) / TICKS_PER_SECOND,
        )
    }

    companion object {
        private const val TICKS_PER_SECOND = 10_000_000.0
        private const val PAGE_SIZE = 2000
        private const val MIN_REFRESH_MS = 30_000L
        private const val LIST_FIELDS = "Overview,Path,ProductionYear"
        private val VIDEO_COLLECTIONS = setOf("movies", "tvshows", "homevideos", "musicvideos", "mixed", "folders", "boxsets")
    }
}
