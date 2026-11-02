package com.yarmiplaytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.OnVideo
import com.yarmiplaytv.voice.MicWithActivity
import com.yarmiplaytv.voice.TvVoiceKeys
import com.yarmiplaytv.voice.VoiceUiState
import com.yarmiplaytv.voice.voiceBanner

/** Focusable Mic toggle for the player bar / room panel. Hidden when listen-only. */
@Composable
fun TvMicButton(
    ui: VoiceUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onVideo: Boolean = false,
    level: Float = 0f,
) {
    if (!ui.showMicButton) return
    val live = ui.micLive
    val tint = when {
        live -> AppColors.Ready
        onVideo -> OnVideo.Text
        else -> AppColors.Text
    }
    val mod = (if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier)
        .testTag("tv_mic_toggle")
        .size(48.dp)
    Surface(
        onClick = onToggle,
        modifier = mod,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.Accent,
            contentColor = tint,
            focusedContentColor = AppColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        MicWithActivity(
            live = live,
            level = level,
            tint = tint,
            contentDescription = if (live) "Mic live — press to mute" else "Mic off — press to talk",
            iconSize = 26.dp,
            ringSize = 40.dp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** One-time hint the first time TV voice is available with a usable mic. */
@Composable
fun TvMicHint(ui: VoiceUiState, modifier: Modifier = Modifier) {
    if (!ui.showMicHint) return
    Text(
        TvVoiceKeys.hintText(ui.remoteKeyLabel),
        color = OnVideo.Text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("tv_mic_hint"),
    )
}

@Composable
fun TvVoiceBanner(ui: VoiceUiState, modifier: Modifier = Modifier) {
    val text = voiceBanner(ui) ?: return
    Text(
        text,
        color = if (ui.micLive) AppColors.Ready else AppColors.TextDim,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.testTag("tv_voice_banner"),
    )
}
