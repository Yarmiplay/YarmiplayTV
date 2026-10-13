package com.yarmiplaytv.desktop

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.ui.mobile.PlayerInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val log = mutableListOf<String>()

    private inner class Target(full: Boolean = false, val playerShowing: Boolean = true, val canGoBack: Boolean = true) : ShortcutTarget {
        private var full = full
        override val isFullscreen get() = full
        override fun setFullscreen(on: Boolean) { full = on; log += "fullscreen $on" }
        override fun back(): Boolean { log += "back"; return canGoBack }
        override val player: PlayerInput? get() = if (playerShowing) input else null
        override fun changeVolume(delta: Double) { log += "volume $delta" }
        override fun toggleMute() { log += "mute" }
        override fun focusChat() { log += "chat" }
        override fun screenshot(subtitles: Boolean) { log += if (subtitles) "screenshot" else "screenshot without subtitles" }
        override fun togglePictureInPicture() { log += "picture-in-picture" }
        var skipOffered = false
        override fun skipSegment(): Boolean {
            if (skipOffered) log += "skip"
            return skipOffered
        }
    }

    private val input = PlayerInput(
        seekStep = 10.0,
        controlsVisible = false,
        toggleControls = { log += "toggleControls" },
        showControls = { log += "show" },
        togglePause = { log += "pause" },
        seekBy = { log += "seek $it" },
        skip = { log += if (it) "next" else "previous" },
    )

    @Test
    fun playerKeys() {
        val t = Target()
        assertTrue(handleShortcut(Key.Spacebar, false, t))
        assertTrue(handleShortcut(Key.DirectionLeft, false, t))
        assertTrue(handleShortcut(Key.DirectionRight, false, t))
        assertTrue(handleShortcut(Key.DirectionUp, false, t))
        assertTrue(handleShortcut(Key.DirectionDown, false, t))
        assertTrue(handleShortcut(Key.M, false, t))
        assertTrue(handleShortcut(Key.F, false, t))
        assertTrue(handleShortcut(Key.Enter, false, t))
        assertEquals(
            listOf(
                "pause", "show", "seek -10.0", "show", "seek 10.0", "show", "volume 5.0", "show", "volume -5.0", "show",
                "mute", "show", "fullscreen true", "show", "chat", "show",
            ),
            log,
        )
    }

    @Test
    fun nAndBOrTheMediaKeysGoToTheNextAndPreviousPlaylistEntry() {
        val t = Target()
        assertTrue(handleShortcut(Key.N, false, t))
        assertTrue(handleShortcut(Key.B, false, t))
        assertTrue(handleShortcut(Key.MediaNext, false, t))
        assertTrue(handleShortcut(Key.MediaPrevious, false, t))
        assertFalse(handleShortcut(Key.N, true, t))
        assertEquals(listOf("next", "show", "previous", "show", "next", "show", "previous", "show"), log)
    }

    @Test
    fun sSkipsTheOpeningOnlyWhileTheSkipButtonShows() {
        val t = Target()
        assertFalse(handleShortcut(Key.S, false, t))
        t.skipOffered = true
        assertTrue(handleShortcut(Key.S, false, t))
        assertEquals(listOf("skip"), log)
    }

    @Test
    fun hTogglesTheControlsWithoutShowingThem() {
        assertTrue(handleShortcut(Key.H, false, Target()))
        assertFalse(handleShortcut(Key.H, true, Target()))
        assertFalse(handleShortcut(Key.H, false, Target(playerShowing = false)))
        assertEquals(listOf("toggleControls"), log)
    }

    @Test
    fun f5TakesAScreenshotWithoutShowingTheControls() {
        assertTrue(handleShortcut(Key.F5, false, Target()))
        assertFalse(handleShortcut(Key.F5, true, Target()))
        assertFalse(handleShortcut(Key.F5, false, Target(playerShowing = false)))
        assertEquals(listOf("screenshot"), log)
    }

    @Test
    fun f6TakesAScreenshotWithoutTheSubtitles() {
        assertTrue(handleShortcut(Key.F6, false, Target()))
        assertEquals(listOf("screenshot without subtitles"), log)
    }

    @Test
    fun pTogglesPictureInPictureWithoutShowingTheControls() {
        assertTrue(handleShortcut(Key.P, false, Target()))
        assertFalse(handleShortcut(Key.P, true, Target()))
        assertFalse(handleShortcut(Key.P, false, Target(playerShowing = false)))
        assertEquals(listOf("picture-in-picture"), log)
    }

    @Test
    fun escapeLeavesFullscreenBeforeGoingBack() {
        val t = Target(full = true)
        assertTrue(handleShortcut(Key.Escape, false, t))
        assertTrue(handleShortcut(Key.Escape, false, t))
        assertEquals(listOf("fullscreen false", "back"), log)
        assertFalse(handleShortcut(Key.Escape, false, Target(canGoBack = false)))
    }

    @Test
    fun playerKeysNeedThePlayerAndNoModifiers() {
        assertFalse(handleShortcut(Key.Spacebar, false, Target(playerShowing = false)))
        assertFalse(handleShortcut(Key.F, true, Target()))
        assertFalse(handleShortcut(Key.A, false, Target()))
        assertEquals(emptyList<String>(), log)
    }
}
