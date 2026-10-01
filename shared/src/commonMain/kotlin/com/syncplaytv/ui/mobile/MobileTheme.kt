package com.syncplaytv.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.syncplaytv.ui.theme.AppColors

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = AppColors.OnAccent,
            primaryContainer = AppColors.SurfaceHigh,
            onPrimaryContainer = AppColors.Text,
            secondary = AppColors.Accent,
            secondaryContainer = AppColors.SurfaceHigh,
            onSecondaryContainer = AppColors.Text,
            background = AppColors.Background,
            onBackground = AppColors.Text,
            surface = AppColors.Background,
            onSurface = AppColors.Text,
            surfaceVariant = AppColors.SurfaceHigh,
            onSurfaceVariant = AppColors.TextDim,
            surfaceContainer = AppColors.Surface,
            surfaceContainerLow = AppColors.Surface,
            surfaceContainerHigh = AppColors.SurfaceHigh,
            surfaceContainerHighest = AppColors.SurfaceHigh,
            outline = AppColors.TextDim,
            error = AppColors.Error,
        ),
        content = content,
    )
}
