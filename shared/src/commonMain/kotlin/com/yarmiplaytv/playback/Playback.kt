package com.yarmiplaytv.playback

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.SubtitleScale
import com.yarmiplaytv.ui.player.formatClock
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One playback key action. [showsControls] opens the player bar (pause, playlist, volume, …). Seek steps do not:
 * on desktop the arrow keys seek without the full HUD (mpv-style OSD / seek flash is enough). [repeats] runs again
 * while the key is held; toggles do not.
 */
sealed interface PlaybackAction {
    val showsControls: Boolean get() = false
    val repeats: Boolean get() = false

    data class SeekBy(val seconds: Double) : PlaybackAction {
        override val repeats = true
    }

    data object SeekStart : PlaybackAction {
        override val showsControls = true
    }

    data class Chapter(val forward: Boolean) : PlaybackAction {
        override val showsControls = true
    }

    data class Playlist(val forward: Boolean) : PlaybackAction {
        override val showsControls = true
    }

    data object TogglePause : PlaybackAction {
        override val showsControls = true
    }

    data object Play : PlaybackAction {
        override val showsControls = true
    }

    data object Pause : PlaybackAction {
        override val showsControls = true
    }

    data class SpeedMultiply(val factor: Double) : PlaybackAction {
        override val repeats = true
    }

    data object SpeedReset : PlaybackAction

    data object UndoSeek : PlaybackAction {
        override val showsControls = true
    }

    data object MarkSeek : PlaybackAction

    data object ResetAll : PlaybackAction

    data class FrameStep(val forward: Boolean) : PlaybackAction

    data object AbLoop : PlaybackAction
    data object LoopFile : PlaybackAction
    data object Reload : PlaybackAction
    data object HwdecToggle : PlaybackAction
    data object CycleEdition : PlaybackAction
    data object ShowPlaylist : PlaybackAction
    data object ShowTracks : PlaybackAction
    data object ShowProgress : PlaybackAction
    data object OsdLevel : PlaybackAction

    data class SubtitleScaleBy(val delta: Double) : PlaybackAction
    data class SubSeek(val direction: Int) : PlaybackAction
    data class SubStep(val direction: Int) : PlaybackAction
    data class AudioDelay(val delta: Double) : PlaybackAction {
        override val repeats = true
    }

    data class Adjust(val property: String, val delta: Double, val label: String) : PlaybackAction {
        override val repeats = true
    }

    data class SetProperty(val property: String, val value: String, val label: String) : PlaybackAction
    data class AdjustPair(val xProperty: String, val x: Double, val yProperty: String, val y: Double, val label: String) : PlaybackAction {
        override val repeats = true
    }

    data class CycleProperty(val property: String, val down: Boolean = false, val label: String) : PlaybackAction
    data class CycleValues(val property: String, val values: List<String>, val label: String) : PlaybackAction

    data object ResetGeometry : PlaybackAction
    data object ResetPan : PlaybackAction
    data object ResetAlign : PlaybackAction
    data object ResetScale : PlaybackAction
    data object ResetRotate : PlaybackAction

    data class Volume(val delta: Double) : PlaybackAction {
        override val showsControls = true
        override val repeats = true
    }

    data object Mute : PlaybackAction {
        override val showsControls = true
    }

    /**
     * Room voice mic mute/unmute (Ctrl+M). Not video volume mute ([Mute] / plain M).
     * Handled by the desktop/web shortcut layer; [PlaybackController] does not apply it.
     */
    data object ToggleMic : PlaybackAction {
        override val showsControls = true
    }

    data object Fullscreen : PlaybackAction {
        override val showsControls = true
    }

    data class Screenshot(val subtitles: Boolean) : PlaybackAction
    data object ScreenshotEachFrame : PlaybackAction
    data object ToggleControls : PlaybackAction
    data object SkipSegment : PlaybackAction
    data object FocusChat : PlaybackAction {
        override val showsControls = true
    }

