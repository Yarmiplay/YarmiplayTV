package com.yarmiplaytv.media.jellyfin

import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.ExternalSubtitle
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.StoredSeekFrames
import com.yarmiplaytv.media.TrickplaySet
import com.yarmiplaytv.media.isTextSubtitleCodec
import com.yarmiplaytv.media.trickplayWidth
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
import com.yarmiplaytv.media.SkipSegment
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class JellyfinSource(
    private val client: JellyfinClient,
    val session: JellyfinSession,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaSource {

    override val key: String = keyOf(session)
    override val kind: String get() = KIND
    override val displayName: String get() = session.serverName

    private val base get() = session.serverUrl
    private val token get() = session.accessToken

    @Volatile private var cachedLibraries: Pair<Long, List<MediaItem>>? = null

    private suspend fun items(query: HttpUrl.Builder.() -> Unit): List<BaseItemDto> =
        client.get<ItemsResult>(base, token, "Items") {
            addQueryParameter("userId", session.userId)
            addQueryParameter("enableTotalRecordCount", "false")
            query()
        }.items

    override suspend fun libraries(): List<MediaItem> {
        val cached = cachedLibraries
        val now = clock()
        if (cached != null && now - cached.first < 60_000) {
            return cached.second
        }
        val views: ItemsResult = try {
            client.get(base, token, "UserViews") { addQueryParameter("userId", session.userId) }
        } catch (e: MediaSourceException) {
            if (e.httpCode != 404) throw e
            client.get(base, token, "Users/${session.userId}/Views") { }
        }
        val result = views.items
            .filter { it.collectionType == null || it.collectionType in VIDEO_COLLECTIONS }
            .map { it.toMediaItem(forceType = MediaItemType.LIBRARY) }
        cachedLibraries = now to result
        return result
    }

    override suspend fun children(parent: MediaItem): List<MediaItem> {
        if (parent.type == MediaItemType.LIBRARY && parent.collectionType == "music") {
            return listOf(
                MediaItem(id = "${parent.id}:artists", name = "Artists", type = MediaItemType.FOLDER, isFolder = true, collectionType = "music_artists", sourceKey = key),
                MediaItem(id = "${parent.id}:albums", name = "Albums", type = MediaItemType.FOLDER, isFolder = true, collectionType = "music_albums", sourceKey = key),
                MediaItem(id = "${parent.id}:tracks", name = "All Tracks", type = MediaItemType.FOLDER, isFolder = true, collectionType = "music_tracks", sourceKey = key),
            )
        }
        val dtos = when {
            parent.collectionType == "music_artists" -> items {
                addQueryParameter("parentId", parent.id.removeSuffix(":artists"))
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "MusicArtist")
                addQueryParameter("sortBy", "SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
            parent.collectionType == "music_albums" -> items {
                addQueryParameter("parentId", parent.id.removeSuffix(":albums"))
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "MusicAlbum")
                addQueryParameter("sortBy", "SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
            parent.collectionType == "music_tracks" -> items {
                addQueryParameter("parentId", parent.id.removeSuffix(":tracks"))
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Audio")
                addQueryParameter("sortBy", "SortName")
                addQueryParameter("limit", "500")
                addQueryParameter("fields", LIST_FIELDS)
            }
            parent.type == MediaItemType.ARTIST -> items {
                addQueryParameter("artistIds", parent.id)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "MusicAlbum,Audio")
                addQueryParameter("sortBy", "IsFolder,ProductionYear,SortName")
                addQueryParameter("sortOrder", "Descending")
                addQueryParameter("fields", LIST_FIELDS)
            }
            parent.type == MediaItemType.ALBUM -> items {
                addQueryParameter("parentId", parent.id)
                addQueryParameter("includeItemTypes", "Audio")
                addQueryParameter("sortBy", "ParentIndexNumber,IndexNumber,SortName")
                addQueryParameter("fields", LIST_FIELDS)
            }
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
            addQueryParameter("includeItemTypes", "Series,Movie,Episode,Video,MusicArtist,MusicAlbum,Audio")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("fields", LIST_FIELDS)
            addQueryParameter("imageTypeLimit", "1")
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
        source?.id?.let { mediaSourceIds[dto.id] = it }
        rememberStreams(dto.id, source)
        val isAudio = dto.type == "Audio"
        val item = dto.toMediaItem()
        val title = when {
            item.artistName != null && item.type == MediaItemType.TRACK -> "${item.artistName} · ${item.name}"
            item.seriesName != null -> "${item.seriesName} · ${item.displayTitle}"
            else -> item.displayTitle
        }
        return PlayableMedia(
            itemId = dto.id,
            url = streamUrl(dto.id, source?.id, isAudio = isAudio),
            fileName = FileNames.baseName(path),
            sizeBytes = source?.size ?: 0L,
            durationSeconds = ticks / TICKS_PER_SECOND,
            title = title,
            sourceKey = key,
            episode = dto.episodeInfo(),
        )
    }

    /** An episode's show, season and number as Jellyfin has them; a MyAnimeList id when an anime plugin set one. */
    private fun BaseItemDto.episodeInfo(): EpisodeInfo? {
        if (type != "Episode") return null
        val series = seriesName?.takeIf { it.isNotBlank() } ?: return null
        val mal = providerIds?.entries?.firstOrNull { it.key.equals("MyAnimeList", true) || it.key.equals("Mal", true) }?.value?.toIntOrNull()
        return EpisodeInfo(series, parentIndexNumber, indexNumber, productionYear, seriesKey = "$key/${seriesId ?: series}", malId = mal)
    }

    /** The media source (version) each item was last resolved to, so reports name the one being played. */
    private val mediaSourceIds = ConcurrentHashMap<String, String>()

    /** Subtitle files beside the item, from the last time its media source was read. */
    private val subsByItem = ConcurrentHashMap<String, List<ExternalSubtitle>>()

    private fun rememberStreams(itemId: String, source: MediaSourceInfo?) {
        subsByItem[itemId] = source?.mediaStreams.orEmpty().mapNotNull { stream ->
            if (stream.type != "Subtitle" || stream.isExternal != true || !isTextSubtitleCodec(stream.codec)) return@mapNotNull null
            val delivery = stream.deliveryUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = stream.displayTitle?.takeIf { it.isNotBlank() } ?: "Subtitles"
            ExternalSubtitle(withToken(delivery), title, stream.language?.takeIf { it.isNotBlank() })
        }
    }

    private fun withToken(delivery: String): String {
        val absolute = if (delivery.startsWith("http://") || delivery.startsWith("https://")) delivery
        else base.trimEnd('/') + "/" + delivery.trimStart('/')
        if ("api_key=" in absolute) return absolute
        val key = java.net.URLEncoder.encode(token, "UTF-8")
        return absolute + (if ('?' in absolute) "&" else "?") + "api_key=$key"
    }

    override suspend fun storedSeekFrames(itemId: String): StoredSeekFrames? {
        val dto = items {
            addQueryParameter("ids", itemId)
            addQueryParameter("fields", "Path,MediaSources")
        }.firstOrNull() ?: return null
        val source = dto.mediaSources?.firstOrNull { it.protocol == null || it.protocol == "File" }
            ?: dto.mediaSources?.firstOrNull()
        rememberStreams(itemId, source)
        val sets = source?.trickplay.orEmpty()
        val width = trickplayWidth(sets.keys.mapNotNull { it.toIntOrNull() }) ?: return null
        val info = sets[width.toString()] ?: return null
        if (info.interval <= 0 || info.thumbnailCount <= 0 || info.tileWidth <= 0 || info.tileHeight <= 0) return null
        return StoredSeekFrames.Jellyfin(
            base, itemId, width, token,
            TrickplaySet(info.interval, info.width, info.height, info.tileWidth, info.tileHeight, info.thumbnailCount),
        )
    }

    override suspend fun externalSubtitles(itemId: String): List<ExternalSubtitle> {
        if (!subsByItem.containsKey(itemId)) storedSeekFrames(itemId)
        return subsByItem[itemId].orEmpty()
    }

    override suspend fun reportPlayback(report: PlaybackReport) {
        val path = when (report.state) {
            ReportState.STARTED -> "Sessions/Playing"
            ReportState.PLAYING, ReportState.PAUSED -> "Sessions/Playing/Progress"
            ReportState.STOPPED -> "Sessions/Playing/Stopped"
        }
        val body = PlaybackProgressBody(
            itemId = report.itemId,
            mediaSourceId = mediaSourceIds[report.itemId],
            playSessionId = report.sessionId,
            positionTicks = (report.positionSeconds * TICKS_PER_SECOND).toLong(),
            isPaused = report.state == ReportState.PAUSED,
        )
        client.postRaw(base, token, path, client.json.encodeToString(PlaybackProgressBody.serializer(), body)) { }
        if (report.markWatched) markPlayed(report.itemId)
    }

    override suspend fun skipSegments(itemId: String): List<SkipSegment> {
        val result = try {
            client.get<MediaSegmentsResult>(base, token, "MediaSegments/$itemId") {
                addQueryParameter("includeSegmentTypes", "Intro")
                addQueryParameter("includeSegmentTypes", "Outro")
            }
        } catch (e: MediaSourceException) {
            // Before 10.10 there are no media segments.
            if (e.httpCode == 404) return emptyList()
            throw e
        }
        return result.items.mapNotNull { s ->
            val kind = when (s.type) {
                "Intro" -> SkipSegment.Kind.OPENING
                "Outro" -> SkipSegment.Kind.ENDING
                else -> return@mapNotNull null
            }
            SkipSegment(kind, s.startTicks.toDouble() / TICKS_PER_SECOND, s.endTicks.toDouble() / TICKS_PER_SECOND)
                .takeIf { it.end > it.start }
        }.sortedBy { it.start }
    }

    override suspend fun seasonEpisodes(itemId: String): List<PlayableMedia> {
        val episode = items { addQueryParameter("ids", itemId) }.firstOrNull() ?: return emptyList()
        val series = episode.seriesId ?: return emptyList()
        val season = episode.seasonId ?: return emptyList()
        return client.get<ItemsResult>(base, token, "Shows/$series/Episodes") {
            addQueryParameter("userId", session.userId)
            addQueryParameter("seasonId", season)
            addQueryParameter("fields", "Path,MediaSources")
        }.items.mapNotNull { runCatching { playableFrom(it) }.getOrNull() }
    }

    private suspend fun markPlayed(itemId: String) {
        try {
            client.postRaw(base, token, "UserPlayedItems/$itemId", null) { addQueryParameter("userId", session.userId) }
        } catch (e: MediaSourceException) {
            if (e.httpCode != 404) throw e
            client.postRaw(base, token, "Users/${session.userId}/PlayedItems/$itemId", null) { }
        }
    }

    fun streamUrl(itemId: String, mediaSourceId: String?, isAudio: Boolean = false): String {
        val endpoint = if (isAudio) "Audio/$itemId/stream" else "Videos/$itemId/stream"
        return client.url(base, endpoint) {
            addQueryParameter("static", "true")
            if (mediaSourceId != null) addQueryParameter("mediaSourceId", mediaSourceId)
            addQueryParameter("api_key", token)
        }.toString()
    }

    override fun imageUrl(item: MediaItem, maxWidth: Int): String? {
        val tag = item.imageTag ?: return null
        return client.url(base, "Items/${item.id}/Images/Primary") {
            addQueryParameter("maxWidth", maxWidth.toString())
            addQueryParameter("quality", "90")
            addQueryParameter("tag", tag)
        }.toString()
    }

    // --- Filename resolution (shared playlist auto-load) ------------------------------------

    private val index = FilenameIndex(clock, MIN_REFRESH_MS)

    /** Builds (or refreshes) the basename index of every video file across the user's libraries. */
    suspend fun refreshIndex(force: Boolean = false) = index.refresh(force) {
        val entries = ArrayList<FilenameIndex.Entry>()
        var start = 0
        while (true) {
            val page: ItemsResult = client.get(base, token, "Items") {
                addQueryParameter("userId", session.userId)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", "Movie,Episode,Video,MusicVideo,Audio")
                addQueryParameter("fields", "Path")
                addQueryParameter("enableImages", "false")
                addQueryParameter("enableUserData", "false")
                addQueryParameter("startIndex", start.toString())
                addQueryParameter("limit", PAGE_SIZE.toString())
            }
            page.items.forEach { dto -> dto.path?.let { entries += FilenameIndex.Entry(dto.id, FileNames.baseName(it)) } }
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
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
        if (!index.isBuilt) runCatching { refreshIndex() }
        val (candidates, kind) = index.lookup(name) ?: return null
        return foundIn(candidates, kind)
    }

    private suspend fun foundIn(candidates: List<FilenameIndex.Entry>, kind: MatchKind): ResolveResult.Found? {
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
            "MusicArtist" -> MediaItemType.ARTIST
            "MusicAlbum" -> MediaItemType.ALBUM
            "Audio" -> MediaItemType.TRACK
            "Folder", "BoxSet" -> MediaItemType.FOLDER
            else -> if (isFolder == true) MediaItemType.FOLDER else MediaItemType.OTHER
        }
        val artist = artists?.firstOrNull() ?: albumArtist ?: seriesName
        return MediaItem(
            id = id,
            name = name ?: "",
            type = t,
            isFolder = isFolder ?: (t in setOf(MediaItemType.LIBRARY, MediaItemType.FOLDER, MediaItemType.SERIES, MediaItemType.SEASON, MediaItemType.ARTIST, MediaItemType.ALBUM)),
            collectionType = collectionType,
            overview = overview,
            year = productionYear,
            indexNumber = indexNumber,
            parentIndexNumber = parentIndexNumber,
            seriesName = seriesName,
            seriesId = seriesId ?: if (t == MediaItemType.SERIES) id else null,
            artistName = artist,
            albumName = album,
            albumId = albumId,
            durationSeconds = runTimeTicks?.let { it / TICKS_PER_SECOND },
            path = path ?: mediaSources?.firstOrNull()?.path,
            imageTag = imageTags?.get("Primary"),
            played = userData?.played ?: false,
            resumeSeconds = (userData?.playbackPositionTicks ?: 0L) / TICKS_PER_SECOND,
            sourceKey = key,
        )
    }

    companion object {
        const val KIND = "jellyfin"

        /** The [key] of [session]'s server; signing in to the same server again gives the same key. */
        fun keyOf(session: JellyfinSession): String = "$KIND:${session.serverId.ifEmpty { session.serverUrl }}"
        private const val TICKS_PER_SECOND = 10_000_000.0
        private const val PAGE_SIZE = 2000
        private const val MIN_REFRESH_MS = 30_000L
        private const val LIST_FIELDS = "Overview,Path,ProductionYear,ProviderIds,Album,AlbumId,AlbumArtist,Artists"
        private val VIDEO_COLLECTIONS = setOf("movies", "tvshows", "homevideos", "musicvideos", "mixed", "folders", "boxsets", "music")
    }
}
