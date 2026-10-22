package com.yarmiplaytv.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yarmiplaytv.media.StoredSeekFrames
import com.yarmiplaytv.media.TrickplayTile
import com.yarmiplaytv.media.trickplayTile

/**
 * Seek bar previews decoded with the platform's [MediaMetadataRetriever]: the key frame at or before the time, which
 * is quick. The retriever stays open for the file while the seek bar is in use, since opening it is the slow part.
 */
class AndroidSeekFrames(context: Context) : SeekFrames {
    private val context = context.applicationContext
    private var open: Pair<String, MediaMetadataRetriever>? = null
    private val sheets = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 4
    }

    @Synchronized
    override fun frame(uri: String, seconds: Double, width: Int): ImageBitmap? {
        serverFrame(uri, seconds, width)?.let { return it }
        val target = previewTarget(uri)
        if (target == uri && !SeekFrames.canPreview(uri) && !uri.startsWith("http://") && !uri.startsWith("https://")) return null
        return decode(target, seconds, width)
    }

    private fun serverFrame(uri: String, seconds: Double, width: Int): ImageBitmap? = when (val offer = SeekOffers.of(uri)) {
        null -> null
        is StoredSeekFrames.Jellyfin -> {
            val tile = trickplayTile(offer.set, seconds) ?: return null
            val bytes = sheet(offer.sheetUrl(tile.sheet)) ?: return null
            crop(bytes, tile, width)
        }
        is StoredSeekFrames.Plex -> {
            val bytes = fetchPreview(offer.jpegUrl(seconds))
            if (bytes == null) {
                SeekOffers.drop(uri)
                null
            } else {
                scaled(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), width)
            }
        }
    }

    private fun sheet(url: String): ByteArray? = sheets[url] ?: fetchPreview(url)?.also { sheets[url] = it }

    private fun crop(bytes: ByteArray, tile: TrickplayTile, width: Int): ImageBitmap? {
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val x = tile.x.coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val w = tile.width.coerceAtMost(full.width - x).coerceAtLeast(1)
        val h = tile.height.coerceAtMost(full.height - y).coerceAtLeast(1)
        val piece = Bitmap.createBitmap(full, x, y, w, h)
        if (piece !== full) full.recycle()
        return scaled(piece, width)
    }

    private fun scaled(bitmap: Bitmap?, width: Int): ImageBitmap? {
        bitmap ?: return null
        val out = if (bitmap.width <= width) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1), true)
            .also { if (it !== bitmap) bitmap.recycle() }
        return out.asImageBitmap()
    }

    private fun decode(uri: String, seconds: Double, width: Int): ImageBitmap? = try {
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
            when {
                uri.startsWith("/") -> retriever.setDataSource(uri)
                uri.startsWith("http://") || uri.startsWith("https://") -> retriever.setDataSource(uri, emptyMap())
                else -> retriever.setDataSource(context, Uri.parse(uri))
            }
        } catch (e: RuntimeException) {
            runCatching { retriever.release() }
            throw e
        }
        open = uri to retriever
        return retriever
    }
}