    data object TogglePip : PlaybackAction
}

/** Where a chapter key goes. [Seek] is omitted when it would not move, so a room is not told about it. */
sealed interface ChapterStep {
    data class Seek(val position: Double) : ChapterStep
    data object NextFile : ChapterStep
    data object PreviousFile : ChapterStep
    data object Stay : ChapterStep
}

/** mpv's `--chapter-seek-threshold`: backward seeks within this many seconds go to the previous chapter. */
const val CHAPTER_SEEK_THRESHOLD = 5.0

private const val SAME_POSITION = 0.05

/**
 * Page Up / Page Down. With no chapter to enter, the key moves through the playlist. Page Down goes to the previous
 * chapter only within [threshold] seconds of the current chapter's start; otherwise it returns to that start.
 */
fun chapterStep(
    forward: Boolean,
    position: Double,
    chapters: List<Chapter>,
    threshold: Double = CHAPTER_SEEK_THRESHOLD,
): ChapterStep {
    if (chapters.isEmpty()) return if (forward) ChapterStep.NextFile else ChapterStep.PreviousFile
    val beforeFirst = position + SAME_POSITION < chapters.first().time
    if (forward) {
        val next = when {
            beforeFirst -> chapters.first()
            else -> chapters.firstOrNull { it.time > position + SAME_POSITION }
        }
        return if (next == null) ChapterStep.NextFile else seekOrStay(position, next.time)
    }
    if (beforeFirst) return ChapterStep.PreviousFile
    val index = chapters.indexOfLast { it.time <= position + SAME_POSITION }.coerceAtLeast(0)
    val start = chapters[index].time
    if (position - start > threshold) return seekOrStay(position, start)
    if (index > 0) return seekOrStay(position, chapters[index - 1].time)
    return ChapterStep.PreviousFile
}

private fun seekOrStay(position: Double, target: Double): ChapterStep =
    if (abs(position - target) < SAME_POSITION) ChapterStep.Stay else ChapterStep.Seek(target)

/** mpv's default key, plus the keys this app keeps for its own actions. Null when nothing is bound. */
fun playbackAction(key: Key, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false): PlaybackAction? {
    if (ctrl && alt) return null
    if (alt) return altAction(key, shift)
    if (ctrl) return ctrlAction(key, shift)
    return plainAction(key, shift)
}

