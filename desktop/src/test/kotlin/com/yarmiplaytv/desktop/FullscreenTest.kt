package com.yarmiplaytv.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fullscreen's Compose [WindowState] sync: after a native resize the scene must use the new bounds,
 * or the player stays cropped with a gutter (the F-key bug on Windows).
 */
class FullscreenTest {
    @Test
    fun publishFloatingBoundsUpdatesWindowState() {
        val state = WindowState(
            placement = WindowPlacement.Maximized,
            position = WindowPosition(40.dp, 60.dp),
            size = DpSize(1280.dp, 720.dp),
        )
        val fullscreen = Fullscreen(state)
        fullscreen.publishFloatingBounds(0, 0, 1920, 1080)
        assertEquals(WindowPlacement.Floating, state.placement)
        assertEquals(WindowPosition(0.dp, 0.dp), state.position)
        assertEquals(DpSize(1920.dp, 1080.dp), state.size)
    }

    @Test
    fun nonWindowsPathUsesComposeFullscreenPlacement() {
        if (isWindows) return
        val state = WindowState(size = DpSize(1280.dp, 800.dp))
        val fullscreen = Fullscreen(state)
        assertFalse(fullscreen.isFullscreen)
        fullscreen.set(true)
        assertTrue(fullscreen.isFullscreen)
        assertEquals(WindowPlacement.Fullscreen, state.placement)
        fullscreen.set(false)
        assertFalse(fullscreen.isFullscreen)
        assertEquals(WindowPlacement.Floating, state.placement)
    }
}
