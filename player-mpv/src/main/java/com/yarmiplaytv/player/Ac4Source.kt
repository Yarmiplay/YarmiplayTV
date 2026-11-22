package com.yarmiplaytv.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Platform demuxer rows whose MIME type is AC-4. An unreadable address is an empty list, not a failure. */
internal object Ac4Source {
    fun languages(context: Context, url: String): List<String?> {
        val extractor = open(context, url) ?: return emptyList()
        return try {
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                if (!mime.equals(Ac4.MIME, ignoreCase = true)) return@mapNotNull null
                format.getString(MediaFormat.KEY_LANGUAGE)
            }
        } catch (_: RuntimeException) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    fun open(context: Context, url: String): MediaExtractor? = runCatching {
        val extractor = MediaExtractor()
        when {
            url.startsWith("content://") -> extractor.setDataSource(context, Uri.parse(url), null)
            url.startsWith("file://") -> extractor.setDataSource(Uri.parse(url).path ?: error("no path"))
            url.startsWith("http://") || url.startsWith("https://") -> extractor.setDataSource(url)
            url.startsWith("/") -> extractor.setDataSource(url)
            else -> error("no platform demuxer for this address")
        }
        extractor
    }.getOrNull()
}