private fun plainAction(key: Key, shift: Boolean): PlaybackAction? = when (key) {
    Key.Spacebar, Key.K -> PlaybackAction.TogglePause
    Key.P -> if (shift) PlaybackAction.ShowProgress else PlaybackAction.TogglePause
    Key.DirectionLeft -> PlaybackAction.SeekBy(if (shift) -1.0 else -5.0)
    Key.DirectionRight -> PlaybackAction.SeekBy(if (shift) 1.0 else 5.0)
    Key.DirectionUp -> PlaybackAction.SeekBy(if (shift) 5.0 else 60.0)
    Key.DirectionDown -> PlaybackAction.SeekBy(if (shift) -5.0 else -60.0)
    Key.MoveHome -> PlaybackAction.SeekStart
    Key.PageUp -> if (shift) PlaybackAction.SeekBy(600.0) else PlaybackAction.Chapter(true)
    Key.PageDown -> if (shift) PlaybackAction.SeekBy(-600.0) else PlaybackAction.Chapter(false)
    Key.MediaFastForward -> PlaybackAction.SeekBy(60.0)
    Key.MediaRewind -> PlaybackAction.SeekBy(-60.0)
    Key.Period -> if (shift) PlaybackAction.Playlist(true) else PlaybackAction.FrameStep(true)
    Key.Comma -> if (shift) PlaybackAction.Playlist(false) else PlaybackAction.FrameStep(false)
    Key.Backspace -> if (shift) PlaybackAction.UndoSeek else PlaybackAction.SpeedReset
    Key.LeftBracket -> PlaybackAction.SpeedMultiply(if (shift) 0.5 else 1.0 / 1.1)
    Key.RightBracket -> PlaybackAction.SpeedMultiply(if (shift) 2.0 else 1.1)
    Key.L -> if (shift) PlaybackAction.LoopFile else PlaybackAction.AbLoop
    Key.F8 -> PlaybackAction.ShowPlaylist
    Key.F9 -> PlaybackAction.ShowTracks
    Key.O -> if (shift) PlaybackAction.OsdLevel else PlaybackAction.ShowProgress
    Key.Enter, Key.NumPadEnter, Key.N, Key.MediaNext -> if (shift && key == Key.N) PlaybackAction.Playlist(false) else PlaybackAction.Playlist(true)
    Key.MediaPrevious -> PlaybackAction.Playlist(false)
    Key.Z -> PlaybackAction.Adjust("sub-delay", if (shift) 0.1 else -0.1, "Subtitle delay")
    Key.X -> PlaybackAction.Adjust("sub-delay", 0.1, "Subtitle delay")
    Key.G -> PlaybackAction.SubtitleScaleBy(SubtitleScale.STEP)
    Key.F -> if (shift) PlaybackAction.SubtitleScaleBy(-SubtitleScale.STEP) else PlaybackAction.Fullscreen
    Key.F11 -> PlaybackAction.Fullscreen
    Key.R -> PlaybackAction.Adjust("sub-pos", if (shift) 1.0 else -1.0, "Subtitle position")
    Key.T -> PlaybackAction.Adjust("sub-pos", 1.0, "Subtitle position")
    Key.V -> if (shift) PlaybackAction.CycleProperty("sub-ass-use-video-data", label = "ASS video data") else PlaybackAction.CycleProperty("sub-visibility", label = "Subtitles")
    Key.U -> PlaybackAction.CycleValues("sub-ass-override", listOf("force", "scale"), "ASS override")
    Key.J -> PlaybackAction.CycleProperty("sid", down = shift, label = "Subtitles")
    Key.Three -> if (shift) PlaybackAction.CycleProperty("aid", label = "Audio") else PlaybackAction.Adjust("brightness", -1.0, "Brightness")
    Key.Minus -> if (shift) PlaybackAction.CycleProperty("vid", label = "Video") else null
    Key.One -> PlaybackAction.Adjust("contrast", -1.0, "Contrast")
    Key.Two -> PlaybackAction.Adjust("contrast", 1.0, "Contrast")
    Key.Four -> PlaybackAction.Adjust("brightness", 1.0, "Brightness")
    Key.Five -> PlaybackAction.Adjust("gamma", -1.0, "Gamma")
    Key.Six -> PlaybackAction.Adjust("gamma", 1.0, "Gamma")
    Key.Seven -> PlaybackAction.Adjust("saturation", -1.0, "Saturation")
    Key.Eight -> if (shift) PlaybackAction.Volume(2.0) else PlaybackAction.Adjust("saturation", 1.0, "Saturation")
    Key.Nine -> PlaybackAction.Volume(-2.0)
    Key.Zero -> PlaybackAction.Volume(2.0)
    Key.Slash, Key.NumPadDivide -> PlaybackAction.Volume(-2.0)
    Key.NumPadMultiply -> PlaybackAction.Volume(2.0)
    Key.W -> PlaybackAction.Adjust("panscan", if (shift) 0.1 else -0.1, "Panscan")
    Key.E -> if (shift) PlaybackAction.CycleEdition else PlaybackAction.Adjust("panscan", 0.1, "Panscan")
    Key.A -> if (shift) PlaybackAction.CycleValues("video-aspect-override", listOf("16:9", "4:3", "2.35:1", "no"), "Aspect") else null
    Key.B -> PlaybackAction.CycleProperty("deband", label = "Deband")
    Key.D -> PlaybackAction.CycleProperty("deinterlace", label = "Deinterlace")
    Key.S -> PlaybackAction.Screenshot(subtitles = !shift)
    Key.F5 -> PlaybackAction.Screenshot(subtitles = true)
    Key.F6 -> PlaybackAction.Screenshot(subtitles = false)
    Key.M -> PlaybackAction.Mute
    Key.H -> PlaybackAction.ToggleControls
    Key.Y -> PlaybackAction.SkipSegment
    Key.C -> PlaybackAction.FocusChat
    Key.F12 -> PlaybackAction.TogglePip
    Key.NumPad1 -> PlaybackAction.Adjust("video-zoom", -0.01, "Zoom")
    Key.NumPad2 -> PlaybackAction.Adjust("video-scale-y", -0.01, "Vertical scale")
    Key.NumPad4 -> PlaybackAction.Adjust("video-scale-x", -0.01, "Horizontal scale")
    Key.NumPad5 -> PlaybackAction.ResetScale
    Key.NumPad6 -> PlaybackAction.Adjust("video-scale-x", 0.01, "Horizontal scale")
    Key.NumPad8 -> PlaybackAction.Adjust("video-scale-y", 0.01, "Vertical scale")
    Key.NumPad9 -> PlaybackAction.Adjust("video-zoom", 0.01, "Zoom")
    else -> null
}

