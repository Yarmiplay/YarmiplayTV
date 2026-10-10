package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.ui.theme.AppColors
import kotlin.math.roundToInt

@Composable
actual fun PlayerVolumeControl(onInteract: () -> Unit, modifier: Modifier) {
    val control = DesktopPlayerInput.volume ?: return
    val volume by control.volume.collectAsState()
    val muted by control.muted.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton({ control.setMuted(!muted); onInteract() }, Modifier.testTag("volume_mute")) {
            Icon(
                when {
                    muted || volume <= 0.0 -> Icons.AutoMirrored.Filled.VolumeOff
                    volume < 50.0 -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (muted) "Unmute (M)" else "Mute (M)",
                tint = Color.White,
            )
        }
        PlayerTrack(
            value = if (muted) 0f else volume.toFloat(),
            valueRange = 0f..control.max.toFloat(),
            onValueChange = { control.setVolume(it.roundToInt().toDouble()); onInteract() },
            modifier = Modifier.width(120.dp).testTag("volume_slider"),
        )
        Text(
            if (muted) "Muted" else "${volume.roundToInt()}%",
            color = AppColors.Text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp).testTag("volume_label"),
        )
    }
}
