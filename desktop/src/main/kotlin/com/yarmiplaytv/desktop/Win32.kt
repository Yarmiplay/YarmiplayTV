package com.yarmiplaytv.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/** The window-frame calls full screen and the mini player make on Windows. */
@Suppress("FunctionName")
internal interface User32 : StdCallLibrary {
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    fun GetWindowRect(hwnd: Pointer, rect: Win32Rect): Boolean
    fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer
    fun GetMonitorInfoW(monitor: Pointer, info: Win32MonitorInfo): Boolean

    companion object {
        val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }

        const val GWL_STYLE = -16
        const val WS_CAPTION = 0x00C00000L
        const val WS_THICKFRAME = 0x00040000L
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOOWNERZORDER = 0x0200
        const val SWP_FRAMECHANGED = 0x0020
        const val SW_RESTORE = 9
        const val SW_MAXIMIZE = 3
        const val MONITOR_DEFAULTTONEAREST = 2
    }
}

@Structure.FieldOrder("left", "top", "right", "bottom")
internal class Win32Rect : Structure() {
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
}

@Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
internal class Win32MonitorInfo : Structure() {
    @JvmField var cbSize = 40
    @JvmField var rcMonitor = Win32Rect()
    @JvmField var rcWork = Win32Rect()
    @JvmField var dwFlags = 0
}

internal val isWindows = System.getProperty("os.name").startsWith("Windows")