private fun ctrlAction(key: Key, shift: Boolean): PlaybackAction? = when (key) {
    Key.DirectionLeft -> if (shift) PlaybackAction.SubStep(-1) else PlaybackAction.SubSeek(-1)
    Key.DirectionRight -> if (shift) PlaybackAction.SubStep(1) else PlaybackAction.SubSeek(1)
    Key.Backspace -> if (shift) PlaybackAction.MarkSeek else PlaybackAction.ResetAll
    // Ctrl+M = room mic (not video mute). Cmd+M is left alone so macOS can keep window minimize.
    Key.M -> PlaybackAction.ToggleMic
    Key.R -> PlaybackAction.Reload
    Key.H -> PlaybackAction.HwdecToggle
    Key.S -> PlaybackAction.Screenshot(subtitles = true)
    Key.Equals, Key.Plus, Key.NumPadAdd -> PlaybackAction.AudioDelay(0.1)
    Key.Minus, Key.NumPadSubtract -> PlaybackAction.AudioDelay(-0.1)
    Key.NumPad1 -> PlaybackAction.AdjustPair("video-pan-x", -0.01, "video-pan-y", 0.01, "Pan")
    Key.NumPad2 -> PlaybackAction.Adjust("video-pan-y", 0.01, "Pan")
    Key.NumPad3 -> PlaybackAction.AdjustPair("video-pan-x", 0.01, "video-pan-y", 0.01, "Pan")
    Key.NumPad4 -> PlaybackAction.Adjust("video-pan-x", -0.01, "Pan")
    Key.NumPad5 -> PlaybackAction.ResetPan
    Key.NumPad6 -> PlaybackAction.Adjust("video-pan-x", 0.01, "Pan")
    Key.NumPad7 -> PlaybackAction.AdjustPair("video-pan-x", -0.01, "video-pan-y", -0.01, "Pan")
    Key.NumPad8 -> PlaybackAction.Adjust("video-pan-y", -0.01, "Pan")
    Key.NumPad9 -> PlaybackAction.AdjustPair("video-pan-x", 0.01, "video-pan-y", -0.01, "Pan")
    Key.MoveEnd -> PlaybackAction.AdjustPair("video-align-x", -0.01, "video-align-y", 0.01, "Align")
    Key.PageDown -> PlaybackAction.AdjustPair("video-align-x", 0.01, "video-align-y", 0.01, "Align")
    Key.MoveHome -> PlaybackAction.AdjustPair("video-align-x", -0.01, "video-align-y", -0.01, "Align")
    Key.PageUp -> PlaybackAction.AdjustPair("video-align-x", 0.01, "video-align-y", -0.01, "Align")
    else -> null
}

