package com.yarmiplaytv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekThumbsTest {
    private val set = TrickplaySet(intervalMs = 10_000, thumbWidth = 320, thumbHeight = 180, columns = 10, rows = 10, count = 250)

    @Test
    fun trickplayTileIsTheFrameAtThatTime() {
        val first = trickplayTile(set, 0.0)!!
        assertEquals(0, first.sheet)
        assertEquals(0, first.x)
        assertEquals(0, first.y)

        val later = trickplayTile(set, 25.4)!!
        assertEquals(0, later.sheet)
        assertEquals(2 * 320, later.x)
        assertEquals(0, later.y)

        val nextSheet = trickplayTile(set, 1000.0)!!
        assertEquals(1, nextSheet.sheet)
        assertEquals(0, nextSheet.x)
        assertEquals(0, nextSheet.y)
    }

    @Test
    fun trickplayPicksAWideEnoughResolution() {
        assertEquals(320, trickplayWidth(listOf(160, 320, 640), 200))
        assertEquals(320, trickplayWidth(listOf(160, 320), 1000))
        assertNull(trickplayWidth(emptyList(), 320))
    }

    @Test
    fun jellyfinSheetUrlAndPlexIndexUrl() {
        assertEquals(
            "https://jelly.test/Videos/item-1/Trickplay/320/2.jpg?api_key=tok",
            jellyfinSheetUrl("https://jelly.test", "item-1", 320, 2, "tok"),
        )
        assertEquals(
            "http://plex.test:32400/library/parts/445/indexes/sd/12500?X-Plex-Token=abc",
            plexPreviewUrl("http://plex.test:32400/", "445", "sd", "abc", 12.5),
        )
        assertEquals("445", plexPartId("/library/parts/445/file.mkv"))
    }

    @Test
    fun sidecarNamesMatchTheVideoAndOneLanguageTag() {
        assertEquals(true, sidecarMatches("Episode.mkv", "Episode.srt"))
        assertEquals(true, sidecarMatches("Episode.mkv", "episode.en.ass"))
        assertEquals(false, sidecarMatches("Episode.mkv", "Episode.extra.en.srt"))
        assertEquals(false, sidecarMatches("Episode.mkv", "Other.srt"))
        assertEquals("en", sidecarLanguage("Episode.en.srt"))
        assertNull(sidecarLanguage("Episode.srt"))
        assertEquals("b", preferredSidecar(
            listOf(ExternalSubtitle("a", "A", "en"), ExternalSubtitle("b", "B", "ja")),
            "jpn,ja",
        )?.url)
    }
}
