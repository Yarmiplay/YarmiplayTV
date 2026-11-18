package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.PlaybackState
import kotlinx.coroutines.delay
import java.awt.Cursor

/**
 * The desktop's picture-in-picture window: the video, and on hover the title, play/pause, seeking, the volume, the
 * way back to the app and closing. Dragging the video moves the window ([onMove]); double-clicking it goes back to
 * the app ([onBack]); the corner grip resizes it ([onResize], the window keeps the video's shape). Both drags report
 * the pointer at the press and the pointer now, each with the monitor it is on, so the window can follow in one
 * coordinate space when the monitors differ. See [PipDrag].
 */
@Composable
fun PipContent(
    container: AppContainer,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onMove: (start: PipScreenPointer, current: PipScreenPointer) -> Unit,
    onResize: (start: PipScreenPointer, current: PipScreenPointer) -> Unit,
) {
    val player = container.player
    val state by player.state.collectAsState()
    val nowPlaying by container.playlist.nowPlaying.collectAsState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxSize().background(Color.Black).hoverable(hover).testTag("pip_window")) {
        Box(Modifier.fillMaxSize().moveAndDoubleClick(onMove, onBack)) {
            VideoSurface(player, Modifier.fillMaxSize())
        }
        if (hovered) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(nowPlaying?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onBack, Modifier.testTag("pip_back")) { Icon(Icons.Filled.OpenInFull, "Back to YarmiplayTV", tint = Color.White) }
                IconButton(onClose, Modifier.testTag("pip_close")) { Icon(Icons.Filled.Close, "Close picture-in-picture", tint = Color.White) }
            }
            Surface(
                onClick = { player.setPaused(!player.isPaused) },
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).size(52.dp).testTag("pip_play_pause"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PipSeekBar(container, state)
                PlayerVolumeControl(onInteract = {})
            }
        }
        // Shown with or without the pointer over the window, above the seek bar's place.
        SkipSegmentButton(container, Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 72.dp))
        // Resizing: the window has no frame, so the corner does it.
        Box(
            Modifier.align(Alignment.BottomEnd).size(18.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                .screenDrag(onResize).testTag("pip_resize"),
        )
    }
}

/**
 * Follows the pointer in screen coordinates. Window-local coordinates move with the window being dragged, and a
 * delta of the raw screen point jumps when the pointer crosses onto a monitor with a different scale ([PipDrag]).
 * [onDrag] gets the pointer from the press and the pointer now; the same press instance is passed for the gesture.
 */
private fun Modifier.screenDrag(onDrag: (start: PipScreenPointer, current: PipScreenPointer) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown().consume()
        val start = PipDrag.systemPointer()
        var last: PipScreenPointer? = null
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val now = PipDrag.systemPointer()
            if (start != null && now != null && now != last) {
                onDrag(start, now)
                last = now
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** Dragging moves the window; a double-click calls [onDoubleClick]. */
private fun Modifier.moveAndDoubleClick(
    onMove: (start: PipScreenPointer, current: PipScreenPointer) -> Unit,
    onDoubleClick: () -> Unit,
): Modifier = pointerInput(Unit) {
    var lastClick = 0L
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val startScreen = PipDrag.systemPointer()
        val startPos = down.position
        var dragged = false
        var last: PipScreenPointer? = null
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val change = event.changes.firstOrNull()
            if (!dragged && change != null && (change.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                dragged = true
            }
            val nowScreen = PipDrag.systemPointer()
            if (dragged && startScreen != null && nowScreen != null && nowScreen != last) {
                onMove(startScreen, nowScreen)
                last = nowScreen
            }
            if (event.changes.none { it.pressed }) break
        }
        if (!dragged) {
            val at = down.uptimeMillis
            if (at - lastClick <= viewConfiguration.doubleTapTimeoutMillis) {
                lastClick = 0L
                onDoubleClick()
            } else {
                lastClick = at
            }
        }
    }
}

/** Seek clock for the picture-in-picture window. Its 4 Hz poll stays off the video. */
@Composable
private fun PipSeekBar(container: AppContainer, state: PlaybackState) {
    var position by remember { mutableDoubleStateOf(container.player.currentPosition()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = container.player.currentPosition()
            delay(250)
        }
    }
    val segments by container.skips.segments.collectAsState()
    TvSeekBar(state, position, onInteract = {}, segments = segments) { container.player.seek(it) }
}
