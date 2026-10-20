@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.theme.AppColors

/** Subtitle delay, position, visibility, and a second subtitle. Size stays in [SubtitleSizeControls]. */
@Composable
fun SubtitleAdjustments(container: AppContainer, modifier: Modifier = Modifier) {
    val tracks by container.player.tracks.collectAsStateWithLifecycle()
    val subs = tracks.filter { it.type == TrackType.SUBTITLE }
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        AdjustmentRow {
            Adjust("Earlier") { container.playback.perform(PlaybackAction.Adjust("sub-delay", -0.1, "Subtitle delay")) }
            Adjust("Later") { container.playback.perform(PlaybackAction.Adjust("sub-delay", 0.1, "Subtitle delay")) }
            Adjust("Up") { container.playback.perform(PlaybackAction.Adjust("sub-pos", -1.0, "Subtitle position")) }
            Adjust("Down") { container.playback.perform(PlaybackAction.Adjust("sub-pos", 1.0, "Subtitle position")) }
        }
        AdjustmentRow {
            Adjust("Hide or show") { container.playback.perform(PlaybackAction.CycleProperty("sub-visibility", label = "Subtitles")) }
            Adjust("Forced only") { container.playback.perform(PlaybackAction.CycleProperty("sub-forced-events-only", label = "Forced subtitles")) }
        }
        Text("Second subtitle", style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
        AdjustmentRow {
            Adjust("Off") { container.player.command("set", "secondary-sid", "no"); container.player.showText("Second subtitle off") }
            subs.forEach { track ->
                Adjust(track.label) {
                    container.player.command("set", "secondary-sid", track.id.toString())
                    container.player.showText("Second subtitle: ${track.label}")
                }
            }
        }
    }
}

@Composable
fun AudioAdjustments(container: AppContainer, modifier: Modifier = Modifier) {
    AdjustmentRow(modifier.padding(bottom = 8.dp)) {
        Adjust("Audio earlier") { container.playback.perform(PlaybackAction.AudioDelay(-0.1)) }
        Adjust("Audio later") { container.playback.perform(PlaybackAction.AudioDelay(0.1)) }
    }
}

/** Picture, speed, looping, chapters, and the full reset. The same actions as the keyboard. */
@Composable
fun VideoAdjustments(container: AppContainer, modifier: Modifier = Modifier) {
    val state by container.player.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        AdjustmentRow {
            Adjust("Slower") { container.playback.perform(PlaybackAction.SpeedMultiply(1.0 / 1.1)) }
            Adjust("Speed 1×") { container.playback.perform(PlaybackAction.SpeedReset) }
            Adjust("Faster") { container.playback.perform(PlaybackAction.SpeedMultiply(1.1)) }
        }
        AdjustmentRow {
            Adjust("A-B loop") { container.playback.perform(PlaybackAction.AbLoop) }
            Adjust("Loop file") { container.playback.perform(PlaybackAction.LoopFile) }
            Adjust("Aspect") {
                container.playback.perform(PlaybackAction.CycleValues("video-aspect-override", listOf("16:9", "4:3", "2.35:1", "no"), "Aspect"))
            }
            Adjust("Reset zoom") { container.playback.perform(PlaybackAction.ResetGeometry) }
        }
        PictureSlider(container, "Contrast", "contrast")
        PictureSlider(container, "Brightness", "brightness")
        PictureSlider(container, "Gamma", "gamma")
        PictureSlider(container, "Saturation", "saturation")
        AdjustmentRow {
            Adjust("Deband") { container.playback.perform(PlaybackAction.CycleProperty("deband", label = "Deband")) }
            Adjust("Deinterlace") { container.playback.perform(PlaybackAction.CycleProperty("deinterlace", label = "Deinterlace")) }
            Adjust("Reset") { container.playback.perform(PlaybackAction.ResetAll) }
        }
        if (state.chapters.isNotEmpty()) {
            Text("Chapters", style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, modifier = Modifier.padding(top = 8.dp))
            state.chapters.forEach { chapter ->
                Adjust(chapterLabel(chapter), modifier = Modifier.fillMaxWidth()) { container.player.seek(chapter.time) }
            }
        }
    }
}

@Composable
private fun PictureSlider(container: AppContainer, label: String, property: String) {
    AdjustmentRow {
        Adjust("$label −") { container.playback.perform(PlaybackAction.Adjust(property, -1.0, label)) }
        Adjust("$label +") { container.playback.perform(PlaybackAction.Adjust(property, 1.0, label)) }
    }
}

@Composable
private fun AdjustmentRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun Adjust(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, maxLines = 1)
    }
}

private fun chapterLabel(chapter: Chapter): String {
    val name = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter"
    return "$name  ${formatClock(chapter.time)}"
}
