package com.yarmiplaytv.media.subtitles

import kotlinx.serialization.Serializable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Query for online subtitle search. Either [movieHash] or [query] should be non-empty. */
data class SubtitleSearchQuery(
    val query: String,
    val language: String = "en",
    val season: Int? = null,
    val episode: Int? = null,
    val movieHash: String? = null,
    val fileSize: Long? = null,
    val fileName: String? = null,
)

/** One downloadable subtitle result from OpenSubtitles.com or Subdl. */
data class SubtitleSearchResult(
    val id: String,
    val provider: SubtitleProviderKind,
    val releaseName: String,
    val language: String,
    val format: String,
    val downloads: Int = 0,
    val hearingImpaired: Boolean = false,
    val exactHashMatch: Boolean = false,
    val fileId: Long? = null,
    val downloadPath: String? = null,
)

enum class SubtitleProviderKind(val label: String) {
    OPENSUBTITLES("OpenSubtitles"),
    SUBDL("Subdl"),
}

/** Optional OpenSubtitles.com account session (JWT token kept locally; password is never persisted). */
@Serializable
data class OpenSubtitlesAccount(
    val username: String,
    val token: String,
) {
    override fun toString() = "OpenSubtitlesAccount(username=$username, token=…)"
}

/** Downloaded subtitle file ready to attach to the player. */
data class DownloadedSubtitle(
    val fileName: String,
    val language: String,
    val content: ByteArray,
)

/** Common subtitle language choices shown in Settings and the subtitle search menu. */
object SubtitleLanguages {
    const val DEFAULT = "en"

    val COMMON: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "pt-br" to "Portuguese (Brazil)",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh-cn" to "Chinese (Simplified)",
        "zh-tw" to "Chinese (Traditional)",
        "ru" to "Russian",
        "ar" to "Arabic",
        "he" to "Hebrew",
        "nl" to "Dutch",
        "pl" to "Polish",
        "tr" to "Turkish",
        "sv" to "Swedish",
        "ro" to "Romanian",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "all" to "Any language",
    )

    fun labelOf(code: String): String {
        val norm = code.trim().lowercase()
        return COMMON.firstOrNull { it.first == norm }?.second ?: norm.uppercase().ifEmpty { "English" }
    }
}

/**
 * Standard 64-bit OpenSubtitles moviehash: file size + sum of little-endian 64-bit words
 * from the first 64 KiB and last 64 KiB of the file, formatted as 16 lowercase hex digits.
 */
object OpenSubtitlesHash {
    const val CHUNK_SIZE = 64 * 1024

    fun compute(first64k: ByteArray, last64k: ByteArray, fileSize: Long): String? {
        if (fileSize < CHUNK_SIZE) return null
        if (first64k.size < CHUNK_SIZE || last64k.size < CHUNK_SIZE) return null
        var hash = fileSize
        hash += sumLittleEndian64(first64k, CHUNK_SIZE)
        hash += sumLittleEndian64(last64k, CHUNK_SIZE)
        return "%016x".format(hash)
    }

    fun compute(channel: FileChannel): String? {
        val size = channel.size()
        if (size < CHUNK_SIZE) return null
        val first = ByteArray(CHUNK_SIZE)
        val last = ByteArray(CHUNK_SIZE)
        if (!readExact(channel, 0L, first)) return null
        if (!readExact(channel, size - CHUNK_SIZE, last)) return null
        return compute(first, last, size)
    }

    fun compute(path: String): String? = runCatching {
        RandomAccessFile(path, "r").use { raf -> compute(raf.channel) }
    }.getOrNull()

    private fun readExact(channel: FileChannel, offset: Long, dst: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(dst)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) return false
            pos += n
        }
        return true
    }

    private fun sumLittleEndian64(bytes: ByteArray, len: Int): Long {
        val buf = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        repeat(len / 8) { sum += buf.long }
        return sum
    }
}
