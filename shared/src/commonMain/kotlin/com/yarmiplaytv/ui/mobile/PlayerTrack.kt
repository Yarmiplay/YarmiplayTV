package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.player.playbackInfo
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.OnVideo
import com.yarmiplaytv.ui.theme.onVideoLabel
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android TV's seek bar: the track, with the clocks and what's playing under it in dim text. Click, tap or drag to
 * seek; the bar shows where it will go until the button or finger is let go. While dragging, or with the mouse on
 * it, the time there shows above it, with the frame from [frames] for a video on this device.
 */
@Composable
internal fun TvSeekBar(
    state: PlaybackState,
    position: Double,
    onInteract: () -> Unit,
    frames: SeekFrames? = null,
    /** The opening and ending, marked on the track. */
    segments: List<SkipSegment> = emptyList(),
    onVideo: Boolean = true,
    onSeek: (Double) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hovering by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val duration = state.duration
    val shown = dragging?.toDouble() ?: position
    val pointing = (dragging ?: hovering)?.toDouble()?.takeIf { duration > 0 }
    val previewWidth = with(LocalDensity.current) { PREVIEW_WIDTH.roundToPx() }.coerceAtMost(MAX_PREVIEW_PIXELS)
    val frame = rememberSeekFrame(frames, state.url, duration, pointing, previewWidth)
    val labelColor = if (onVideo) OnVideo.Text else AppColors.TextDim
    val labelModifier = if (onVideo) Modifier.onVideoLabel() else Modifier
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }, contentAlignment = Alignment.CenterStart) {
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
                onHover = { hovering = it; if (it != null) onInteract() },
                onVideo = onVideo,
                modifier = Modifier.fillMaxWidth().testTag("seek_slider"),
            ) { SkipMarks(segments, duration, Modifier.matchParentSize()) }
            pointing?.let { at -> SeekBubble(formatClock(at), frame, (at / duration).toFloat(), width) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shown), color = labelColor, style = MaterialTheme.typography.bodyMedium, modifier = labelModifier)
            Spacer(Modifier.weight(1f))
            playbackInfo(state).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = labelColor, style = MaterialTheme.typography.bodyMedium, modifier = labelModifier)
            }
            Spacer(Modifier.width(24.dp))
            Text(formatClock(duration), color = labelColor, style = MaterialTheme.typography.bodyMedium, modifier = labelModifier)
        }
    }
}

/**
 * Where the seek bar points: the time, and above it the [frame] there when there is one. Sits above the track at
 * [fraction] of its [trackWidth] pixels, kept inside the track's ends, and takes no room of its own.
 */
@Composable
private fun SeekBubble(time: String, frame: ImageBitmap?, fraction: Float, trackWidth: Int) {
    val lift = with(LocalDensity.current) { (KNOB / 2 + 8.dp).roundToPx() }
    Column(
        Modifier
            .layout { measurable, _ ->
                val bubble = measurable.measure(Constraints())
                layout(0, 0) {
                    val x = (fraction * trackWidth - bubble.width / 2f).roundToInt().coerceIn(0, (trackWidth - bubble.width).coerceAtLeast(0))
                    bubble.place(x, -bubble.height - lift)
                }
            }
            .testTag("seek_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        frame?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.width(PREVIEW_WIDTH).testTag("seek_frame").clip(RoundedCornerShape(6.dp)).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            time,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The preview frame for [at] seconds into the video at [url] (null while the bar isn't pointed at, or for a stream).
 * Times go in steps of a 200th of the video (at least a second); one frame is decoded at a time, the newest wanted
 * first, and the last few dozen are kept so going back and forth doesn't decode them again.
 */
@Composable
private fun rememberSeekFrame(frames: SeekFrames?, url: String?, duration: Double, at: Double?, width: Int): ImageBitmap? {
    val source = frames ?: return null
    val video = url?.takeIf { duration > 0 && SeekFrames.canPreview(it) } ?: return null
    val step = (duration / 200).coerceAtLeast(1.0)
    val cache = remember(video, step) {
        object : LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > 40
        }
    }
    var frame by remember(video) { mutableStateOf<ImageBitmap?>(null) }
    val wanted by rememberUpdatedState(at?.let { (it / step).roundToLong() })
    LaunchedEffect(video, step) {
        val decoding = Mutex()
        snapshotFlow { wanted }.filterNotNull().distinctUntilChanged().collectLatest { key ->
            cache[key]?.let { frame = it; return@collectLatest }
            // A drag passes through many steps: only the one it stays on for a moment is decoded.
            delay(40)
            val made = decoding.withLock { withContext(Dispatchers.IO) { source.frame(video, key * step, width) } } ?: return@collectLatest
            cache[key] = made
            frame = made
        }
    }
    DisposableEffect(video) { onDispose { source.release() } }
    return frame.takeIf { at != null }
}

/**
 * The TV bar's 6 dp track (white at 20 %, the [buffered] part a little lighter, the part up to [value] in the accent
 * colour) as a slider. A knob shows at the value while the mouse is on it or it is being dragged. [onHover] hears the
 * value under the mouse while it moves over the track without a button down, and null when it leaves.
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
    onHover: ((Float?) -> Unit)? = null,
    onVideo: Boolean = true,
    /** Drawn on the track, over the played part (e.g. [SkipMarks]). */
    marks: @Composable BoxScope.() -> Unit = {},
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun fractionOf(v: Float) = ((v - valueRange.start) / span).coerceIn(0f, 1f)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val hoverAt by rememberUpdatedState(onHover)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val knob = with(LocalDensity.current) { KNOB.toPx() }
    val fraction = fractionOf(value)
    val trackBaseColor = if (onVideo) Color.White else AppColors.Text

    Box(
        modifier
            .height(playerTrackTouchHeight)
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
            }
            .pointerInput(enabled, onHover != null) {
                if (!enabled || onHover == null) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull() ?: continue
                        when {
                            event.type == PointerEventType.Exit -> hoverAt?.invoke(null)
                            // Touch only moves while pressed: that's a drag, not hovering.
                            !pointer.pressed && (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) -> {
                                val x = pointer.position.x
                                hoverAt?.invoke(range.start + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * (range.endInclusive - range.start))
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(TRACK).clip(RoundedCornerShape(TRACK / 2)).background(trackBaseColor.copy(alpha = 0.2f))) {
            buffered?.let { Box(Modifier.fillMaxWidth(fractionOf(it)).fillMaxHeight().background(trackBaseColor.copy(alpha = 0.25f))) }
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent))
            marks()
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

/**
 * The opening and ending on a seek bar's track, [duration] seconds long: lighter spans, so it's plain where they are
 * (also over the part already played).
 */
@Composable
fun SkipMarks(segments: List<SkipSegment>, duration: Double, modifier: Modifier = Modifier) {
    if (segments.isEmpty() || duration <= 0) return
    Canvas(modifier.testTag("skip_marks")) {
        for (s in segments) {
            val from = (s.start / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            val to = (s.end / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            drawRect(SKIP_MARK, topLeft = Offset(from, 0f), size = Size((to - from).coerceAtLeast(2f), size.height))
        }
    }
}

/** Amber, unlike anything else on the bar, and see-through enough to show the played part under it. */
private val SKIP_MARK = Color(0xFFFFC14D).copy(alpha = 0.75f)

private val TRACK = 6.dp
private val KNOB = 14.dp
private val PREVIEW_WIDTH = 176.dp
/** Wider frames take longer to decode and look no sharper at this size. */
private const val MAX_PREVIEW_PIXELS = 360
