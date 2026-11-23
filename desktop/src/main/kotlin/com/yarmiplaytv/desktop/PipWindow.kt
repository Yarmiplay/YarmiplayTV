package com.yarmiplaytv.desktop

import com.yarmiplaytv.ui.theme.appIconLabels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.playback.playbackAction
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.PipContent
import com.yarmiplaytv.ui.mobile.PipDrag
import com.yarmiplaytv.ui.mobile.PipMonitor
import com.yarmiplaytv.ui.mobile.PipScreenPointer
import com.yarmiplaytv.ui.mobile.appLogoPainter
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Where the picture-in-picture window was last: its top-left corner and width, in screen pixels. */
internal data class MiniBounds(val x: Int, val y: Int, val width: Int)

/**
 * The desktop's picture-in-picture: a window of its own, small, borderless and always on top, showing the video, while
 * the main window stays usable. It's shaped like the video and starts in the bottom-right corner (or where it was
 * last); [PipPlacement] has the math. [onBounds] gets its place when it closes, to remember it.
 */
@Composable
internal fun PipWindow(
    container: AppContainer,
    remembered: MiniBounds?,
    near: AwtWindow?,
    onBounds: (MiniBounds) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    // Only the shape: the whole state moves with the clock, which would rebuild this window four times a second.
    val aspectFlow = remember(container) { container.player.state.map { it.displayAspect }.distinctUntilChanged() }
    val displayAspect by aspectFlow.collectAsState(container.player.state.value.displayAspect)
    val initial = remember { PipPlacement.bounds(PipPlacement.workArea(near, remembered), displayAspect, remembered) }
    val state = rememberWindowState(position = WindowPosition(initial.x.dp, initial.y.dp), size = DpSize(initial.width.dp, initial.height.dp))
    val scaleSpace = remember { PipDrag.scaleSpace(System.getProperty("os.name").orEmpty()) }
    val drag = remember { PipWindowDrag() }
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "YarmiplayTV",
        icon = appLogoPainter(),
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        onKeyEvent = { event ->
            event.type == KeyEventType.KeyDown && pipKey(
                container,
                event.key,
                event.isShiftPressed,
                event.isCtrlPressed,
                event.isAltPressed,
                event.isMetaPressed,
                onBack,
                onClose,
            )
        },
    ) {
        val awt = window
        LaunchedEffect(Unit) { runCatching { awt.iconImages = LaunchUpdateWindow.windowIcons() } }
        fun keepBounds() = onBounds(MiniBounds(awt.x, awt.y, awt.width))
        // Keep the video's shape when it changes (another file).
        LaunchedEffect(displayAspect) {
            val ratio = PipPlacement.ratio(displayAspect)
            awt.setSize(awt.width, (awt.width / ratio).roundToInt())
        }
        // All video: what's on it is white whatever the theme, and the seek bar takes the theme's accent.
        MobileTheme(appPalette(container), appIconLabels(container)) {
            PipContent(
                container,
                onBack = { keepBounds(); onBack() },
                onClose = { keepBounds(); onClose() },
                onMove = { start, current ->
                    val monitor = awt.graphicsConfiguration?.let { PipDrag.monitorOf(it) } ?: return@PipContent
                    if (drag.moveStart !== start) {
                        drag.moveStart = start
                        drag.moveAnchor = PipDrag.moveAnchor(start, awt.x, awt.y, awt.width, awt.height, monitor, scaleSpace)
                    }
                    val anchor = drag.moveAnchor ?: return@PipContent
                    if (current == start) return@PipContent
                    placePip(awt, PipDrag.moved(anchor, current, PipDrag.systemMonitors(), scaleSpace))
                },
                onResize = { start, current ->
                    val monitor = awt.graphicsConfiguration?.let { PipDrag.monitorOf(it) } ?: return@PipContent
                    if (drag.resizeStart !== start) {
                        drag.resizeStart = start
                        drag.resizeAnchor = PipResizeAnchor(
                            deviceX = PipDrag.toDeviceX(awt.x, monitor, scaleSpace),
                            deviceY = PipDrag.toDeviceY(awt.y, monitor, scaleSpace),
                            userWidth = awt.width,
                            monitor = monitor,
                        )
                    }
                    val anchor = drag.resizeAnchor ?: return@PipContent
                    if (current == start) return@PipContent
                    val ratio = PipPlacement.ratio(container.player.state.value.displayAspect)
                    val (dx, dy) = PipDrag.userDelta(start, current, anchor.monitor.scaleX, anchor.monitor.scaleY, scaleSpace)
                    val width = PipPlacement.resizedWidth(anchor.userWidth, dx, dy, ratio)
                    val height = (width / ratio).roundToInt().coerceAtLeast(1)
                    placePip(
                        awt,
                        PipDrag.resizedBounds(
                            anchor.deviceX,
                            anchor.deviceY,
                            width,
                            height,
                            anchor.monitor,
                            PipDrag.systemMonitors(),
                            scaleSpace,
                        ),
                    )
                },
            )
        }
    }
}

