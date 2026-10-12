package com.yarmiplaytv.media

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

    @Test
    fun `episodes in anime release names`() {
        fun check(name: String, title: String, season: Int?, episode: Int, dir: String? = null) {
            val p = FileNames.parseEpisode(name, dir)!!
            assertEquals(name, Triple(title, season, episode), Triple(p.title, p.season, p.episode))
        }
        check("[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv", "Frieren", null, 5)
        check("[Group] Hero Academy S2 - 05v2 [1080p].mkv", "Hero Academy", 2, 5)
        check("Hero Academy 2nd Season - 13.mkv", "Hero Academy", 2, 13)
        check("Hero Academy Season 3 - 01.mkv", "Hero Academy", 3, 1)
        check("Hero.Academy.E50.1080p.mkv", "Hero Academy", null, 50)
        check("Show - S01E02 - Name.mkv", "Show", 1, 2)
        check("05.mkv", "Hero Academy", 2, 5, dir = "Hero Academy/Season 2")
        check("Hero Academy - 07.mkv", "Hero Academy", 4, 7, dir = "Anime/Hero Academy/Season 04")
    }

    @Test
    fun `movies aren't episodes`() {
        assertEquals(null, FileNames.parseEpisode("Your Name (2016) [1080p].mkv"))
        assertEquals(null, FileNames.parseEpisode("Movie - 1080p.mkv"))
    }
}
