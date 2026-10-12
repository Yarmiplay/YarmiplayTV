package com.yarmiplaytv.desktop

import com.yarmiplaytv.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskbarProgressTest {
    private val playing = PlaybackState(fileLoaded = true, position = 300.0, duration = 1200.0, paused = false)

    @Test
    fun showsHowFarTheVideoHasPlayed() {
        assertEquals(TaskbarProgress(25, paused = false), taskbarProgress(playing))
        assertEquals(TaskbarProgress(25, paused = true), taskbarProgress(playing.copy(paused = true)))
        assertEquals(TaskbarProgress(100, paused = false), taskbarProgress(playing.copy(position = 1250.0)))
    }

    @Test
    fun showsNothingWithoutAVideoOrALength() {
        assertNull(taskbarProgress(PlaybackState()))
        assertNull(taskbarProgress(playing.copy(duration = 0.0)))
        assertNull(taskbarProgress(playing.copy(eofReached = true)))
    }
}
