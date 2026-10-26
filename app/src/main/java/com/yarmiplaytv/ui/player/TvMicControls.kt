package com.yarmiplaytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.OnVideo
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
    IconAction(
        if (live) Icons.Filled.Mic else Icons.Filled.MicOff,
        if (live) "Mic live — press to mute" else "Mic off — press to talk",
        onToggle,
        mod,
        tint = tint,
    )
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
