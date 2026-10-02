package com.yarmiplaytv.local

import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.MatchKind

/** A video file on this device (from a media folder or picked directly). [uri] is a content:// URI. */
data class LocalFile(val name: String, val sizeBytes: Long, val uri: String, val folder: String = "")

/** Finds the local file for a shared-playlist entry, the way desktop Syncplay searches its media directories. */
object LocalMatcher {
    data class Match(val file: LocalFile, val kind: MatchKind)

    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "wmv", "flv", "ts", "m2ts", "mts", "mpg", "mpeg", "ogv", "3gp", "vob",
    )

    fun isVideo(name: String, mimeType: String?): Boolean =
        mimeType?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in videoExtensions

    fun find(files: List<LocalFile>, fileName: String): Match? {
        val wanted = FileNames.baseName(fileName)
        files.firstOrNull { it.name == wanted }?.let { return Match(it, MatchKind.EXACT_FILENAME) }
        files.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return Match(it, MatchKind.EXACT_FILENAME) }
        val normalized = FileNames.normalize(wanted)
        if (normalized.isEmpty()) return null
        return files.firstOrNull { FileNames.normalize(it.name) == normalized }?.let { Match(it, MatchKind.NORMALIZED_FILENAME) }
    }
}
