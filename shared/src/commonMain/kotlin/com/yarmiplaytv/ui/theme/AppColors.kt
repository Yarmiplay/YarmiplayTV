package com.yarmiplaytv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** One look of the app: the colours everything is drawn with. [isLight] is a light background with dark text. */
@Immutable
data class Palette(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textDim: Color,
    val ready: Color,
    val notReady: Color,
    val error: Color,
    val scrim: Color,
)

object Palettes {
    /** Night blue: the app's own look, and what the player always uses. */
    val Dark = Palette(
        isLight = false,
        background = Color(0xFF0E1116),
        surface = Color(0xFF171B22),
        surfaceHigh = Color(0xFF212733),
        accent = Color(0xFF3DA5F4),
        onAccent = Color(0xFF04121F),
        text = Color(0xFFE8ECF2),
        textDim = Color(0xFF9AA4B2),
        ready = Color(0xFF4CC38A),
        notReady = Color(0xFFFFB74D),
        error = Color(0xFFEF5350),
        scrim = Color(0xCC000000),
    )

    /** Black and white: true black (dark on OLED screens), greys, and white for what's chosen. */
    val Noire = Palette(
        isLight = false,
        background = Color(0xFF000000),
        surface = Color(0xFF0D0D0D),
        surfaceHigh = Color(0xFF1C1C1C),
        accent = Color(0xFFF2F2F2),
        onAccent = Color(0xFF000000),
        text = Color(0xFFF5F5F5),
        textDim = Color(0xFF8E8E8E),
        ready = Color(0xFF7FD1A6),
        notReady = Color(0xFFE6C47A),
        error = Color(0xFFF07A78),
        scrim = Color(0xE6000000),
    )

    /** Daylight: white cards on pale grey, dark text and a deeper blue that reads on white. */
    val Light = Palette(
        isLight = true,
        background = Color(0xFFF3F5F8),
        surface = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFE4E8EE),
        accent = Color(0xFF1668C7),
        onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF12161C),
        textDim = Color(0xFF5A6472),
        ready = Color(0xFF1B8A55),
        notReady = Color(0xFFB86800),
        error = Color(0xFFC62828),
        scrim = Color(0x99000000),
    )
}

/** The themes to pick from in Settings; [SYSTEM] is Light or Dark as the device's own setting is. */
enum class AppTheme(val key: String, val label: String, val description: String) {
    DARK("dark", "Dark", "Night blue, the app's own look"),
    NOIRE("noire", "Noire", "Black and white: true black, darkest on OLED screens"),
    LIGHT("light", "Light", "Light backgrounds and dark text"),
    SYSTEM("system", "Match the system", "Light or dark, as your device is set");

    /** The palette this theme draws with; [systemDark] is whether the device is in dark mode. */
    fun palette(systemDark: Boolean): Palette = when (this) {
        DARK -> Palettes.Dark
        NOIRE -> Palettes.Noire
        LIGHT -> Palettes.Light
        SYSTEM -> if (systemDark) Palettes.Dark else Palettes.Light
    }

    companion object {
        fun of(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Under the theme choices: what no theme changes. */
const val THEME_NOTE = "The player stays dark in every theme, as it sits over the video."

/** The palette for what's below it; the player keeps [Palettes.Dark] whatever the theme, as it sits over the video. */
val LocalPalette = staticCompositionLocalOf { Palettes.Dark }

/** The colours of the palette in use (see [LocalPalette]). */
object AppColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceHigh
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val TextDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textDim
    val Ready: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ready
    val NotReady: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.notReady
    val Error: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.error
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.scrim
    val isLight: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.isLight
}
