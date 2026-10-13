package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.theme.AppTheme
import com.yarmiplaytv.ui.theme.LocalPalette
import com.yarmiplaytv.ui.theme.Palette

/** Material's colours from [palette] (by default the one already in use), which [content] also draws with. */
@Composable
fun MobileTheme(palette: Palette = LocalPalette.current, content: @Composable () -> Unit) {
    val scheme = with(palette) {
        if (isLight) {
            lightColorScheme(
                primary = accent, onPrimary = onAccent, primaryContainer = surfaceHigh, onPrimaryContainer = text,
                secondary = accent, secondaryContainer = surfaceHigh, onSecondaryContainer = text,
                background = background, onBackground = text, surface = background, onSurface = text,
                surfaceVariant = surfaceHigh, onSurfaceVariant = textDim,
                surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
                outline = textDim, error = error,
            )
        } else {
            darkColorScheme(
                primary = accent, onPrimary = onAccent, primaryContainer = surfaceHigh, onPrimaryContainer = text,
                secondary = accent, secondaryContainer = surfaceHigh, onSecondaryContainer = text,
                background = background, onBackground = text, surface = background, onSurface = text,
                surfaceVariant = surfaceHigh, onSurfaceVariant = textDim,
                surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
                outline = textDim, error = error,
            )
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The palette the Theme setting picks; "Match the system" follows the device's dark mode as it changes. */
@Composable
fun appPalette(container: AppContainer): Palette {
    val settings by container.settings.collectAsStateWithLifecycle()
    return AppTheme.of(settings.theme).palette(systemDark = isSystemInDarkTheme())
}
