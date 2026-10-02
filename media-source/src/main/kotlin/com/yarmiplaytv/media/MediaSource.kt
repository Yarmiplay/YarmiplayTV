package com.yarmiplaytv.media

/**
 * A browsable library of video files. Jellyfin is the first implementation; Plex or SMB can be
 * added behind the same interface. All methods are safe to call from any coroutine context.
 */
interface MediaSource {
    val displayName: String

    /** Top-level entry points (libraries / views). */
    suspend fun libraries(): List<MediaItem>

    /** Direct children of a folder-like item (library, series, season, folder). */
    suspend fun children(parent: MediaItem): List<MediaItem>

    suspend fun search(query: String, limit: Int = 60): List<MediaItem>

    /** Continue-watching / recently added style shortcuts for the home screen. */
    suspend fun recent(limit: Int = 24): List<MediaItem>

    /** Resolves a playable stream (URL plus the real file name/size used for Syncplay matching). */
    suspend fun playable(item: MediaItem): PlayableMedia

    /**
     * Finds the library item whose underlying file matches [fileName] (as it appears in a
     * Syncplay shared playlist). Equivalent to the desktop client's media-directory lookup.
     */
    suspend fun resolveByFilename(fileName: String): ResolveResult

    fun imageUrl(item: MediaItem, maxWidth: Int = 400): String?
}

enum class MediaItemType { LIBRARY, FOLDER, SERIES, SEASON, EPISODE, MOVIE, VIDEO, OTHER }

data class MediaItem(
    val id: String,
    val name: String,
    val type: MediaItemType,
    val isFolder: Boolean,
    val collectionType: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val durationSeconds: Double? = null,
    val path: String? = null,
    val imageTag: String? = null,
    val played: Boolean = false,
    val resumeSeconds: Double = 0.0,
) {
    val isPlayable: Boolean get() = !isFolder && type in setOf(MediaItemType.EPISODE, MediaItemType.MOVIE, MediaItemType.VIDEO)

    /** Display title, e.g. "S01E01 · The Goddess (Neptune) Of Planeptune". */
    val displayTitle: String
        get() = if (type == MediaItemType.EPISODE && indexNumber != null) {
            val s = parentIndexNumber?.let { "S%02d".format(it) } ?: ""
            "${s}E%02d · $name".format(indexNumber)
        } else name

    val fileName: String? get() = path?.let(FileNames::baseName)
}

data class PlayableMedia(
    val itemId: String,
    val url: String,
    /** File name exactly as on disk; this is what Syncplay peers compare against. */
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Double,
    val title: String,
)

sealed interface ResolveResult {
    data class Found(val item: MediaItem, val playable: PlayableMedia, val matchedBy: MatchKind) : ResolveResult
    data class NotFound(val fileName: String, val reason: String) : ResolveResult
}

enum class MatchKind { EXACT_FILENAME, NORMALIZED_FILENAME, PARSED_SEARCH }

class MediaSourceException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)
