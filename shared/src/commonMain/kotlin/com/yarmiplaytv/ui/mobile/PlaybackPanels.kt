@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * TV player menus draw these rows for a remote: the app's colours, a visible focus, and D-pad order.
 * Phone, tablet and desktop leave this off and keep the material controls.
 */
val LocalTvPlaybackControls = staticCompositionLocalOf { false }

/** Subtitle delay and position, in the same row as [SubtitleSizeControls]. */
@Composable
fun SubtitleTimingControls(container: AppContainer, modifier: Modifier = Modifier) {
    val delay = rememberPlayerValue(container.player.propertyDouble("sub-delay") ?: 0.0) { container.player.propertyDouble("sub-delay") ?: 0.0 }
    val position = rememberPlayerValue(container.player.propertyDouble("sub-pos") ?: 100.0) { container.player.propertyDouble("sub-pos") ?: 100.0 }
    Column(modifier.fillMaxWidth()) {
        StepperRow(
            title = "Timing ${signedMillis(delay.value)}",
            hint = "z earlier, Z later",
            onDecrease = { container.playback.perform(PlaybackAction.Adjust("sub-delay", -0.1, "Subtitle delay")); delay.refresh() },
            onIncrease = { container.playback.perform(PlaybackAction.Adjust("sub-delay", 0.1, "Subtitle delay")); delay.refresh() },
            onReset = {
                container.player.command("set", "sub-delay", "0")
                container.player.showText("Subtitle delay: 0 ms")
                delay.refresh()
            },
            decreaseDescription = "Earlier subtitles",
            increaseDescription = "Later subtitles",
            resetDescription = "Reset subtitle timing",
        )
        StepperRow(
            title = "Placement ${position.value.roundToInt()}",
            hint = "r up, t down",
            onDecrease = { container.playback.perform(PlaybackAction.Adjust("sub-pos", -1.0, "Subtitle position")); position.refresh() },
            onIncrease = { container.playback.perform(PlaybackAction.Adjust("sub-pos", 1.0, "Subtitle position")); position.refresh() },
            onReset = {
                container.player.command("set", "sub-pos", "100")
                container.player.showText("Subtitle position: 100")
                position.refresh()
            },
            decreaseDescription = "Move subtitles up",
            increaseDescription = "Move subtitles down",
            resetDescription = "Reset subtitle placement",
        )
    }
}

/** Subtitle visibility and a second subtitle. Size and timing stay in their own rows. */
@Composable
fun SubtitleAdjustments(container: AppContainer, modifier: Modifier = Modifier) {
    val tracks by container.player.tracks.collectAsStateWithLifecycle()
    val subs = tracks.filter { it.type == TrackType.SUBTITLE }
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
fun AudioAdjustments(container: AppContainer, modifier: Modifier = Modifier, initialFocus: FocusRequester? = null) {
    val delay = rememberPlayerValue(container.player.userAudioDelay()) { container.player.userAudioDelay() }
    StepperRow(
        title = "Timing ${signedMillis(delay.value)}",
        hint = "Ctrl+− earlier, Ctrl++ later",
        onDecrease = { container.playback.perform(PlaybackAction.AudioDelay(-0.1)); delay.refresh() },
        onIncrease = { container.playback.perform(PlaybackAction.AudioDelay(0.1)); delay.refresh() },
        onReset = {
            container.player.setUserAudioDelay(0.0)
            container.player.showText("Audio delay: 0 ms")
            delay.refresh()
        },
        decreaseDescription = "Earlier audio",
        increaseDescription = "Later audio",
        resetDescription = "Reset audio timing",
        modifier = modifier.padding(bottom = 8.dp),
        initialFocus = initialFocus,
    )
}

/** The size row's layout: the current value, the keys, then reset, smaller, and larger. */
@Composable
internal fun StepperRow(
    title: String,
    hint: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onReset: () -> Unit,
    decreaseDescription: String,
    increaseDescription: String,
    resetDescription: String,
    modifier: Modifier = Modifier,
    decreaseTag: String? = null,
    increaseTag: String? = null,
    initialFocus: FocusRequester? = null,
) {
    AdjustRow(modifier, initialFocus) { enter ->
        AdjustmentLabel(title, hint, Modifier.weight(1f))
        AdjustIcon(Icons.Filled.RestartAlt, resetDescription, onReset, enter)
        AdjustIcon(Icons.Filled.Remove, decreaseDescription, onDecrease, tag = decreaseTag)
        AdjustIcon(Icons.Filled.Add, increaseDescription, onIncrease, tag = increaseTag)
    }
}

@Composable
private fun AdjustmentLabel(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = AppColors.Text)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
    }
}