private fun altAction(key: Key, shift: Boolean): PlaybackAction? = when (key) {
    Key.DirectionLeft -> PlaybackAction.Adjust("video-pan-x", 0.1, "Pan")
    Key.DirectionRight -> PlaybackAction.Adjust("video-pan-x", -0.1, "Pan")
    Key.DirectionUp -> PlaybackAction.Adjust("video-pan-y", 0.1, "Pan")
    Key.DirectionDown -> PlaybackAction.Adjust("video-pan-y", -0.1, "Pan")
    Key.Equals, Key.Plus, Key.NumPadAdd -> PlaybackAction.Adjust("video-zoom", 0.1, "Zoom")
    Key.Minus, Key.NumPadSubtract -> PlaybackAction.Adjust("video-zoom", -0.1, "Zoom")
    Key.Backspace -> PlaybackAction.ResetGeometry
    Key.V -> PlaybackAction.CycleProperty("secondary-sub-visibility", label = "Secondary subtitles")
    Key.S -> PlaybackAction.ScreenshotEachFrame
    Key.NumPad1 -> PlaybackAction.Adjust("video-rotate", -1.0, "Rotate")
    Key.NumPad3 -> PlaybackAction.Adjust("video-rotate", 1.0, "Rotate")
    Key.NumPad5 -> PlaybackAction.ResetRotate
    else -> if (shift) null else null
}

/**
 * Applies [PlaybackAction]s to [player]. Speed, A-B loop, and file loop do nothing in a room except [PlaybackAction.ResetAll],
 * which still clears them. Seeks go through [Player.seek] so a room follows them.
 */
