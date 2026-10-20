package com.yarmiplaytv.playback

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {
    @Test
    fun pageDownReturnsToTheChapterStartAfterFiveSeconds() {
        val chapters = listOf(Chapter("A", 0.0), Chapter("B", 60.0))
        assertEquals(ChapterStep.PreviousFile, chapterStep(forward = false, position = 2.0, chapters = chapters))
        assertEquals(ChapterStep.Seek(0.0), chapterStep(forward = false, position = 20.0, chapters = chapters))
        assertEquals(ChapterStep.Seek(0.0), chapterStep(forward = false, position = 62.0, chapters = chapters))
        assertEquals(ChapterStep.Seek(60.0), chapterStep(forward = false, position = 80.0, chapters = chapters))
    }

    @Test
    fun pageUpGoesToTheNextChapterOrTheNextFile() {
        val chapters = listOf(Chapter("A", 0.0), Chapter("B", 60.0))
        assertEquals(ChapterStep.Seek(60.0), chapterStep(forward = true, position = 10.0, chapters = chapters))
        assertEquals(ChapterStep.NextFile, chapterStep(forward = true, position = 70.0, chapters = chapters))
        assertEquals(ChapterStep.NextFile, chapterStep(forward = true, position = 10.0, chapters = emptyList()))
        assertEquals(ChapterStep.PreviousFile, chapterStep(forward = false, position = 10.0, chapters = emptyList()))
        assertEquals(ChapterStep.NextFile, chapterStep(forward = true, position = 10.0, chapters = listOf(Chapter("Only", 0.0))))
    }

    @Test
    fun aChapterSeekThatDoesNotMoveStaysPut() {
        // A negative threshold always aims at the current chapter's start. Already there, nothing is seeked.
        assertEquals(ChapterStep.Stay, chapterStep(forward = false, position = 0.0, chapters = listOf(Chapter("A", 0.0)), threshold = -1.0))
    }

    @Test
    fun speedAndLoopDoNothingInARoomAndResetDoesNotSeek() {
        val player = RecordingPlayer()
        val alone = controller(player, inRoom = false)
        alone.perform(PlaybackAction.SpeedMultiply(1.1))
        assertEquals(listOf("speed 1.1", "text Speed 1.10x"), player.log)
        player.log.clear()

        val room = controller(player, inRoom = true)
        room.perform(PlaybackAction.SpeedMultiply(2.0))
        room.perform(PlaybackAction.AbLoop)
        room.perform(PlaybackAction.LoopFile)
        room.perform(playbackAction(Key.Backspace, ctrl = true)!!)
        assertFalse(player.log.any { it.startsWith("seek") })
        val texts = player.log.filter { it.startsWith("text ") }
        assertEquals(listOf("text Speed follows the room", "text Looping stays off in a room", "text Looping stays off in a room", "text Reset"), texts)
        assertTrue(player.log.contains("speed 1.0"))
        assertTrue(player.log.any { it.startsWith("set sub-delay 0") })
        assertTrue(player.log.contains("scale 1.0"))
    }

    @Test
    fun seeksGoThroughThePlayer() {
        val player = RecordingPlayer()
        controller(player, inRoom = true).perform(PlaybackAction.SeekBy(5.0))
        controller(player, inRoom = false).perform(PlaybackAction.SeekStart)
        assertEquals(listOf("seekRelative 5.0", "seek 0.0"), player.log)
    }

    @Test
    fun aSeekStepDoesNotAskForThePlayerBar() {
        // Desktop arrow keys use SeekBy; showing the full HUD on every seek is wrong. Pause still does.
        assertFalse(PlaybackAction.SeekBy(5.0).showsControls)
        assertTrue(PlaybackAction.TogglePause.showsControls)
    }

    private fun controller(player: RecordingPlayer, inRoom: Boolean) = PlaybackController(
        player = player,
        inRoom = { inRoom },
        skip = { player.log += "skip $it" },
        adjustSubtitleScale = { player.log += "scale-delta $it" },
        setSubtitleScale = { player.log += "scale $it" },
        playlistText = { "" },
        trackText = { "" },
    )

    private class RecordingPlayer : Player {
        val log = mutableListOf<String>()
        override val state = MutableStateFlow(PlaybackState(speed = 1.0, fileLoaded = true, url = "file://a"))
        override val tracks = MutableStateFlow<List<Track>>(emptyList())
        override val events = MutableSharedFlow<PlayerEvent>()
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) { log += "load" }
        override fun stop() = Unit
        override fun setPaused(paused: Boolean) { log += "pause $paused" }
        override fun seek(position: Double) { log += "seek $position" }
        override fun seekRelative(offset: Double) { log += "seekRelative $offset" }
        override fun setSpeed(speed: Double) { log += "speed $speed" }
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun showText(text: String, durationMs: Int) { log += "text $text" }
        override val isFileLoaded = true
        override val isPaused = false
        override val duration = 100.0
        override fun currentPosition() = 10.0
        override fun command(vararg args: String): Boolean {
            log += args.joinToString(" ")
            return true
        }
        override fun setUserAudioDelay(seconds: Double) { log += "audio-delay $seconds" }
    }
}
