package com.yarmiplaytv.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.ui.theme.AppColors

/**
 * Remote users whose audio we are hearing right now (not locally muted).
 * Empty when nobody qualifies — callers must render nothing at all (no empty panel).
 * [VoiceUiState.speakingIds] is receive-energy based and clears the same tick as silence
 * (no fade-out, hangover, or linger).
 */
fun VoiceUiState.audibleSpeakingNames(settings: AppSettings): List<String> {
    if (!showUi || connection != VoiceConnection.CONNECTED) return emptyList()
    val self = participantId
    return peers.asSequence()
        .filter { peer ->
            val id = peer.participantId
            id != self &&
                id in speakingIds &&
                !(settings.voicePeerPrefs[peer.username]?.muted ?: false)
        }
        .map { it.username }
        .distinct()
        .toList()
}

/**
 * Ring around a Mic icon that scales with [level] (0…1 post-NS). Hidden when [active] is false
 * (muted / voice off) — draws nothing beyond a transparent canvas of [size].
 */
@Composable
fun MicActivityRing(
    level: Float,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    stroke: Dp = 2.dp,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(active, level) {
        val target = if (active) level.coerceIn(0f, 1f) else 0f
        anim.animateTo(target, spring(stiffness = 500f, dampingRatio = 0.85f))
    }
    Canvas(modifier.size(size)) {
        val v = anim.value
        if (v <= 0.02f) return@Canvas
        val pad = stroke.toPx()
        val radius = (this.size.minDimension / 2f - pad) * (0.72f + 0.28f * v)
        drawCircle(
            color = color.copy(alpha = (0.35f + 0.55f * v).coerceIn(0f, 1f)),
            radius = radius,
            style = Stroke(width = stroke.toPx()),
        )
    }
}

/** Mic icon with optional activity ring; ring omitted when muted / inactive. */
@Composable
fun MicWithActivity(
    live: Boolean,
    level: Float,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    ringSize: Dp = 40.dp,
) {
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        if (live) {
            MicActivityRing(
                level = level,
                active = true,
                color = AppColors.Ready,
                size = ringSize,
            )
        }
        Icon(
            if (live) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Edge list of remote speakers currently heard. Renders **nothing** when [names] is empty —
 * no placeholder, panel, or empty container. Instantly updates as [names] changes.
 */
@Composable
fun VoiceSpeakingOverlay(
    names: List<String>,
    modifier: Modifier = Modifier,
) {
    if (names.isEmpty()) return
    Column(
        modifier
            .testTag("voice_speaking_overlay")
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEach { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("voice_speaking_$name"),
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    MicActivityRing(level = 0.65f, active = true, color = AppColors.Accent, size = 22.dp, stroke = 1.5.dp)
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(12.dp))
                }
                Text(
                    name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
