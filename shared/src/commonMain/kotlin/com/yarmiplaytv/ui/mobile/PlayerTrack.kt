package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.player.playbackInfo
import com.yarmiplaytv.ui.theme.AppColors
import kotlin.math.roundToInt

/**
 * Android TV's seek bar for the mouse: the track, with the clocks and what's playing under it in dim text. Click or
 * drag to seek; the bar shows where it will go until the button is let go.
 */
@Composable
internal fun TvSeekBar(state: PlaybackState, position: Double, onInteract: () -> Unit, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.duration
    val shown = dragging?.toDouble() ?: position
    Column(Modifier.fillMaxWidth()) {
        PlayerTrack(
            value = shown.toFloat(),
            valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
            onValueChange = { dragging = it; onInteract() },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toDouble()) }
                dragging = null
            },
            buffered = (position + state.cacheSeconds).toFloat(),
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("seek_slider"),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shown), color = AppColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(playbackInfo(state), color = AppColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(24.dp))
            Text(formatClock(duration), color = AppColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The TV bar's 6 dp track (white at 20 %, the [buffered] part a little lighter, the part up to [value] in the accent
 * colour) as a mouse slider. A knob shows at the value while the mouse is on it or dragging.
 */
@Composable
internal fun PlayerTrack(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    buffered: Float? = null,
    enabled: Boolean = true,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun fractionOf(v: Float) = ((v - valueRange.start) / span).coerceIn(0f, 1f)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val knob = with(LocalDensity.current) { KNOB.toPx() }
    val fraction = fractionOf(value)

    Box(
        modifier
            .height(TOUCH_HEIGHT)
            .onSizeChanged { width = it.width }
            .hoverable(hover, enabled)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                if (enabled) {
                    setProgress { target ->
                        change(target.coerceIn(range))
                        finished()
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = range.start + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    down.consume()
                    change(at(down.position.x))
                    drag(down.id) { moved ->
                        moved.consume()
                        change(at(moved.position.x))
                    }
                    pressed = false
                    finished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(TRACK).clip(RoundedCornerShape(TRACK / 2)).background(Color.White.copy(alpha = 0.2f))) {
            buffered?.let { Box(Modifier.fillMaxWidth(fractionOf(it)).fillMaxHeight().background(Color.White.copy(alpha = 0.25f))) }
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent))
        }
        if (enabled && (hovered || pressed)) {
            Box(
                Modifier
                    .offset { IntOffset((fraction * width - knob / 2).roundToInt(), 0) }
                    .size(KNOB)
                    .clip(CircleShape)
                    .background(AppColors.Accent),
            )
        }
    }
}

private val TRACK = 6.dp
private val KNOB = 14.dp
/** Taller than the track, so it's easy to hit with the mouse. */
private val TOUCH_HEIGHT = 24.dp
