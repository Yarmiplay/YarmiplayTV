package com.yarmiplaytv.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Seek bar previews decoded with the platform's [MediaMetadataRetriever]: the key frame at or before the time, which
 * is quick. The retriever stays open for the file while the seek bar is in use, since opening it is the slow part.
 */
class AndroidSeekFrames(context: Context) : SeekFrames {
    private val context = context.applicationContext
    private var open: Pair<String, MediaMetadataRetriever>? = null

    @Synchronized
    override fun frame(uri: String, seconds: Double, width: Int): ImageBitmap? = try {
        val retriever = retriever(uri)
        val atUs = (seconds * 1_000_000).toLong().coerceAtLeast(0)
        val option = MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(atUs, option, width, width)
        } else {
            retriever.getFrameAtTime(atUs, option)?.let { full ->
                if (full.width <= width) full
                else Bitmap.createScaledBitmap(full, width, (full.height.toLong() * width / full.width).toInt().coerceAtLeast(1), true)
                    .also { if (it !== full) full.recycle() }
            }
        }
        bitmap?.asImageBitmap()
    } catch (e: RuntimeException) {
        null
    }

    @Synchronized
    override fun release() {
        open?.second?.let { runCatching { it.release() } }
        open = null
    }

    private fun retriever(uri: String): MediaMetadataRetriever {
        open?.let { (openUri, retriever) -> if (openUri == uri) return retriever }
        release()
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.startsWith("/")) retriever.setDataSource(uri) else retriever.setDataSource(context, Uri.parse(uri))
        } catch (e: RuntimeException) {
            runCatching { retriever.release() }
            throw e
        }
        open = uri to retriever
        return retriever
    }
}
