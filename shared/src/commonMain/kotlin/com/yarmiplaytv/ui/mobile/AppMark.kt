package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * The app icon beside the title: the same dark squircle as the launcher. The launcher foreground is
 * padded for the adaptive-icon safe zone, so the prism is drawn larger inside the tile to match the favicon.
 */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.225f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF152238), Color(0xFF0D1524), Color(0xFF070B12)))),
    ) {
        Icon(
            appLogoPainter(),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = PRISM_SCALE
                scaleY = PRISM_SCALE
            },
        )
    }
}

private const val PRISM_SCALE = 1.5f
