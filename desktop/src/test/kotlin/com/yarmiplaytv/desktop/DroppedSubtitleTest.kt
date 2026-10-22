package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A subtitle dropped on the window stays off the video list, so it is not added to the shared playlist. */
class DroppedSubtitleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aSubtitleDroppedWithAVideoIsKeptApartFromTheVideos() {
        val dir = tmp.newFolder("drop")
        val episode = File(dir, "Episode.mkv").apply { writeBytes(byteArrayOf(1)) }
        val english = File(dir, "Episode.en.srt").apply { writeText("1") }
        val notes = File(dir, "notes.txt").apply { writeText("n") }
        val season = File(dir, "Season").apply { mkdir() }
        val (videos, subs) = splitDropped(listOf(episode, english, notes, season))
        assertEquals(listOf("Episode.mkv", "notes.txt", "Season"), videos.map { it.name })
        assertEquals(listOf("Episode.en.srt"), subs.map { it.name })
    }
}
