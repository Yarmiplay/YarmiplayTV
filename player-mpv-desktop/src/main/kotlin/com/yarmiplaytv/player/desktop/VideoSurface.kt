package com.yarmiplaytv.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Draws [renderer]'s frames. mpv renders at this view's pixel size (letterboxing included), so frames are
 * drawn 1:1 except for the moment between a resize and the next frame.
 */
@Composable
fun VideoSurface(renderer: VideoRenderer?, modifier: Modifier = Modifier) {
    Canvas(modifier.onSizeChanged { renderer?.setSize(it.width, it.height) }) {
        val r = renderer ?: return@Canvas
        r.frameTick.longValue
        val frame = r.frames.acquire() ?: return@Canvas
        r.onPresented(frame)
        // Catch up on the next refresh if the UI fell behind.
        if (r.frames.hasQueued()) r.frameTick.longValue++
        val image = frame.image()
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                Rect.makeWH(size.width, size.height),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }
}
