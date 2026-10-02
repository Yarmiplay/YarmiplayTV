package com.yarmiplaytv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistEditsTest {
    private val list = listOf("a.mkv", "b.mkv", "c.mkv", "d.mkv", "e.mkv")

    @Test
    fun `new entries skip blanks, repeats and what's already there`() {
        assertEquals(
            listOf("f.mkv", "https://example.org/x.mp4"),
            PlaylistEdits.newEntries(list, listOf(" ", "b.mkv", "f.mkv", " f.mkv ", "https://example.org/x.mp4")),
        )
    }

    @Test
    fun `insert places entries before an index, or at the end`() {
        val added = listOf("x.mkv", "y.mkv")
        assertEquals(listOf("x.mkv", "y.mkv") + list, PlaylistEdits.insert(list, added, 0))
        assertEquals(listOf("a.mkv", "b.mkv", "x.mkv", "y.mkv", "c.mkv", "d.mkv", "e.mkv"), PlaylistEdits.insert(list, added, 2))
        assertEquals(list + added, PlaylistEdits.insert(list, added, null))
        assertEquals(list + added, PlaylistEdits.insert(list, added, 99))
        assertEquals(added + list, PlaylistEdits.insert(list, added, -1))
    }

    @Test
    fun `shift moves a selection as a block and stops at the ends`() {
        assertEquals(listOf("b.mkv", "c.mkv", "a.mkv", "d.mkv", "e.mkv") to setOf(0, 1), PlaylistEdits.shift(list, setOf(1, 2), -1))
        assertEquals(listOf("a.mkv", "c.mkv", "b.mkv", "e.mkv", "d.mkv") to setOf(2, 4), PlaylistEdits.shift(list, setOf(1, 3), 1))
        assertEquals(list to setOf(0, 2), PlaylistEdits.shift(list, setOf(0, 2), -1))
        assertEquals(list to setOf(4), PlaylistEdits.shift(list, setOf(4), 1))
    }

    @Test
    fun `remove and move`() {
        assertEquals(listOf("b.mkv", "d.mkv", "e.mkv"), PlaylistEdits.remove(list, setOf(0, 2)))
        assertEquals(listOf("b.mkv", "c.mkv", "d.mkv", "a.mkv", "e.mkv"), PlaylistEdits.move(list, 0, 3))
        assertEquals(list, PlaylistEdits.move(list, 0, 9))
    }

    @Test
    fun `shuffling what's left keeps the entries up to the current one`() {
        val shuffled = PlaylistEdits.shuffle(list, index = 1, entire = false, random = Random(7))
        assertEquals(list.take(2), shuffled.take(2))
        assertEquals(list.toSet(), shuffled.toSet())
        val entire = PlaylistEdits.shuffle(list, index = 1, entire = true, random = Random(7))
        assertEquals(list.toSet(), entire.toSet())
        assertEquals(list.size, entire.size)
    }

    @Test
    fun `playlist files are one entry per line`() {
        val text = PlaylistEdits.format(listOf("a.mkv", "https://youtu.be/x"))
        assertEquals("a.mkv\nhttps://youtu.be/x\n", text)
        assertEquals(listOf("a.mkv", "https://youtu.be/x"), PlaylistEdits.parse("\r\n a.mkv \r\n\r\nhttps://youtu.be/x\n"))
    }

    @Test
    fun `trusted domains work like Syncplay's`() {
        val domains = TrustedDomains.DEFAULT + "example.org/videos/"
        assertTrue(TrustedDomains.isTrusted("https://www.youtube.com/watch?v=x", domains))
        assertTrue(TrustedDomains.isTrusted("https://youtu.be/x", domains))
        assertTrue(TrustedDomains.isTrusted("HTTP://YOUTUBE.COM/watch", domains))
        assertTrue(TrustedDomains.isTrusted("https://example.org/videos/a.mp4", domains))
        assertTrue(TrustedDomains.isTrusted("https://example.org/videos", domains))
        assertFalse(TrustedDomains.isTrusted("https://example.org/other/a.mp4", domains))
        assertFalse(TrustedDomains.isTrusted("https://youtube.com.evil.example/x", domains))
        assertFalse(TrustedDomains.isTrusted("https://m.youtube.com/x", domains))
        assertFalse(TrustedDomains.isTrusted("ftp://youtube.com/x", domains))
        assertFalse(TrustedDomains.isTrusted("https://youtube.com/x", emptyList()))
    }

    @Test
    fun `the domain to trust drops www`() {
        assertEquals("example.org", TrustedDomains.domainOf("https://www.example.org/a.mp4"))
        assertEquals("cdn.example.org", TrustedDomains.domainOf("http://cdn.example.org:8080/a.mp4"))
        assertNull(TrustedDomains.domainOf("not a url"))
    }
}
