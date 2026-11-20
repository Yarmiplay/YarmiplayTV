package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.OnVideo
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** What the player bar's volume control shows and changes: mpv's volume on desktop, the media volume on Android. */
interface PlayerVolume {
    /** In percent, 0 to [max]. */
    val volume: StateFlow<Double>
    val muted: StateFlow<Boolean>
    val max: Double
    /** Also unmutes. */
    fun setVolume(percent: Double)
    fun setMuted(muted: Boolean)
}

/** The slider grows up to this and no further; leftover space stays after the percent label. */
internal val VolumeSliderMax = 120.dp

private enum class VolumePart { Mute, Slider, Label }

/**
 * Width of the volume slider, in px. The mute button and the percent label keep their natural widths, and the
 * slider uses what's left up to [sliderMax], so "0%"…"130%" (and "Muted") stay on one line instead of the track
 * taking the row and wrapping the percent one character per line.
 */
internal fun volumeSliderWidth(maxWidth: Int, muteWidth: Int, labelWidth: Int, sliderMax: Int): Int {
    val room = (maxWidth - muteWidth - labelWidth).coerceAtLeast(0)
    return room.coerceAtMost(sliderMax.coerceAtLeast(0))
}

/**
 * Mute button, the TV bar's thin track as the volume slider, and the volume in percent. [muteShortcut] is the key
 * named in the mute button's description, where there is one.
 *
 * The percent is measured at its natural width before the slider, so a narrow portrait row (mute, slider, percent,
 * save, screenshot, menu) cannot squeeze "13%" into a vertical stack. The slider then uses the space that is left,
 * up to [VolumeSliderMax].
 */
@Composable
internal fun PlayerVolumeBar(
    control: PlayerVolume,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    muteShortcut: String? = null,
    onVideo: Boolean = true,
) {
    val volume by control.volume.collectAsState()
    val muted by control.muted.collectAsState()
    val shortcut = muteShortcut?.let { " ($it)" }.orEmpty()
    val label = if (muted) "Muted" else "${volume.roundToInt()}%"
    Layout(
        content = {
            IconButton(
                { control.setMuted(!muted); onInteract() },
                Modifier.layoutId(VolumePart.Mute).testTag("volume_mute"),
            ) {
                Icon(
                    when {
                        muted || volume <= 0.0 -> Icons.AutoMirrored.Filled.VolumeOff
                        volume < 50.0 -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = if (muted) "Unmute$shortcut" else "Mute$shortcut",
                    tint = if (onVideo) Color.White else AppColors.Text,
                )
            }
            PlayerTrack(
                value = if (muted) 0f else volume.toFloat(),
                valueRange = 0f..control.max.toFloat(),
                onValueChange = { control.setVolume(it.roundToInt().toDouble()); onInteract() },
                onVideo = onVideo,
                modifier = Modifier.layoutId(VolumePart.Slider).testTag("volume_slider"),
            )
            Text(
                label,
                color = if (onVideo) OnVideo.Text else AppColors.TextDim,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.layoutId(VolumePart.Label).padding(start = 4.dp).testTag("volume_label"),
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val parts = measurables.associateBy { it.layoutId as VolumePart }
        val heightMax = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        // Finite and wider than "Muted" or "130%" at a large font scale. An infinite max is illegal for some
        // children, and the parent's max is what used to wrap the percent one character per line.
        val natural = Constraints(minWidth = 0, maxWidth = 1000.dp.roundToPx(), minHeight = 0, maxHeight = heightMax)
        val mute = parts.getValue(VolumePart.Mute).measure(natural)
        val labelPlaceable = parts.getValue(VolumePart.Label).measure(natural)
        val sliderMax = VolumeSliderMax.roundToPx()
        val sliderWidth = if (constraints.hasBoundedWidth) {
            volumeSliderWidth(constraints.maxWidth, mute.width, labelPlaceable.width, sliderMax)
        } else {
            sliderMax
        }
        val slider = parts.getValue(VolumePart.Slider).measure(
            Constraints(minWidth = sliderWidth, maxWidth = sliderWidth, minHeight = 0, maxHeight = heightMax),
        )
        val contentWidth = mute.width + slider.width + labelPlaceable.width
        val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(mute.height, slider.height, labelPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            mute.placeRelative(0, (height - mute.height) / 2)
            slider.placeRelative(mute.width, (height - slider.height) / 2)
            labelPlaceable.placeRelative(mute.width + slider.width, (height - labelPlaceable.height) / 2)
        }
    }
}
