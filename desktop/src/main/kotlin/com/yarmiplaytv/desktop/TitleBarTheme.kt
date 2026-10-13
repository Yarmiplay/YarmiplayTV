package com.yarmiplaytv.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * A dark title bar on Windows, to go with the app's dark theme whatever the system's mode; AWT draws it light
 * otherwise. The accent colour on title bars, when that's turned on, is still DWM's to draw. Does nothing on other
 * systems: macOS gets its dark title bar through apple.awt.application.appearance (set in main) and Linux window
 * managers draw their own.
 */
internal fun useDarkTitleBar(window: Window) {
    if (!isWindows) return
    runCatching {
        val hwnd = Native.getWindowPointer(window) ?: return
        val on = IntByReference(1)
        val dwm = Dwmapi.INSTANCE
        // Windows 10 before 20H1 knows the attribute by its pre-release number.
        if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, on, 4) != 0) {
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, on, 4)
        }
    }
}

@Suppress("FunctionName")
private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
