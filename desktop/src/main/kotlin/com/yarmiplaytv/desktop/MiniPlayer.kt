package com.yarmiplaytv.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.yarmiplaytv.desktop.User32.Companion.GWL_STYLE
import com.yarmiplaytv.desktop.User32.Companion.SWP_FRAMECHANGED
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOMOVE
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOOWNERZORDER
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOSIZE
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOZORDER
import com.yarmiplaytv.desktop.User32.Companion.WS_CAPTION
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.roundToInt

/** Where the mini player was last: its top-left corner and width, in screen pixels. */
internal data class MiniBounds(val x: Int, val y: Int, val width: Int)

/**
 * The desktop's picture-in-picture: the main window becomes a small, always-on-top video in a corner of the screen,
 * shaped like the video. On Windows the title bar goes (the edges still resize it); macOS and Linux keep their frame,
 * since AWT can't take it off a window that's showing. Leaving puts the window back as it was.
 */
internal class MiniPlayer(private val state: WindowState, private val fullscreen: Fullscreen, remembered: MiniBounds?) {
    private var window: Window? = null
    private var saved: Saved? = null

    /** Where it was last, kept with the window's bounds between runs. */
    var remembered: MiniBounds? = remembered
        private set

    val isActive: Boolean get() = saved != null

    fun attach(window: Window) {
        this.window = window
    }

    /** [aspect] is the video's width over height (0 when unknown). Returns whether it's now the mini player. */
    fun set(on: Boolean, aspect: Double = 0.0): Boolean {
        val w = window ?: return false
        if (on == isActive) return isActive
        if (on) enter(w, aspect) else leave(w)
        return isActive
    }

    private fun enter(w: Window, aspect: Double) {
        fullscreen.set(false)
        val maximized = state.placement == WindowPlacement.Maximized
        if (maximized) state.placement = WindowPlacement.Floating
        val style = if (isWindows) Native.getWindowPointer(w)?.let { User32.INSTANCE.GetWindowLongPtrW(it, GWL_STYLE) } else null
        saved = Saved(Rectangle(w.bounds), maximized, w.minimumSize, w.isAlwaysOnTop, style)
        w.minimumSize = Dimension(MIN_WIDTH, MIN_WIDTH * 9 / 16)
        if (style != null) setStyle(w, style and WS_CAPTION.inv())
        w.bounds = miniBounds(workArea(w, remembered), aspect, remembered)
        w.isAlwaysOnTop = true
    }

    private fun leave(w: Window) {
        val s = saved ?: return
        remembered = MiniBounds(w.x, w.y, w.width)
        w.isAlwaysOnTop = s.alwaysOnTop
        s.style?.let { setStyle(w, it) }
        w.minimumSize = s.minimumSize
        w.bounds = s.bounds
        if (s.maximized) state.placement = WindowPlacement.Maximized
        saved = null
    }

    private fun setStyle(w: Window, style: Long) {
        val hwnd = Native.getWindowPointer(w) ?: return
        User32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, style)
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER)
    }

    /** The usable part (without taskbar or dock) of the screen the mini player was last on, else of the window's. */
    private fun workArea(w: Window, last: MiniBounds?): Rectangle {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        val config = last?.let { m -> screens.firstOrNull { it.bounds.contains(Point(m.x, m.y)) } } ?: w.graphicsConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }

    private class Saved(val bounds: Rectangle, val maximized: Boolean, val minimumSize: Dimension, val alwaysOnTop: Boolean, val style: Long?)

    companion object {
        const val DEFAULT_WIDTH = 400
        const val MIN_WIDTH = 240
        /** Space between the mini player and the edges of the screen. */
        const val MARGIN = 16

        /**
         * The mini player's bounds in [work]: [remembered]'s place and width if it has one, else [DEFAULT_WIDTH] in the
         * bottom-right corner. The height follows [aspect] (16:9 when unknown). At most half the screen either way,
         * and always fully on it.
         */
        fun miniBounds(work: Rectangle, aspect: Double, remembered: MiniBounds?): Rectangle {
            val ratio = if (aspect > 0) aspect.coerceIn(0.5, 3.0) else 16.0 / 9.0
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
    }
}
