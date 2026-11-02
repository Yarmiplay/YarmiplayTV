package com.yarmiplaytv.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.yarmiplaytv.desktop.User32.Companion.GWL_STYLE
import com.yarmiplaytv.desktop.User32.Companion.MONITOR_DEFAULTTONEAREST
import com.yarmiplaytv.desktop.User32.Companion.SWP_FRAMECHANGED
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOOWNERZORDER
import com.yarmiplaytv.desktop.User32.Companion.SWP_NOZORDER
import com.yarmiplaytv.desktop.User32.Companion.SW_MAXIMIZE
import com.yarmiplaytv.desktop.User32.Companion.SW_RESTORE
import com.yarmiplaytv.desktop.User32.Companion.WS_CAPTION
import com.yarmiplaytv.desktop.User32.Companion.WS_THICKFRAME
import java.awt.Rectangle
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Fullscreen that survives switching to other windows. Compose's [WindowPlacement.Fullscreen] uses AWT's
 * exclusive full-screen mode on Windows, which minimizes the window whenever it loses activation (clicking
 * a chat on the second monitor would hide the video). On Windows we instead strip the frame and cover the
 * monitor, like mpv and browsers do; elsewhere Compose's placement is fine.
 *
 * After the Win32 resize we sync [WindowState] and nudge AWT on the EDT so Compose's scene (player + overlays)
 * re-layouts to the new client size. Skipping that left the first F-key fullscreen with a cropped player and
 * a gray gutter — native HWND grew while Compose still used the old floating size.
 */
internal class Fullscreen(private val state: WindowState) {
    private var window: Window? = null
    private var saved: Saved? = null

    var isFullscreen: Boolean = false
        private set

    fun attach(window: Window) {
        this.window = window
    }

    fun set(on: Boolean) {
        if (on == isFullscreen) return
        val w = window
        if (!isWindows || w == null) {
            state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
            isFullscreen = on
            return
        }
        // Win32 + AWT must run on the EDT so componentResized reaches ComposePanel.
        val apply = Runnable { applyWindows(w, on) }
        if (SwingUtilities.isEventDispatchThread()) apply.run()
        else SwingUtilities.invokeAndWait(apply)
    }

    fun toggle() = set(!isFullscreen)

    private fun applyWindows(w: Window, on: Boolean) {
        val hwnd = Native.getWindowPointer(w)
        if (hwnd == null) {
            state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
            isFullscreen = on
            return
        }
        val user32 = User32.INSTANCE
        if (on) {
            val style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE)
            val rect = Win32Rect()
            user32.GetWindowRect(hwnd, rect)
            saved = Saved(
                style = style,
                x = rect.left,
                y = rect.top,
                width = rect.right - rect.left,
                height = rect.bottom - rect.top,
                maximized = state.placement == WindowPlacement.Maximized,
                composeX = w.x,
                composeY = w.y,
                composeW = w.width,
                composeH = w.height,
            )
            if (state.placement == WindowPlacement.Maximized) user32.ShowWindow(hwnd, SW_RESTORE)
            val info = Win32MonitorInfo()
            user32.GetMonitorInfoW(user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), info)
            val m = info.rcMonitor
            user32.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
            user32.SetWindowPos(
                hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top,
                SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER,
            )
            // Cover the screen in AWT's logical coordinates and publish that size to WindowState so
            // Compose's updater does not keep the pre-fullscreen floating size.
            val screen = w.graphicsConfiguration.bounds
            adoptBounds(w, screen)
        } else {
            val s = saved ?: return
            user32.SetWindowLongPtrW(hwnd, GWL_STYLE, s.style)
            user32.SetWindowPos(hwnd, null, s.x, s.y, s.width, s.height, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER)
            if (s.maximized) {
                user32.ShowWindow(hwnd, SW_MAXIMIZE)
                state.placement = WindowPlacement.Maximized
                // Maximized: let AWT report the restored work-area size to Compose.
                syncWindowStateFromAwt(w)
            } else {
                adoptBounds(w, Rectangle(s.composeX, s.composeY, s.composeW, s.composeH))
            }
            saved = null
        }
        isFullscreen = on
        w.revalidate()
        w.repaint()
    }

    /**
     * Sets AWT bounds and matching [WindowState] size/position (Compose Desktop treats window Dp as AWT pixels).
     * Visible for tests.
     */
    internal fun adoptBounds(w: Window, bounds: Rectangle) {
        w.bounds = bounds
        publishFloatingBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    /** Publishes the window's current AWT size into [WindowState] without changing the HWND. */
    internal fun syncWindowStateFromAwt(w: Window) {
        publishFloatingBounds(w.x, w.y, w.width, w.height)
    }

    /**
     * Writes floating [WindowState] size/position from AWT pixel bounds.
     * Compose Desktop's setSizeSafely uses these Dp values 1:1 as window pixels.
     */
    internal fun publishFloatingBounds(x: Int, y: Int, width: Int, height: Int) {
        state.placement = WindowPlacement.Floating
        state.position = WindowPosition(x.dp, y.dp)
        state.size = DpSize(width.dp, height.dp)
    }

    private class Saved(
        val style: Long,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val maximized: Boolean,
        val composeX: Int,
        val composeY: Int,
        val composeW: Int,
        val composeH: Int,
    )
}
