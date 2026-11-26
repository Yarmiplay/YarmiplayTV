package com.yarmiplaytv.sync

import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.PlayableMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRestOfSeasonTest {

    private fun ep(n: Int): PlayableMedia = PlayableMedia(
        itemId = "id-$n",
        url = "https://srv/$n.mkv",
        fileName = "Frieren.S01E%02d.mkv".format(n),
        sizeBytes = 1000L,
        durationSeconds = 1440.0,
        title = "Ep $n",
        episode = EpisodeInfo("Frieren", 1, n, 2023, "jf/frieren"),
    )

    @Test
    fun selectRestOfSeasonWithAndWithoutAnchor() {
        val season = (1..5).map(::ep)
        val anchor = season[2] // Ep 3
        assertEquals(
            listOf("Frieren.S01E03.mkv", "Frieren.S01E04.mkv", "Frieren.S01E05.mkv"),
            PlaylistController.selectRestOfSeason(season, anchor, includeAnchor = true).map { it.fileName },
        )
        assertEquals(
            listOf("Frieren.S01E04.mkv", "Frieren.S01E05.mkv"),
            PlaylistController.selectRestOfSeason(season, anchor, includeAnchor = false).map { it.fileName },
        )
        assertEquals(
            emptyList<String>(),
            PlaylistController.selectRestOfSeason(season, season.last(), includeAnchor = false).map { it.fileName },
        )
    }

    @Test
    fun localRestOfSeasonFiltersBySameFolderShowAndSeason() {
        val files = listOf(
            LocalFile(name = "Show.S01E01.mkv", sizeBytes = 100L, uri = "file:///a/1", directory = "Show/Season 1"),
            LocalFile(name = "Show.S01E02.mkv", sizeBytes = 100L, uri = "file:///a/2", directory = "Show/Season 1"),
            LocalFile(name = "Show.S01E03.mkv", sizeBytes = 100L, uri = "file:///a/3", directory = "Show/Season 1"),
            LocalFile(name = "Show.S02E01.mkv", sizeBytes = 100L, uri = "file:///a/4", directory = "Show/Season 1"),
            LocalFile(name = "Other.S01E04.mkv", sizeBytes = 100L, uri = "file:///b/1", directory = "Show/Season 1"),
            LocalFile(name = "Show.S01E04.mkv", sizeBytes = 100L, uri = "file:///c/4", directory = "OtherFolder"),
        )
        assertEquals(
            listOf("Show.S01E02.mkv", "Show.S01E03.mkv"),
            PlaylistController.localRestOfSeason("Show.S01E02.mkv", "Show/Season 1", files, includeSelf = true),
        )
        assertEquals(
            listOf("Show.S01E03.mkv"),
            PlaylistController.localRestOfSeason("Show.S01E02.mkv", "Show/Season 1", files, includeSelf = false),
        )
    }

    @Test
    fun pickNextEpisodeSkipsAlreadyQueuedEpisodes() {
        val remaining = listOf("Show.S01E02.mkv", "Show.S01E03.mkv", "Show.S01E04.mkv")
        assertEquals("Show.S01E02.mkv", PlaylistController.pickNextEpisode(remaining, listOf("Show.S01E01.mkv")))
        assertEquals("Show.S01E03.mkv", PlaylistController.pickNextEpisode(remaining, listOf("Show.S01E01.mkv", "Show.S01E02.mkv")))
        assertEquals(null, PlaylistController.pickNextEpisode(remaining, remaining))
    }
}
