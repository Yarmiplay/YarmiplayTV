package com.yarmiplaytv.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekDiskTest {
    @Test
    fun steadyPlaybackWaitsAndASeekPauseOrNewFileWrites() {
        assertTrue(SeekDisk.due(nowMs = 0, lastMs = 0, position = 10.0, lastPos = Double.NaN, sameFile = true, force = false))
        assertFalse(SeekDisk.due(nowMs = 1_000, lastMs = 0, position = 11.0, lastPos = 10.0, sameFile = true, force = false))
        assertTrue(SeekDisk.due(nowMs = 5_000, lastMs = 0, position = 15.0, lastPos = 10.0, sameFile = true, force = false))
        assertTrue(SeekDisk.due(nowMs = 100, lastMs = 0, position = 30.0, lastPos = 10.0, sameFile = true, force = false))
        assertTrue(SeekDisk.due(nowMs = 100, lastMs = 0, position = 11.0, lastPos = 10.0, sameFile = false, force = false))
        assertTrue(SeekDisk.due(nowMs = 100, lastMs = 0, position = 11.0, lastPos = 10.0, sameFile = true, force = true))
    }
}
