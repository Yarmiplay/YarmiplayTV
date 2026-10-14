package com.yarmiplaytv.local

import com.yarmiplaytv.media.MatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMatcherTest {
    private val ep1 = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"
    private val files = listOf(
        LocalFile("Other Show - S01E01.mkv", 10, "content://x/1", "Movies"),
        LocalFile(ep1, 587443083, "content://x/2", "Movies"),
        LocalFile("my.movie.2020.1080p.mp4", 20, "content://x/3", "Downloads"),
    )

    @Test
    fun `exact filename wins`() {
        val m = LocalMatcher.find(files, ep1)!!
        assertEquals("content://x/2", m.file.uri)
        assertEquals(MatchKind.EXACT_FILENAME, m.kind)
    }

    @Test
    fun `paths and case are ignored for exact matches`() {
        val m = LocalMatcher.find(files, "D:\\Anime\\" + ep1.uppercase())!!
        assertEquals("content://x/2", m.file.uri)
        assertEquals(MatchKind.EXACT_FILENAME, m.kind)
    }

    @Test
    fun `syncplay normalisation matches differently punctuated names`() {
        val m = LocalMatcher.find(files, "My Movie 2020 1080p.mp4")!!
        assertEquals("content://x/3", m.file.uri)
        assertEquals(MatchKind.NORMALIZED_FILENAME, m.kind)
    }

    @Test
    fun `unknown files are not matched`() {
        assertNull(LocalMatcher.find(files, "Hyperdimension Neptunia - S01E02.mkv"))
        assertNull(LocalMatcher.find(emptyList(), ep1))
    }

    @Test
    fun `video detection uses mime type or extension`() {
        assertTrue(LocalMatcher.isVideo("a.bin", "video/x-matroska"))
        assertTrue(LocalMatcher.isVideo("a.MKV", "application/octet-stream"))
        assertFalse(LocalMatcher.isVideo("notes.txt", "text/plain"))
    }

    @Test
    fun `the index finds what find does, for every kind of match and none`() {
        val withDuplicates = files + listOf(
            LocalFile(ep1, 1, "content://y/dup", "Other"),
            LocalFile("MY.MOVIE.2020.1080P.MP4", 30, "content://y/upper", "Other"),
        )
        val index = LocalMatcher.Index(withDuplicates)
        val names = listOf(ep1, "D:\\Anime\\" + ep1.uppercase(), "My Movie 2020 1080p.mp4", "my.movie.2020.1080p.mp4", "MY.MOVIE.2020.1080P.MP4", "Something else.mkv", "", "!!!.mkv")
        for (name in names) assertEquals(name, LocalMatcher.find(withDuplicates, name), index.find(name))
    }

    @Test
    fun `a long playlist is matched against a big library quickly`() {
        val library = (1..3000).map { LocalFile("Show $it - S01E%02d [1080p] (Group).mkv".format(it % 24), 1, "file:///$it") }
        val playlist = (1..200).map { "Missing Show $it - S02E%02d.mkv".format(it % 24) }
        val started = System.nanoTime()
        val index = LocalMatcher.Index(library)
        assertTrue(playlist.all { index.find(it) == null })
        // Matching each entry with find takes seconds here; the index normalizes each name once.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }
}
