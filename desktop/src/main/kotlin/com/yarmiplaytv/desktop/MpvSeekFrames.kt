package com.yarmiplaytv.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.local.SeekOffers
import com.yarmiplaytv.local.fetchPreview
import com.yarmiplaytv.local.previewTarget
import com.yarmiplaytv.media.StoredSeekFrames
import com.yarmiplaytv.media.TrickplayTile
import com.yarmiplaytv.media.trickplayTile
import com.yarmiplaytv.player.desktop.MpvFrameGrabber
import java.io.File
import java.util.Locale
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** Seek bar previews from mpv: the key frame at or before the time, decoded by the same grabber as the file lists'. */
internal object MpvSeekFrames : SeekFrames {
    private val sheets = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 4
    }

    override fun frame(uri: String, seconds: Double, width: Int): ImageBitmap? {
        serverFrame(uri, seconds)?.let { return it }
        val target = previewTarget(uri)
        val path = when {
            SeekFrames.canPreview(target) -> FileLocalLibrary.pathOf(target).toString()
            target.startsWith("http://") || target.startsWith("https://") -> target
            else -> return null
        }
        val file = File.createTempFile("yarmiplaytv-seek", ".jpg")
        try {
            val start = String.format(Locale.ROOT, "%.3f", seconds.coerceAtLeast(0.0))
            if (!MpvFrameGrabber.grab(path, width, file, start)) return null
            return Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        } catch (e: Exception) {
            return null
        } finally {
            file.delete()
        }
    }

    private fun serverFrame(uri: String, seconds: Double): ImageBitmap? = when (val offer = SeekOffers.of(uri)) {
        null -> null
        is StoredSeekFrames.Jellyfin -> {
            val tile = trickplayTile(offer.set, seconds) ?: return null
            val bytes = sheets[offer.sheetUrl(tile.sheet)] ?: fetchPreview(offer.sheetUrl(tile.sheet))?.also { sheets[offer.sheetUrl(tile.sheet)] = it }
            bytes?.let { crop(it, tile) }
        }
        is StoredSeekFrames.Plex -> {
            val bytes = fetchPreview(offer.jpegUrl(seconds))
            if (bytes == null) {
                SeekOffers.drop(uri)
                null
            } else {
                runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            }
        }
    }

    private fun crop(bytes: ByteArray, tile: TrickplayTile): ImageBitmap? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        val x = tile.x.coerceIn(0, (image.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (image.height - 1).coerceAtLeast(0))
        val w = tile.width.coerceAtMost(image.width - x).coerceAtLeast(1)
        val h = tile.height.coerceAtMost(image.height - y).coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            image,
            Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()
}
