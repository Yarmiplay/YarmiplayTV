package com.yarmiplaytv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.SpectrumMotion
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.isActive

/**
 * What an audio-only file shows instead of a black frame.
 *
 * When the player is drawing a spectrum from the audio ([PlaybackState.audioVisualizer]), this is only the
 * title, artist and cover, so the picture underneath stays visible. Otherwise it draws an idle spectrum that
 * holds still while playback is paused. A video file never gets here.
 */
@Composable
fun BoxScope.AudioStage(
    player: Player,
    state: PlaybackState,
    fileName: String,
    title: String,
    subtitle: String?,
    compact: Boolean,
) {
    val show = state.fileLoaded && !playbackShowsVideo(state, fileName)
    if (!show) return
    val accent = AppColors.Accent
    val cover by player.coverArt.collectAsStateWithLifecycle()
    val heading = state.mediaTitle ?: title
    val byline = state.mediaArtist ?: subtitle
    LaunchedEffect(accent, state.audioOnly) {
        if (state.audioOnly) player.setAudioVisualizerColor(accent.toArgb() and 0xFFFFFF)
    }
    if (state.audioVisualizer) {
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 16.dp else 28.dp)
                .widthIn(max = if (compact) 360.dp else 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.46f))
                .padding(12.dp)
                .testTag("audio_stage"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CoverArt(cover, if (compact) 56.dp else 84.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, color = Color.White, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!byline.isNullOrBlank()) {
                    Text(byline, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val motion = remember(state.url) { IdleSpectrum() }
    var frame by remember(state.url) { mutableIntStateOf(0) }
    val tick = frame
    LaunchedEffect(state.paused, state.buffering) {
        if (state.paused || state.buffering) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                motion.phase += dt
                val next = SpectrumMotion.approach(motion.levels, SpectrumMotion.idle(motion.phase), dt)
                next.copyInto(motion.levels)
                frame++
            }
        }
    }
    Box(Modifier.fillMaxSize().testTag("audio_stage")) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.28f), Color(0xFF07090D), Color.Black),
                    radius = 900f,
                ),
            ),
        )
        Canvas(Modifier.fillMaxSize()) {
            // [tick] is read during composition, so a paused playback (no new tick) holds this frame.
            val redraw = tick
            if (redraw < 0) return@Canvas
            val n = motion.levels.size
            val gap = size.width * 0.008f
            val barW = (size.width - gap * (n + 1)) / n
            val base = size.height * 0.90f
            val maxH = size.height * (if (compact) 0.28f else 0.34f)
            for (i in 0 until n) {
                val h = (maxH * motion.levels[i]).coerceAtLeast(4f)
                val x = gap + i * (barW + gap)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.18f)),
                        startY = base - h,
                        endY = base,
                    ),
                    topLeft = Offset(x, base - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.10f),
                    topLeft = Offset(x, base + 6f),
                    size = Size(barW, h * 0.28f),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp).padding(bottom = if (compact) 24.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        ) {
            CoverArt(cover, if (compact) 96.dp else 148.dp)
            Text(
                heading,
                color = Color.White,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!byline.isNullOrBlank()) {
                Text(
                    byline,
                    color = Color.White.copy(alpha = 0.72f),
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CoverArt(bytes: ByteArray?, edge: Dp) {
    Box(
        Modifier.size(edge).clip(RoundedCornerShape(edge * 0.16f)).background(AppColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Audiotrack, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(edge * 0.42f))
        if (bytes != null) {
            AsyncImage(
                model = bytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class IdleSpectrum {
    var phase = 0f
    val levels = SpectrumMotion.idle(0f)
}
