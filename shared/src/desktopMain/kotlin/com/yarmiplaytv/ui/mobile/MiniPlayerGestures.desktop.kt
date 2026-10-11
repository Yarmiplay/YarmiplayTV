package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.MouseInfo
import java.awt.Point
import kotlin.math.abs

actual val pictureInPictureLabel = "Mini player (P)"

/**
 * Dragging moves the window. The pointer is followed in screen coordinates: in window coordinates it would seem to
 * move back by however far the window just moved. A press that doesn't move is a click; two within the double-click
 * time leave the mini player (single clicks do nothing, the hover buttons are for that).
 */
actual fun Modifier.miniPlayerGestures(onExit: () -> Unit): Modifier = composed {
    val exit by rememberUpdatedState(onExit)
    pointerInput(Unit) {
        var lastClick = 0L
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val start = screenPointer()
            var last = start
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                val now = screenPointer()
                if (now != null && last != null && start != null) {
                    if (!dragged && abs(now.x - start.x) + abs(now.y - start.y) > viewConfiguration.touchSlop) dragged = true
                    if (dragged && (now.x != last.x || now.y != last.y)) {
                        DesktopPlayerInput.moveWindowBy(now.x - last.x, now.y - last.y)
                        last = now
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (!dragged) {
                val at = down.uptimeMillis
                if (at - lastClick <= viewConfiguration.doubleTapTimeoutMillis) {
                    lastClick = 0L
                    exit()
                } else {
                    lastClick = at
                }
            }
        }
    }
}

private fun screenPointer(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
