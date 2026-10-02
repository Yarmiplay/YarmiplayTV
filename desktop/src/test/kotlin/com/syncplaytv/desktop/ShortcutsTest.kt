package com.syncplaytv.desktop

import androidx.compose.ui.input.key.Key
import com.syncplaytv.ui.mobile.PlayerInput
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
        override fun focusChat() { log += "chat" }
    }

    private val input = PlayerInput(
        seekStep = 10.0,
        controlsVisible = false,
        toggleControls = { log += "toggleControls" },
        showControls = { log += "show" },
        togglePause = { log += "pause" },
        seekBy = { log += "seek $it" },
    )

    @Test
    fun playerKeys() {
        val t = Target()
        assertTrue(handleShortcut(Key.Spacebar, false, t))
        assertTrue(handleShortcut(Key.DirectionLeft, false, t))
        assertTrue(handleShortcut(Key.DirectionRight, false, t))
        assertTrue(handleShortcut(Key.DirectionUp, false, t))
        assertTrue(handleShortcut(Key.DirectionDown, false, t))
        assertTrue(handleShortcut(Key.F, false, t))
        assertTrue(handleShortcut(Key.Enter, false, t))
        assertEquals(
            listOf(
                "pause", "show", "seek -10.0", "show", "seek 10.0", "show", "volume 5.0", "show", "volume -5.0", "show",
                "fullscreen true", "show", "chat", "show",
            ),
            log,
        )
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