class PlaybackController(
    private val player: Player,
    private val inRoom: () -> Boolean,
    private val skip: (forward: Boolean) -> Unit,
    private val adjustSubtitleScale: (Double) -> Unit,
    private val setSubtitleScale: (Double) -> Unit,
    private val playlistText: () -> String,
    private val trackText: () -> String,
    private val screenshot: (Boolean) -> Unit = {},
) {
    private var seekMark: Double? = null

    /** False when the action is refused (a skip that is not offered is handled by the caller). */
    fun perform(action: PlaybackAction): Boolean {
        when (action) {
            is PlaybackAction.SeekBy -> player.seekRelative(action.seconds)
            PlaybackAction.SeekStart -> player.seek(0.0)
            is PlaybackAction.Chapter -> when (val step = chapterStep(action.forward, player.currentPosition(), player.state.value.chapters)) {
                is ChapterStep.Seek -> player.seek(step.position)
                ChapterStep.NextFile -> skip(true)
                ChapterStep.PreviousFile -> skip(false)
                ChapterStep.Stay -> Unit
            }
            is PlaybackAction.Playlist -> skip(action.forward)
            PlaybackAction.TogglePause -> player.setPaused(!player.isPaused)
            PlaybackAction.Play -> player.setPaused(false)
            PlaybackAction.Pause -> player.setPaused(true)
            is PlaybackAction.SpeedMultiply -> {
                if (blockedInRoom("Speed follows the room")) return true
                val next = (player.state.value.speed * action.factor).coerceIn(0.1, 4.0)
                player.setSpeed(next)
                player.showText("Speed ${"%.2f".format(next)}x")
            }
            PlaybackAction.SpeedReset -> {
                if (blockedInRoom("Speed follows the room")) return true
                player.setSpeed(1.0)
                player.showText("Speed 1.00x")
            }
            PlaybackAction.UndoSeek -> {
                val target = seekMark ?: player.seekUndoPosition()
                if (target == null) player.showText("Nothing to undo") else player.seek(target)
            }
            PlaybackAction.MarkSeek -> {
                seekMark = player.currentPosition()
                player.showText("Marked ${formatClock(seekMark!!)}")
            }
            PlaybackAction.ResetAll -> resetAll()
            is PlaybackAction.FrameStep -> {
                player.markUserSeek()
                if (!player.command(if (action.forward) "frame-step" else "frame-back-step")) {
                    player.showText("Can't step a frame")
                }
            }
            PlaybackAction.AbLoop -> {
                if (blockedInRoom("Looping stays off in a room")) return true
                player.command("ab-loop")
                player.showText("A-B loop")
            }
            PlaybackAction.LoopFile -> {
                if (blockedInRoom("Looping stays off in a room")) return true
                player.command("cycle-values", "loop-file", "inf", "no")
                player.showText("Loop ${player.propertyString("loop-file") ?: "file"}")
            }
            PlaybackAction.Reload -> reload()
            PlaybackAction.HwdecToggle -> {
                player.command("cycle-values", "hwdec", "no", "auto")
                player.showText("Hardware decoding: ${player.propertyString("hwdec") ?: "auto"}")
            }
            PlaybackAction.CycleEdition -> {
                player.command("cycle", "edition")
                player.showText("Edition")
            }
            PlaybackAction.ShowPlaylist -> player.showText(playlistText().ifBlank { "Playlist is empty" }, 4000)
            PlaybackAction.ShowTracks -> player.showText(trackText().ifBlank { "No tracks" }, 4000)
            PlaybackAction.ShowProgress -> {
                val state = player.state.value
                val speed = if (state.speed != 1.0) "  ${"%.2f".format(state.speed)}x" else ""
                player.showText("${formatClock(player.currentPosition())} / ${formatClock(state.duration)}$speed")
            }
            PlaybackAction.OsdLevel -> {
                player.command("cycle-values", "osd-level", "3", "1")
                player.showText("OSD ${player.propertyString("osd-level") ?: ""}")
            }
            is PlaybackAction.SubtitleScaleBy -> adjustSubtitleScale(action.delta)
            is PlaybackAction.SubSeek -> {
                player.markUserSeek()
                player.command("sub-seek", action.direction.toString())
            }
            is PlaybackAction.SubStep -> player.command("sub-step", action.direction.toString())
            is PlaybackAction.AudioDelay -> {
                val next = player.userAudioDelay() + action.delta
                player.setUserAudioDelay(next)
                player.showText("Audio delay: ${signedMillis(next)}")
            }
            is PlaybackAction.Adjust -> {
                player.add(action.property, action.delta)
                val value = player.propertyDouble(action.property)
                player.showText(if (value == null) action.label else "${action.label}: ${formatNumber(value)}")
            }
            is PlaybackAction.SetProperty -> {
                player.command("set", action.property, action.value)
                player.showText(action.label)
            }
            is PlaybackAction.AdjustPair -> {
                player.add(action.xProperty, action.x)
                player.add(action.yProperty, action.y)
                player.showText(action.label)
            }
            is PlaybackAction.CycleProperty -> {
                player.cycle(action.property, action.down)
                val value = player.propertyString(action.property)
                player.showText(if (value.isNullOrBlank()) action.label else "${action.label}: $value")
            }
            is PlaybackAction.CycleValues -> {
                player.command("cycle-values", action.property, *action.values.toTypedArray())
                val value = player.propertyString(action.property)
                player.showText(if (value.isNullOrBlank()) action.label else "${action.label}: $value")
            }
            PlaybackAction.ResetGeometry -> {
                geometryIdentity()
                player.showText("Zoom reset")
            }
            PlaybackAction.ResetPan -> {
                player.command("set", "video-pan-x", "0")
                player.command("set", "video-pan-y", "0")
                player.command("set", "video-align-x", "0")
                player.command("set", "video-align-y", "0")
                player.showText("Pan reset")
            }
            PlaybackAction.ResetAlign -> {
                player.command("set", "video-align-x", "0")
                player.command("set", "video-align-y", "0")
                player.showText("Align reset")
            }
            PlaybackAction.ResetScale -> {
                player.command("set", "video-scale-x", "1")
                player.command("set", "video-scale-y", "1")
                player.command("set", "video-zoom", "0")
                player.showText("Scale reset")
            }
            PlaybackAction.ResetRotate -> {
                player.command("set", "video-rotate", "0")
                player.showText("Rotate reset")
            }
            is PlaybackAction.Volume -> platformAdjustVolume(action.delta)
            PlaybackAction.Mute -> platformToggleMute()
            PlaybackAction.Fullscreen -> return false
            is PlaybackAction.Screenshot -> screenshot(action.subtitles)
            PlaybackAction.ScreenshotEachFrame -> {
                if (!player.command("screenshot", "each-frame")) player.showText("Not available")
            }
            PlaybackAction.ToggleControls, PlaybackAction.SkipSegment, PlaybackAction.FocusChat,
            PlaybackAction.TogglePip, PlaybackAction.ToggleMic,
            -> return false
        }
        return true
    }

    /**
     * Ctrl+wheel. [xFraction] and [yFraction] are the pointer in the video, 0 to 1, so the point under it stays put.
     */
    fun zoomAt(delta: Double, xFraction: Double, yFraction: Double) {
        val zoom = player.propertyDouble("video-zoom") ?: 0.0
        val panX = player.propertyDouble("video-pan-x") ?: 0.0
        val panY = player.propertyDouble("video-pan-y") ?: 0.0
        val scale = 2.0.pow(delta)
        val ox = (xFraction - 0.5).coerceIn(-0.5, 0.5)
        val oy = (yFraction - 0.5).coerceIn(-0.5, 0.5)
        player.command("set", "video-zoom", (zoom + delta).toString())
        player.command("set", "video-pan-x", (panX * scale + ox * (scale - 1)).toString())
        player.command("set", "video-pan-y", (panY * scale + oy * (scale - 1)).toString())
        player.showText("Zoom: ${formatNumber(zoom + delta)}")
    }

    private fun resetAll() {
        player.setSpeed(1.0)
        player.command("set", "ab-loop-a", "no")
        player.command("set", "ab-loop-b", "no")
        player.command("set", "loop-file", "no")
        player.command("set", "sub-delay", "0")
        player.command("set", "sub-pos", "100")
        player.command("set", "sub-visibility", "yes")
        player.command("set", "secondary-sub-visibility", "yes")
        player.command("set", "secondary-sid", "no")
        setSubtitleScale(SubtitleScale.DEFAULT)
        player.command("set", "sub-ass-override", "yes")
        player.command("set", "sub-ass-use-video-data", "yes")
        player.command("set", "sub-forced-events-only", "no")
        player.setUserAudioDelay(0.0)
        geometryIdentity()
        player.command("set", "video-scale-x", "1")
        player.command("set", "video-scale-y", "1")
        player.command("set", "video-rotate", "0")
        player.command("set", "video-aspect-override", "no")
        listOf("contrast", "brightness", "gamma", "saturation").forEach { player.command("set", it, "0") }
        player.command("set", "deband", "no")
        player.command("set", "deinterlace", "no")
        player.command("set", "osd-level", "1")
        player.showText("Reset")
    }

    private fun geometryIdentity() {
        player.command("set", "video-zoom", "0")
        player.command("set", "panscan", "0")
        player.command("set", "video-pan-x", "0")
        player.command("set", "video-pan-y", "0")
        player.command("set", "video-align-x", "0")
        player.command("set", "video-align-y", "0")
    }

    private fun reload() {
        val state = player.state.value
        val url = state.url ?: return
        player.showText("Reloading current file...")
        player.load(url, startPaused = player.isPaused, startPosition = player.currentPosition())
    }

    private fun blockedInRoom(message: String): Boolean {
        if (!inRoom()) return false
        player.showText(message)
        return true
    }
}

internal expect fun platformAdjustVolume(delta: Double)
internal expect fun platformToggleMute()

private fun signedMillis(seconds: Double): String {
    val ms = (seconds * 1000).roundToInt()
    return if (ms > 0) "+$ms ms" else "$ms ms"
}

private fun formatNumber(value: Double): String = ((value * 100).roundToInt() / 100.0).toString()
