package com.yarmiplaytv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A collector that saved on every time-pos wrote the settings file about 60 times a second.
 * Steady playback now writes once, then at most once per 5 seconds.
 */
class SeekAutosaveTest {
    @Test
    fun steadyPlaybackWritesOnceThenWaits() {
        val save = SeekAutosave()
        var now = 1_000L
        var writes = 0
        repeat(120) { i ->
            if (save.shouldSave("a.mkv", 10.0 + i / 60.0, paused = false, ended = false, nowMs = now)) writes++
            now += 16
        }
        assertEquals(1, writes)
        assertTrue(save.shouldSave("a.mkv", 16.0, paused = false, ended = false, nowMs = now + 5_000))
        assertFalse(save.shouldSave("a.mkv", 16.2, paused = false, ended = false, nowMs = now + 5_100))
    }

    @Test
    fun aSeekAndAPauseAndTheEndAreWrittenAtOnce() {
        val save = SeekAutosave()
        assertTrue(save.shouldSave("a.mkv", 30.0, paused = false, ended = false, nowMs = 0))
        assertTrue(save.shouldSave("a.mkv", 60.0, paused = false, ended = false, nowMs = 100))
        assertTrue(save.shouldSave("a.mkv", 60.6, paused = true, ended = false, nowMs = 200))
        assertFalse(save.shouldSave("a.mkv", 60.6, paused = true, ended = false, nowMs = 300))
        assertTrue(save.shouldSave("a.mkv", 61.0, paused = false, ended = true, nowMs = 400))
        assertFalse(save.shouldSave("a.mkv", 61.0, paused = false, ended = true, nowMs = 500))
    }

    @Test
    fun aNewFileIsWrittenAndTheFirstSecondIsNot() {
        val save = SeekAutosave()
        assertFalse(save.shouldSave("a.mkv", 0.4, paused = false, ended = false, nowMs = 0))
        assertFalse(save.shouldSave("", 12.0, paused = false, ended = false, nowMs = 0))
        assertTrue(save.shouldSave("a.mkv", 12.0, paused = false, ended = false, nowMs = 0))
        assertTrue(save.shouldSave("b.mkv", 12.0, paused = false, ended = false, nowMs = 10))
    }
}
