package com.yarmiplaytv.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.player.desktop.MpvFrameGrabber
import java.io.File
import java.util.Locale
import org.jetbrains.skia.Image

/** Seek bar previews from mpv: the key frame at or before the time, decoded by the same grabber as the file lists'. */
internal object MpvSeekFrames : SeekFrames {
    override fun frame(uri: String, seconds: Double, width: Int): ImageBitmap? {
        val target = File.createTempFile("yarmiplaytv-seek", ".jpg")
        try {
            val start = String.format(Locale.ROOT, "%.3f", seconds.coerceAtLeast(0.0))
            if (!MpvFrameGrabber.grab(FileLocalLibrary.pathOf(uri).toString(), width, target, start)) return null
            return Image.makeFromEncoded(target.readBytes()).toComposeImageBitmap()
        } catch (e: Exception) {
            return null
        } finally {
            target.delete()
        }
    }
}
