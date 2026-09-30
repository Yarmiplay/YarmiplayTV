package com.syncplaytv.syncplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLogicTest {
    private val ep1 = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"

    @Test
    fun `same filename ignores separators and brackets like Syncplay`() {
        assertTrue(Filenames.same(ep1, "Hyperdimension_Neptunia_S01E01_The_Goddess_Neptune_Of_Planeptune.mkv"))
        assertTrue(Filenames.same(ep1, "http://10.0.2.2:8096/files/Hyperdimension%20Neptunia%20-%20S01E01%20-%20The%20Goddess%20(Neptune)%20Of%20Planeptune.mkv"))
        assertFalse(Filenames.same(ep1, ep1.replace("S01E01", "S01E02")))
    }

    @Test
    fun `base name handles windows and posix paths`() {
        assertEquals(ep1, Filenames.baseName("F:\\Torrents\\Neptunia\\Season 1\\$ep1"))
        assertEquals(ep1, Filenames.baseName("/media/anime/Season 1/$ep1"))
    }

    @Test
    fun `playlist edits keep pointing at the current file`() {
        val old = listOf("a.mkv", "b.mkv", "c.mkv")
        assertEquals(2, SyncplayClient.validIndexFromNewPlaylist(old, 1, listOf("x.mkv", "a.mkv", "b.mkv", "c.mkv")))
        assertEquals(1, SyncplayClient.validIndexFromNewPlaylist(old, 2, listOf("a.mkv", "c.mkv")))
        assertEquals(0, SyncplayClient.validIndexFromNewPlaylist(emptyList(), null, listOf("a.mkv")))
    }

    @Test
    fun `removing the current file moves to the next surviving one`() {
        val old = listOf("a.mkv", "b.mkv", "c.mkv")
        assertEquals(1, SyncplayClient.validIndexFromNewPlaylist(old, 1, listOf("a.mkv", "c.mkv")))
    }

    @Test
    fun `format time`() {
        assertEquals("01:05", formatTime(65.4))
        assertEquals("1:00:00", formatTime(3600.0))
    }
}
