package com.yarmiplaytv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    /** Text on [surface]. Same as [text] except where the card is lighter than the page. */
    val onSurface: Color,
    /** Secondary text on [surface]. Same as [textDim] except where the card is lighter than the page. */
    val onSurfaceDim: Color,
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
        onSurface = Color(0xFFE8ECF2),
        onSurfaceDim = Color(0xFF9AA4B2),
    )

    /** Dark purple: violet-tinted black, purple cards and a bright violet for what's chosen. */
    val Noire = Palette(
        isLight = false,
        background = Color(0xFF100C16),
        surface = Color(0xFF1A1522),
        surfaceHigh = Color(0xFF261E33),
        accent = Color(0xFFA970FF),
        onAccent = Color(0xFF12051F),
        text = Color(0xFFE8ECF2),
        textDim = Color(0xFF9AA4B2),
        ready = Color(0xFF4CC38A),
        notReady = Color(0xFFFFB74D),
        error = Color(0xFFEF5350),
        scrim = Color(0xCC000000),
        onSurface = Color(0xFFE8ECF2),
        onSurfaceDim = Color(0xFF9AA4B2),
    )

    /** Black and white: true black (dark on OLED screens), greys, and white for what's chosen. */
    val Monochrome = Palette(
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
        onSurface = Color(0xFFF5F5F5),
        onSurfaceDim = Color(0xFF8E8E8E),
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
        onSurface = Color(0xFF12161C),
        onSurfaceDim = Color(0xFF5A6472),
    )

    /** Warm black and copper. */
    val Ember = Palette(
        isLight = false,
        background = Color(0xFF14110E),
        surface = Color(0xFF1E1914),
        surfaceHigh = Color(0xFF2C241C),
        accent = Color(0xFFE39A4A),
        onAccent = Color(0xFF1A1006),
        text = Color(0xFFF3EBE3),
        textDim = Color(0xFFA89888),
        ready = Color(0xFF4CC38A),
        notReady = Color(0xFFFFB74D),
        error = Color(0xFFEF5350),
        scrim = Color(0xCC000000),
        onSurface = Color(0xFFF3EBE3),
        onSurfaceDim = Color(0xFFA89888),
    )

    /** Dark slate page, pale ice cards. Text on the page is white; text on a card is black. */
    val Snow = Palette(
        isLight = false,
        background = Color(0xFF75898A),
        surface = Color(0xFFD1E4E6),
        surfaceHigh = Color(0xFF879FA1),
        accent = Color(0xFF75898A),
        onAccent = Color(0xFFFFFFFF),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFFE7F1F2),
        ready = Color(0xFF1B8A55),
        notReady = Color(0xFFB86800),
        error = Color(0xFFC62828),
        scrim = Color(0xCC000000),
        onSurface = Color(0xFF1C2829),
        onSurfaceDim = Color(0xFF3A4C4E),
    )

    /** Leaf green page. Cards are a deeper green so the white text still shows. */
    val Grass = Palette(
        isLight = false,
        background = Color(0xFF538532),
        surface = Color(0xFF3D6424),
        surfaceHigh = Color(0xFF2E4C1B),
        accent = Color(0xFFC6EF84),
        onAccent = Color(0xFF14240A),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFFE8F2DC),
        ready = Color(0xFFB6E8C4),
        notReady = Color(0xFFFFE0A3),
        error = Color(0xFFFFB4B0),
        scrim = Color(0xCC000000),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceDim = Color(0xFFE8F2DC),
    )

    /** Bright blue page. Cards are a deeper blue so the white text still shows. */
    val Sky = Palette(
        isLight = false,
        background = Color(0xFF41B1E9),
        surface = Color(0xFF1872A8),
        surfaceHigh = Color(0xFF125E8C),
        accent = Color(0xFFFFFFFF),
        onAccent = Color(0xFF083048),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFFE7F4FC),
        ready = Color(0xFFD8FFE8),
        notReady = Color(0xFFFFE7B8),
        error = Color(0xFFFFD0CD),
        scrim = Color(0xCC000000),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceDim = Color(0xFFE7F4FC),
    )
}

/** The themes to pick from in Settings; [SYSTEM] is Light or Dark as the device's own setting is. */
enum class AppTheme(val key: String, val label: String, val description: String) {
    DARK("dark", "Dark", "Night blue, the app's own look"),
    NOIRE("noire", "Noire", "Dark purple"),
    MONOCHROME("monochrome", "Monochrome", "Black and white: true black, darkest on OLED screens"),
    LIGHT("light", "Light", "Light backgrounds and dark text"),
    EMBER("ember", "Ember", "Warm black and copper"),
    SNOW("snow", "Snow", "Dark slate and pale ice"),
    GRASS("grass", "Grass", "Leaf green"),
    SKY("sky", "Sky", "Bright blue"),
    SYSTEM("system", "Match the system", "Light or dark, as your device is set");

    /** The palette this theme draws with; [systemDark] is whether the device is in dark mode. */
    fun palette(systemDark: Boolean): Palette = when (this) {
        DARK -> Palettes.Dark
        NOIRE -> Palettes.Noire
        MONOCHROME -> Palettes.Monochrome
        LIGHT -> Palettes.Light
        EMBER -> Palettes.Ember
        SNOW -> Palettes.Snow
        GRASS -> Palettes.Grass
        SKY -> Palettes.Sky
        SYSTEM -> if (systemDark) Palettes.Dark else Palettes.Light
    }

    companion object {
        fun of(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Under the theme choices: what no theme changes. */
const val THEME_NOTE = "The player takes the theme too; what's written on the video stays white, on see-through black."

/** The palette for what's below it. What the player draws straight on the video is [OnVideo]'s instead. */
val LocalPalette = staticCompositionLocalOf { Palettes.Dark }

/** True while drawing on a [Palette.surface] card, so its text stays dark when the page text is white. */
val LocalSurfaceInk = staticCompositionLocalOf { false }

/** Draw [content] with the colours meant for a surface card. [on] false is a raised row, which keeps the page text. */
@Composable
fun SurfaceInk(on: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSurfaceInk provides on, content = content)
}

/** The colours of the palette in use (see [LocalPalette]). */
object AppColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceHigh
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
    val Text: Color @Composable @ReadOnlyComposable get() = if (LocalSurfaceInk.current) LocalPalette.current.onSurface else LocalPalette.current.text
    val TextDim: Color @Composable @ReadOnlyComposable get() = if (LocalSurfaceInk.current) LocalPalette.current.onSurfaceDim else LocalPalette.current.textDim
    val OnSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onSurface
    val OnSurfaceDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onSurfaceDim
    val Ready: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ready
    val NotReady: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.notReady
    val Error: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.error
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.scrim
    val isLight: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.isLight
}
