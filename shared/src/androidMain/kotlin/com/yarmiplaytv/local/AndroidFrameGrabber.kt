package com.yarmiplaytv.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.File

/**
 * The document provider's own thumbnail where it has one (the media store's, for instance), else a frame a tenth of
 * the way in, decoded with the platform's [MediaMetadataRetriever].
 */
class AndroidFrameGrabber(context: Context) : FrameGrabber {
    private val context = context.applicationContext

    override fun grab(uri: String, width: Int, target: File): Boolean {
        val parsed = Uri.parse(uri)
        val bitmap = providerThumbnail(parsed, width) ?: frame(parsed, width) ?: return false
        try {
            return target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun providerThumbnail(uri: Uri, width: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // Most providers can't make one for a video and throw; then it's the retriever's turn.
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(width, width), null) }.getOrNull()
    }

    private fun frame(uri: Uri, width: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Past the black frames and logos most videos open with.
            val atUs = durationMs * 1000 / 10
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) return retriever.getScaledFrameAtTime(atUs, option, width, width)
            val full = retriever.getFrameAtTime(atUs, option) ?: return null
            if (full.width <= width) return full
            return Bitmap.createScaledBitmap(full, width, (full.height.toLong() * width / full.width).toInt().coerceAtLeast(1), true)
                .also { if (it !== full) full.recycle() }
        } catch (e: RuntimeException) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val QUALITY = 85
    }
}
