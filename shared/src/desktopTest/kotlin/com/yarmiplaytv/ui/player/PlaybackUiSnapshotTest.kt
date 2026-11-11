package com.yarmiplaytv.ui.player

import com.yarmiplaytv.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiSnapshotTest {
    @Test
    fun clockTicksDoNotChangeTheUiSnapshot() {
        val a = PlaybackState(
            position = 1.0,
            cacheSeconds = 3.1,
            avSync = 0.01,
            frameDrops = 2,
            decoderFrameDrops = 1,
            displayLatencyMs = 8.4,
            fileLoaded = true,
            duration = 100.0,
            paused = false,
        )
        val snapshots = (0 until 60).map { tick ->
            a.copy(position = 1.0 + tick / 60.0, avSync = 0.01 + tick * 0.001, frameDrops = 2L + tick).forUi()
        }
        assertTrue(snapshots.all { it == snapshots.first() })
        assertEquals(0.0, snapshots.first().position, 0.0)
        assertNull(snapshots.first().avSync)
        assertNull(snapshots.first().frameDrops)
        assertEquals(3.0, snapshots.first().cacheSeconds, 0.0)
        assertEquals(100.0, snapshots.first().duration, 0.0)
        assertTrue(snapshots.first().fileLoaded)
    }

    @Test
    fun statsStayVisibleButNotAtFrameRate() {
        val state = PlaybackState(avSync = 0.021, displayLatencyMs = 8.4, frameDrops = 3, fileLoaded = true, position = 4.0)
        val shown = state.forControls(showStats = true)
        assertEquals(0.0, shown.avSync!!, 0.0) // 0.021 rounds to 0.00 at 50 ms
        assertEquals(8.0, shown.displayLatencyMs!!, 0.0)
        assertEquals(3L, shown.frameDrops)
        val nearby = state.copy(position = 4.2, avSync = 0.024).forControls(showStats = true)
        assertEquals(shown, nearby)
        val moved = state.copy(avSync = 0.08).forControls(showStats = true)
        assertEquals(0.1, moved.avSync!!, 0.0)
    }
}
