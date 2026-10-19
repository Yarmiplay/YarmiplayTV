package com.yarmiplaytv.desktop

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
import com.yarmiplaytv.player.SubtitleScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.PipContent
import com.yarmiplaytv.ui.mobile.VOLUME_STEP
import com.yarmiplaytv.ui.mobile.appLogoPainter
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import kotlin.math.roundToInt

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
    val aspect by container.player.state.collectAsState()
    val initial = remember { PipPlacement.bounds(PipPlacement.workArea(near, remembered), aspect.displayAspect, remembered) }
    val state = rememberWindowState(position = WindowPosition(initial.x.dp, initial.y.dp), size = DpSize(initial.width.dp, initial.height.dp))
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
                event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
                onBack,
                onClose,
            )
        },
    ) {
        val awt = window
        fun keepBounds() = onBounds(MiniBounds(awt.x, awt.y, awt.width))
        // Keep the video's shape when it changes (another file).
        LaunchedEffect(aspect.displayAspect) {
            val ratio = PipPlacement.ratio(aspect.displayAspect)
            awt.setSize(awt.width, (awt.width / ratio).roundToInt())
        }
        // All video: what's on it is white whatever the theme, and the seek bar takes the theme's accent.
        MobileTheme(appPalette(container)) {
            PipContent(
                container,
                onBack = { keepBounds(); onBack() },
                onClose = { keepBounds(); onClose() },
                onMove = { dx, dy -> awt.setLocation(awt.x + dx, awt.y + dy) },
                onResize = { dx, dy ->
                    val width = PipPlacement.resizedWidth(awt.width, dx, dy, PipPlacement.ratio(container.player.state.value.displayAspect))
                    awt.setSize(width, (width / PipPlacement.ratio(container.player.state.value.displayAspect)).roundToInt())
                },
            )
        }
    }
}

/** The picture-in-picture window's keys: Space, ←/→, ↑/↓ and M as in the player; z and Z resize subtitles; Esc goes back to the app, P closes it. */
private fun pipKey(
    container: AppContainer,
    key: Key,
    shift: Boolean,
    modified: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
): Boolean {
    val player = container.player
    val step = container.settings.value.playback.seekStepSeconds.toDouble()
    if (!modified && key == Key.Z) {
        container.changeSubtitleScale(if (shift) SubtitleScale.STEP else -SubtitleScale.STEP)
        return true
    }
    when (key) {
        Key.Spacebar, Key.K -> player.setPaused(!player.isPaused)
        Key.DirectionLeft, Key.J -> player.seekRelative(-step)
        Key.DirectionRight, Key.L -> player.seekRelative(step)
        Key.DirectionUp -> DesktopPlayerInput.changeVolume(VOLUME_STEP)
        Key.DirectionDown -> DesktopPlayerInput.changeVolume(-VOLUME_STEP)
        Key.M -> DesktopPlayerInput.volume?.let { it.setMuted(!it.muted.value) }
        Key.Escape -> onBack()
        Key.P -> onClose()
        else -> return false
    }
    return true
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
