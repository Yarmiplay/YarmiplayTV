package com.yarmiplaytv.local

import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.MatchKind

/** A video file on this device (from a media folder or picked directly). [uri] is a content:// URI. */
data class LocalFile(
    val name: String,
    val sizeBytes: Long,
    val uri: String,
    val folder: String = "",
    /** Last modified, in milliseconds since the epoch; 0 when unknown. */
    val modified: Long = 0L,
    /** Where it is under the media folder, like "Show/Season 1"; empty for files directly in it. */
    val directory: String = "",
    /** The media folder's URI, which tells apart two media folders with the same name. */
    val folderUri: String = "",
)

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

    /**
     * [find] over the same [files] for many names, such as every entry of a long playlist: each file's name is looked
     * at, and normalized, once rather than per name. The same file wins as with [find].
     */
    class Index(files: List<LocalFile>) {
        private val exact = HashMap<String, LocalFile>()
        private val anyCase = HashMap<String, LocalFile>()
        private val normalized = HashMap<String, LocalFile>()

        init {
            for (file in files) {
                exact.putIfAbsent(file.name, file)
                anyCase.putIfAbsent(caseKey(file.name), file)
                FileNames.normalize(file.name).takeIf { it.isNotEmpty() }?.let { normalized.putIfAbsent(it, file) }
            }
        }

        fun find(fileName: String): Match? {
            val wanted = FileNames.baseName(fileName)
            exact[wanted]?.let { return Match(it, MatchKind.EXACT_FILENAME) }
            anyCase[caseKey(wanted)]?.let { return Match(it, MatchKind.EXACT_FILENAME) }
            val key = FileNames.normalize(wanted).ifEmpty { return null }
            return normalized[key]?.let { Match(it, MatchKind.NORMALIZED_FILENAME) }
        }

        /** Equal for two names exactly when `equals(ignoreCase = true)` is: it compares each character that way. */
        private fun caseKey(name: String): String = buildString(name.length) { name.forEach { append(it.uppercaseChar().lowercaseChar()) } }
    }
}
