package com.syncplaytv.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
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
        if (on) {
            val style = User32.INSTANCE.GetWindowLongPtrW(hwnd, GWL_STYLE)
            val rect = Rect()
            User32.INSTANCE.GetWindowRect(hwnd, rect)
            saved = Saved(style, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top, state.placement == WindowPlacement.Maximized)
            if (state.placement == WindowPlacement.Maximized) User32.INSTANCE.ShowWindow(hwnd, SW_RESTORE)
            val info = MonitorInfo()
            User32.INSTANCE.GetMonitorInfoW(User32.INSTANCE.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), info)
            val m = info.rcMonitor
            User32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
            User32.INSTANCE.SetWindowPos(hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER)
        } else {
            val s = saved ?: return
            User32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, s.style)
            User32.INSTANCE.SetWindowPos(hwnd, null, s.x, s.y, s.width, s.height, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOOWNERZORDER)
            if (s.maximized) User32.INSTANCE.ShowWindow(hwnd, SW_MAXIMIZE)
        }
        isFullscreen = on
    }

    fun toggle() = set(!isFullscreen)

    private class Saved(val style: Long, val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean

        companion object {
            val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }
        }
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 40
        @JvmField var rcMonitor = Rect()
        @JvmField var rcWork = Rect()
        @JvmField var dwFlags = 0
    }

    private companion object {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        const val GWL_STYLE = -16
        const val WS_CAPTION = 0x00C00000L
        const val WS_THICKFRAME = 0x00040000L
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOOWNERZORDER = 0x0200
        const val SWP_FRAMECHANGED = 0x0020
        const val SW_RESTORE = 9
        const val SW_MAXIMIZE = 3
        const val MONITOR_DEFAULTTONEAREST = 2
    }
}
