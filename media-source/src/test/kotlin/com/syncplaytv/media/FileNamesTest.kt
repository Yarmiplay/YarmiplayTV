package com.syncplaytv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNamesTest {
    @Test
    fun baseNameHandlesWindowsAndUnixPaths() {
        assertEquals("a.mkv", FileNames.baseName("F:\\Torrents\\Show\\a.mkv"))
        assertEquals("a.mkv", FileNames.baseName("/media/tv/Show/a.mkv"))
        assertEquals("a.mkv", FileNames.baseName("a.mkv"))
    }

    @Test
    fun normalizeMatchesSyncplayStripping() {
        assertEquals(
            FileNames.normalize("Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"),
            FileNames.normalize("hyperdimension_neptunia_-_s01e01_-_the_goddess_neptune_of_planeptune.mkv"),
        )
        assertEquals(FileNames.normalize("My%20File.mkv"), FileNames.normalize("My File.mkv"))
    }

    @Test
    fun parsesEpisode() {
        val p = FileNames.parse("Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv")
        assertEquals("Hyperdimension Neptunia", p.title)
        assertEquals(1, p.season)
        assertEquals(1, p.episode)
    }

    @Test
    fun parsesReleaseGroupEpisode() {
        val p = FileNames.parse("[SubsPlease] Frieren.S01E12.1080p.HEVC.x265-Group.mkv")
        assertEquals("Frieren", p.title)
        assertEquals(1, p.season)
        assertEquals(12, p.episode)
    }

    @Test
    fun parsesMovieWithYear() {
        val p = FileNames.parse("Spirited.Away.2001.1080p.BluRay.x264.mkv")
        assertEquals("Spirited Away", p.title)
        assertEquals(2001, p.year)
        assertNull(p.episode)
    }

    @Test
    fun parsesMovieWithParenthesisedYear() {
        val p = FileNames.parse("Your Name (2016) [1080p].mkv")
        assertEquals("Your Name", p.title)
        assertEquals(2016, p.year)
    }
}
