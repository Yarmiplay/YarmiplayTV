package com.yarmiplaytv.desktop

import androidx.compose.ui.window.WindowPlacement
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
import java.awt.Window

/**
 * Fullscreen that survives switching to other windows. Compose's [WindowPlacement.Fullscreen] uses AWT's
 * exclusive full-screen mode on Windows, which minimizes the window whenever it loses activation (clicking
 * a chat on the second monitor would hide the video). On Windows we instead strip the frame and cover the
 * monitor, like mpv and browsers do; elsewhere Compose's placement is fine.
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
        val hwnd = Native.getWindowPointer(w) ?: return
        val user32 = User32.INSTANCE
        if (on) {
            val style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE)
            val rect = Win32Rect()
            user32.GetWindowRect(hwnd, rect)
            saved = Saved(style, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top, state.placement == WindowPlacement.Maximized)
            if (state.placement == WindowPlacement.Maximized) user32.ShowWindow(hwnd, SW_RESTORE)
            val info = Win32MonitorInfo()
            user32.GetMonitorInfoW(user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), info)
            val m = info.rcMonitor
            user32.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
            user32.SetWindowPos(hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER)
        } else {
            val s = saved ?: return
            user32.SetWindowLongPtrW(hwnd, GWL_STYLE, s.style)
            user32.SetWindowPos(hwnd, null, s.x, s.y, s.width, s.height, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER)
            if (s.maximized) user32.ShowWindow(hwnd, SW_MAXIMIZE)
        }
        isFullscreen = on
    }

    fun toggle() = set(!isFullscreen)

    private class Saved(val style: Long, val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)
}