/** On or off, with the same reset as [StepperRow]. */
@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    resetDescription: String,
) {
    AdjustRow { enter ->
        AdjustmentLabel(title, hint, Modifier.weight(1f))
        AdjustIcon(Icons.Filled.RestartAlt, resetDescription, onReset, enter)
        if (LocalTvPlaybackControls.current) {
            TvToggle(checked, onCheckedChange)
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** One step through a list of choices, with a reset back to the default. */
@Composable
private fun CycleRow(
    title: String,
    hint: String,
    onCycle: () -> Unit,
    onReset: () -> Unit,
    cycleDescription: String,
    resetDescription: String,
) {
    AdjustRow { enter ->
        AdjustmentLabel(title, hint, Modifier.weight(1f))
        AdjustIcon(Icons.Filled.RestartAlt, resetDescription, onReset, enter)
        AdjustIcon(Icons.Filled.Autorenew, cycleDescription, onCycle)
    }
}

/**
 * One adjustment on its own line. On TV, entering the row from above or below lands on [initialFocus]
 * (or the row's first control), so Down walks the labels instead of skipping across the buttons.
 */
@Composable
private fun AdjustRow(
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
    content: @Composable RowScope.(FocusRequester?) -> Unit,
) {
    val tv = LocalTvPlaybackControls.current
    val created = remember { FocusRequester() }
    val enter = initialFocus ?: if (tv) created else null
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (enter != null) {
                    // Up or Down into the row lands on its first control. Left and Right still move between the buttons.
                    Modifier.focusProperties {
                        onEnter = {
                            if (requestedFocusDirection == FocusDirection.Up ||
                                requestedFocusDirection == FocusDirection.Down ||
                                requestedFocusDirection == FocusDirection.Enter
                            ) {
                                enter.requestFocus()
                            }
                        }
                    }.focusGroup()
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) { content.invoke(this, enter) }
}

/** [read] again after each change, and while the row is open so a key press shows too. */
@Composable
private fun <T> rememberPlayerValue(initial: T, read: () -> T): PlayerValue<T> {
    var value by remember { mutableStateOf(initial) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { value = read() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            value = read()
        }
    }
    return PlayerValue(value) { tick++ }
}

private class PlayerValue<T>(val value: T, val refresh: () -> Unit)

private fun signedMillis(seconds: Double): String {
    val ms = (seconds * 1000.0).roundToInt()
    return if (ms > 0) "+$ms ms" else "$ms ms"
}

/** Picture, speed, looping, and chapters. Each adjustment shows its value and resets on its own. */
@Composable
fun VideoAdjustments(container: AppContainer, modifier: Modifier = Modifier, initialFocus: FocusRequester? = null) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val zoom = rememberPlayerValue(container.player.propertyDouble("video-zoom") ?: 0.0) { container.player.propertyDouble("video-zoom") ?: 0.0 }
    val contrast = rememberPlayerValue(container.player.propertyDouble("contrast") ?: 0.0) { container.player.propertyDouble("contrast") ?: 0.0 }
    val brightness = rememberPlayerValue(container.player.propertyDouble("brightness") ?: 0.0) { container.player.propertyDouble("brightness") ?: 0.0 }
    val gamma = rememberPlayerValue(container.player.propertyDouble("gamma") ?: 0.0) { container.player.propertyDouble("gamma") ?: 0.0 }
    val saturation = rememberPlayerValue(container.player.propertyDouble("saturation") ?: 0.0) { container.player.propertyDouble("saturation") ?: 0.0 }
    val aspect = rememberPlayerValue(container.player.propertyString("video-aspect-override") ?: "no") { container.player.propertyString("video-aspect-override") ?: "no" }
    val loopA = rememberPlayerValue(container.player.propertyString("ab-loop-a") ?: "no") { container.player.propertyString("ab-loop-a") ?: "no" }
    val loopB = rememberPlayerValue(container.player.propertyString("ab-loop-b") ?: "no") { container.player.propertyString("ab-loop-b") ?: "no" }
    val loopFile = rememberPlayerValue(container.player.propertyString("loop-file") ?: "no") { container.player.propertyString("loop-file") ?: "no" }
    val deband = rememberPlayerValue(container.player.propertyString("deband") ?: "no") { container.player.propertyString("deband") ?: "no" }
    val deinterlace = rememberPlayerValue(container.player.propertyString("deinterlace") ?: "no") { container.player.propertyString("deinterlace") ?: "no" }
    Column(modifier.fillMaxWidth()) {
        StepperRow(
            title = "Speed ${"%.2f".format(state.speed)}×",
            hint = "[ slower, ] faster",
            onDecrease = { container.playback.perform(PlaybackAction.SpeedMultiply(1.0 / 1.1)) },
            onIncrease = { container.playback.perform(PlaybackAction.SpeedMultiply(1.1)) },
            onReset = { container.playback.perform(PlaybackAction.SpeedReset) },
            decreaseDescription = "Slower",
            increaseDescription = "Faster",
            resetDescription = "Reset speed",
            initialFocus = initialFocus,
        )
        StepperRow(
            title = "Zoom ${formatStep(zoom.value)}",
            hint = "Alt+− out, Alt++ in",
            onDecrease = { container.playback.perform(PlaybackAction.Adjust("video-zoom", -0.1, "Zoom")); zoom.refresh() },
            onIncrease = { container.playback.perform(PlaybackAction.Adjust("video-zoom", 0.1, "Zoom")); zoom.refresh() },
            onReset = { container.playback.perform(PlaybackAction.ResetGeometry); zoom.refresh() },
            decreaseDescription = "Zoom out",
            increaseDescription = "Zoom in",
            resetDescription = "Reset zoom",
        )
        PictureRow(container, "Contrast", "contrast", contrast, "1 lower, 2 higher")
        PictureRow(container, "Brightness", "brightness", brightness, "3 lower, 4 higher")
        PictureRow(container, "Gamma", "gamma", gamma, "5 lower, 6 higher")
        PictureRow(container, "Saturation", "saturation", saturation, "7 lower, 8 higher")
        CycleRow(
            title = "Aspect ${aspectLabel(aspect.value)}",
            hint = "Shift+A",
            onCycle = {
                container.playback.perform(PlaybackAction.CycleValues("video-aspect-override", listOf("16:9", "4:3", "2.35:1", "no"), "Aspect"))
                aspect.refresh()
            },
            onReset = {
                container.player.command("set", "video-aspect-override", "no")
                container.player.showText("Aspect: no")
                aspect.refresh()
            },
            cycleDescription = "Next aspect",
            resetDescription = "Reset aspect",
        )
        CycleRow(
            title = "A-B loop ${abLabel(loopA.value, loopB.value)}",
            hint = "l",
            onCycle = {
                container.playback.perform(PlaybackAction.AbLoop)
                loopA.refresh()
                loopB.refresh()
            },
            onReset = {
                container.player.command("set", "ab-loop-a", "no")
                container.player.command("set", "ab-loop-b", "no")
                container.player.showText("A-B loop")
                loopA.refresh()
                loopB.refresh()
            },
            cycleDescription = "Set A-B loop",
            resetDescription = "Clear A-B loop",
        )
        SwitchRow(
            title = "Loop ${if (fileLoops(loopFile.value)) "on" else "off"}",
            hint = "L",
            checked = fileLoops(loopFile.value),
            onCheckedChange = { container.playback.perform(PlaybackAction.LoopFile); loopFile.refresh() },
            onReset = {
                container.player.command("set", "loop-file", "no")
                container.player.showText("Loop no")
                loopFile.refresh()
            },
            resetDescription = "Turn off file loop",
        )
        SwitchRow(
            title = "Deband ${if (isOn(deband.value)) "on" else "off"}",
            hint = "b",
            checked = isOn(deband.value),
            onCheckedChange = { on -> setFlag(container, "deband", "Deband", on); deband.refresh() },
            onReset = { setFlag(container, "deband", "Deband", false); deband.refresh() },
            resetDescription = "Turn off deband",
        )
        SwitchRow(
            title = "Deinterlace ${if (isOn(deinterlace.value)) "on" else "off"}",
            hint = "d",
            checked = isOn(deinterlace.value),
            onCheckedChange = { on -> setFlag(container, "deinterlace", "Deinterlace", on); deinterlace.refresh() },
            onReset = { setFlag(container, "deinterlace", "Deinterlace", false); deinterlace.refresh() },
            resetDescription = "Turn off deinterlace",
        )
        AdjustRow {
            AdjustmentLabel("Reset all", "Ctrl+Backspace", Modifier.weight(1f))
            AdjustIcon(Icons.Filled.RestartAlt, "Reset everything", { container.playback.perform(PlaybackAction.ResetAll) }, it)
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
private fun PictureRow(container: AppContainer, label: String, property: String, value: PlayerValue<Double>, hint: String) {
    StepperRow(
        title = "$label ${value.value.roundToInt()}",
        hint = hint,
        onDecrease = { container.playback.perform(PlaybackAction.Adjust(property, -1.0, label)); value.refresh() },
        onIncrease = { container.playback.perform(PlaybackAction.Adjust(property, 1.0, label)); value.refresh() },
        onReset = {
            container.player.command("set", property, "0")
            container.player.showText("$label: 0")
            value.refresh()
        },
        decreaseDescription = "Decrease $label",
        increaseDescription = "Increase $label",
        resetDescription = "Reset $label",
    )
}

private fun setFlag(container: AppContainer, property: String, label: String, on: Boolean) {
    val value = if (on) "yes" else "no"
    container.player.command("set", property, value)
    container.player.showText("$label: $value")
}

private fun aspectLabel(raw: String): String = when (raw) {
    "", "no", "-1" -> "original"
    else -> raw
}

private fun abLabel(a: String, b: String): String = when {
    !pointSet(a) -> "off"
    !pointSet(b) -> "A set"
    else -> "A–B"
}

private fun pointSet(raw: String) = raw.isNotBlank() && raw != "no"

private fun fileLoops(raw: String) = raw == "inf" || raw == "yes" || raw.toIntOrNull()?.let { it > 0 } == true

private fun isOn(raw: String) = raw == "yes" || raw == "true"

private fun formatStep(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    val whole = tenths / 10
    val frac = kotlin.math.abs(tenths % 10)
    if (frac == 0) return whole.toString()
    val sign = if (tenths < 0 && whole == 0) "-" else ""
    return "$sign$whole.$frac"
}

@Composable
private fun AdjustmentRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun Adjust(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (LocalTvPlaybackControls.current) {
        TvTextControl(label, onClick, modifier)
    } else {
        TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun AdjustIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    focus: FocusRequester? = null,
    tag: String? = null,
) {
    var modifier: Modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier
    if (tag != null) modifier = modifier.testTag(tag)
    if (LocalTvPlaybackControls.current) {
        TvFocusIcon(icon, description, onClick, modifier)
    } else {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = description, tint = AppColors.Text)
        }
    }
}

/** A remote button: theme-coloured icon, and the accent fill while it has the focus, like the rest of the TV controls. */
@Composable
private fun TvFocusIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) AppColors.Accent else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (focused) AppColors.OnAccent else AppColors.Text,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** A labelled remote button: surface tile, theme text, accent border while focused. */
@Composable
private fun TvTextControl(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(AppColors.SurfaceHigh)
            .border(3.dp, if (focused) AppColors.Accent else Color.Transparent, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = AppColors.Text, maxLines = 1)
    }
}

/** On or off, as its own focused control. Checked is the accent wash; focused is the solid accent. */
@Composable
private fun TvToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val label = if (checked) "On" else "Off"
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(when {
                focused -> AppColors.Accent
                checked -> AppColors.Accent.copy(alpha = 0.28f)
                else -> AppColors.SurfaceHigh
            })
            .border(3.dp, if (focused) AppColors.Accent else Color.Transparent, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (focused) AppColors.OnAccent else AppColors.Text, maxLines = 1)
    }
}

private fun chapterLabel(chapter: Chapter): String {
    val name = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter"
    return "$name  ${formatClock(chapter.time)}"
}
