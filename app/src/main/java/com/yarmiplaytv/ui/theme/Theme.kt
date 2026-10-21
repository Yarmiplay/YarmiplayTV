package com.yarmiplaytv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/** TV Material's colours from [palette] (by default the one already in use), which [content] also draws with. */
@Composable
fun YarmiplayTvTheme(palette: Palette = LocalPalette.current, content: @Composable () -> Unit) {
    val scheme = with(palette) {
        if (isLight) {
            lightColorScheme(
                primary = accent, onPrimary = onAccent, background = background, onBackground = text,
                surface = surface, onSurface = onSurface, surfaceVariant = surfaceHigh, onSurfaceVariant = textDim, error = error,
            )
        } else {
            darkColorScheme(
                primary = accent, onPrimary = onAccent, background = background, onBackground = text,
                surface = surface, onSurface = onSurface, surfaceVariant = surfaceHigh, onSurfaceVariant = textDim, error = error,
            )
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
