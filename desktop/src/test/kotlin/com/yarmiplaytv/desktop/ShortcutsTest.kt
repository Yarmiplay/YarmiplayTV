package com.yarmiplaytv.desktop

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.ui.mobile.PlayerInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val log = mutableListOf<String>()

    private inner class Target(
        full: Boolean = false,
        val playerShowing: Boolean = true,
        val canGoBack: Boolean = true,
    ) : ShortcutTarget {
        private var full = full
        override val isFullscreen get() = full
        override fun setFullscreen(on: Boolean) { full = on; log += "fullscreen $on" }
        override fun back(): Boolean { log += "back"; return canGoBack }
        override val player: PlayerInput? get() = if (playerShowing) input else null
        var skipOffered = false
        override fun perform(action: PlaybackAction): Boolean {
            if (action == PlaybackAction.SkipSegment) {
                if (!skipOffered) return false
                log += "skip"
                return true
            }
            log += action.javaClass.simpleName + if (action is PlaybackAction.SeekBy) " ${action.seconds}" else ""
            return true
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

    private fun press(key: Key, target: Target = Target(), ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false) =
        handleShortcut(key, ctrl, alt, shift, meta, target)

    @Test
    fun arrowsSeekWithoutShowingTheControlsAndSpaceStillDoes() {
        val t = Target()
        assertTrue(press(Key.Spacebar, t))
        assertTrue(press(Key.DirectionLeft, t))
        assertTrue(press(Key.DirectionRight, t))
        assertTrue(press(Key.DirectionUp, t))
        assertTrue(press(Key.DirectionDown, t))
        assertEquals(
            listOf(
                "TogglePause", "show",
                "SeekBy -5.0",
                "SeekBy 5.0",
                "SeekBy 60.0",
                "SeekBy -60.0",
            ),
            log,
        )
    }

    @Test
    fun shiftArrowsSeekExactlyWithoutShowingTheControls() {
        val t = Target()
        assertTrue(press(Key.DirectionLeft, t, shift = true))
        assertTrue(press(Key.DirectionUp, t, shift = true))
        assertEquals(listOf("SeekBy -1.0", "SeekBy 5.0"), log)
    }

    @Test
    fun playlistKeysMatchMpvAndShiftNGoesBack() {
        val t = Target()
        assertTrue(press(Key.N, t))
        assertTrue(press(Key.N, t, shift = true))
        assertTrue(press(Key.Enter, t))
        assertTrue(press(Key.MediaPrevious, t))
        assertTrue(press(Key.B, t))
        assertEquals(
            listOf("Playlist", "show", "Playlist", "show", "Playlist", "show", "Playlist", "show", "CycleProperty"),
            log,
        )
    }

    @Test
    fun zAdjustsSubtitleDelayAndGAndFAdjustSize() {
        val t = Target()
        assertTrue(press(Key.Z, t))
        assertTrue(press(Key.Z, t, shift = true))
        assertTrue(press(Key.G, t))
        assertTrue(press(Key.F, t, shift = true))
        assertTrue(press(Key.F, t))
        assertEquals(listOf("Adjust", "Adjust", "SubtitleScaleBy", "SubtitleScaleBy", "fullscreen true", "show"), log)
    }

    @Test
    fun ySkipsTheOpeningOnlyWhileTheSkipButtonShows() {
        val t = Target()
        assertFalse(press(Key.Y, t))
        t.skipOffered = true
        assertTrue(press(Key.Y, t))
        assertEquals(listOf("skip"), log)
    }

    @Test
    fun hTogglesTheControlsWithoutShowingThem() {
        assertTrue(press(Key.H))
        assertFalse(press(Key.H, meta = true))
        assertFalse(press(Key.H, Target(playerShowing = false)))
        assertEquals(listOf("toggleControls"), log)
    }

    @Test
    fun screenshotsUseMpvKeysAndTheOldFunctionKeys() {
        assertTrue(press(Key.S))
        assertTrue(press(Key.S, shift = true))
        assertTrue(press(Key.F5))
        assertTrue(press(Key.F6))
        assertEquals(listOf("Screenshot", "Screenshot", "Screenshot", "Screenshot"), log)
    }

    @Test
    fun pShowsProgressAndF12IsPictureInPicture() {
        assertTrue(press(Key.P, shift = true))
        assertTrue(press(Key.F12))
        assertEquals(listOf("ShowProgress", "TogglePip"), log)
    }

    @Test
    fun cOpensChatAndCtrlBackspaceResets() {
        assertTrue(press(Key.C))
        assertTrue(press(Key.Backspace, ctrl = true))
        assertEquals(listOf("FocusChat", "show", "ResetAll"), log)
    }

    @Test
    fun escapeLeavesFullscreenBeforeGoingBack() {
        val t = Target(full = true)
        assertTrue(press(Key.Escape, t))
        assertTrue(press(Key.Escape, t))
        assertEquals(listOf("fullscreen false", "back"), log)
        assertFalse(press(Key.Escape, Target(canGoBack = false)))
    }

    @Test
    fun playerKeysNeedThePlayerAndMetaIsLeftAlone() {
        assertFalse(press(Key.Spacebar, Target(playerShowing = false)))
        assertFalse(press(Key.F, meta = true))
        assertFalse(press(Key.A))
        assertEquals(emptyList<String>(), log)
    }
}