/** The picture-in-picture window's keys match the player's. Esc goes back to the app; F12 closes the window. */
private fun pipKey(
    container: AppContainer,
    key: Key,
    shift: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    meta: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
): Boolean {
    if (meta) return false
    if (key == Key.Escape && !ctrl && !alt && !shift) {
        onBack()
        return true
    }
    val action = playbackAction(key, shift = shift, ctrl = ctrl, alt = alt) ?: return false
    if (action == PlaybackAction.TogglePip) {
        onClose()
        return true
    }
    if (action == PlaybackAction.SkipSegment) {
        if (container.skips.prompt.value == null) return false
        container.skips.skip()
        return true
    }
    if (action == PlaybackAction.Fullscreen || action == PlaybackAction.FocusChat || action == PlaybackAction.ToggleControls) return false
    if (action == PlaybackAction.ToggleMic) {
        if (!container.voice.ui.value.showMicButton) return false
        container.voice.toggleMic()
        return true
    }
    return container.playback.perform(action)
}

/** One press. The start pointer is the same instance for every sample of that gesture, which tells a new press apart. */
private class PipWindowDrag {
    var moveStart: PipScreenPointer? = null
    var moveAnchor: PipDrag.MoveAnchor? = null
    var resizeStart: PipScreenPointer? = null
    var resizeAnchor: PipResizeAnchor? = null
}

private data class PipResizeAnchor(
    val deviceX: Int,
    val deviceY: Int,
    val userWidth: Int,
    val monitor: PipMonitor,
)

/**
 * Moves when only the place changed, and sets the size in the same call when the user-pixel size has to change
 * (the device-pixel size is held while the window crosses onto a different scale). A position-only drag does not
 * send a resize, so the video surface is left alone.
 */
private fun placePip(window: AwtWindow, bounds: PipDrag.Bounds) {
    if (window.width != bounds.width || window.height != bounds.height) {
        window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    } else if (window.x != bounds.x || window.y != bounds.y) {
        window.setLocation(bounds.x, bounds.y)
    }
}

internal object PipPlacement {
    const val DEFAULT_WIDTH = 400
    const val MIN_WIDTH = 240
    /** Space between the window and the edges of the screen. */
    const val MARGIN = 16

    /** The video's width:height, kept between 1:2 and 3:1 (16:9 when unknown). */
    fun ratio(aspect: Double): Double = if (aspect > 0) aspect.coerceIn(0.5, 3.0) else 16.0 / 9.0

    /**
     * The window's bounds in [work]: [remembered]'s place and width if it has one, else [DEFAULT_WIDTH] in the
     * bottom-right corner. The height follows [aspect]. At most half the screen either way, and always fully on it.
     */
    fun bounds(work: Rectangle, aspect: Double, remembered: MiniBounds?): Rectangle {
        val ratio = ratio(aspect)
        var width = (remembered?.width ?: DEFAULT_WIDTH).coerceIn(MIN_WIDTH, (work.width / 2).coerceAtLeast(MIN_WIDTH))
        var height = (width / ratio).roundToInt()
        val maxHeight = (work.height / 2).coerceAtLeast(MIN_WIDTH * 9 / 16)
        if (height > maxHeight) {
            height = maxHeight
            width = (height * ratio).roundToInt()
        }
        val x = remembered?.x ?: (work.x + work.width - width - MARGIN)
        val y = remembered?.y ?: (work.y + work.height - height - MARGIN)
        return Rectangle(
            x.coerceIn(work.x, (work.x + work.width - width).coerceAtLeast(work.x)),
            y.coerceIn(work.y, (work.y + work.height - height).coerceAtLeast(work.y)),
            width,
            height,
        )
    }

    /**
     * The width after dragging the corner by [dx], [dy]: whichever way the drag went further (the height in widths),
     * and never below [MIN_WIDTH].
     */
    fun resizedWidth(width: Int, dx: Int, dy: Int, ratio: Double): Int {
        val byHeight = (dy * ratio).roundToInt()
        val change = if (kotlin.math.abs(byHeight) > kotlin.math.abs(dx)) byHeight else dx
        return (width + change).coerceAtLeast(MIN_WIDTH)
    }

    /** The usable part (without taskbar or dock) of the screen the window was last on, else of [near]'s. */
    fun workArea(near: AwtWindow?, last: MiniBounds?): Rectangle {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        val config = last?.let { m -> screens.firstOrNull { it.bounds.contains(Point(m.x, m.y)) } }
            ?: near?.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }
}
