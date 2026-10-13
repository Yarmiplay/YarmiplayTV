package com.yarmiplaytv.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.yarmiplaytv.ui.theme.AppTheme
import java.awt.Window

/**
 * A dark or light title bar on Windows, to go with the app's theme whatever the system's mode; AWT draws it light
 * otherwise. It changes at once when the theme does. The accent colour on title bars, when that's turned on, is still
 * DWM's to draw. Does nothing on other systems: macOS gets its title bar through apple.awt.application.appearance
 * ([macAppearance], set before the first window) and Linux window managers draw their own.
 */
internal fun useTitleBarTheme(window: Window, dark: Boolean) {
    if (!isWindows) return
    runCatching {
        val hwnd = Native.getWindowPointer(window) ?: return
        val value = IntByReference(if (dark) 1 else 0)
        val dwm = Dwmapi.INSTANCE
        // Windows 10 before 20H1 knows the attribute by its pre-release number.
        if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4) != 0) {
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, value, 4)
        }
        // The frame is only redrawn in the new colours when told it changed.
        User32.INSTANCE.SetWindowPos(
            hwnd, null, 0, 0, 0, 0,
            User32.SWP_NOSIZE or User32.SWP_NOMOVE or User32.SWP_NOZORDER or SWP_NOACTIVATE or User32.SWP_FRAMECHANGED,
        )
    }
}

/** macOS's window appearance for the saved [theme] (the value of apple.awt.application.appearance). */
internal fun macAppearance(theme: String): String = when (AppTheme.of(theme)) {
    AppTheme.LIGHT -> "NSAppearanceNameAqua"
    AppTheme.SYSTEM -> "system"
    AppTheme.DARK, AppTheme.NOIRE, AppTheme.MONOCHROME -> "NSAppearanceNameDarkAqua"
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
private const val SWP_NOACTIVATE = 0x0010
