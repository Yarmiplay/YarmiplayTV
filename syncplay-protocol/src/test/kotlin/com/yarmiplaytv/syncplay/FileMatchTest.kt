package com.yarmiplaytv.syncplay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMatchTest {
    private val ep1 = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"
    private val mine = FileInfo(ep1, 1420.0, 587443083)

    @Test
    fun `hashes match Syncplay's utils`() {
        // hashlib.sha256(...).hexdigest()[:12] of the stripped name and of str(size), as Syncplay sends them.
        assertEquals("fd0cfaa80c3c", Filenames.hash(ep1))
        assertEquals("194298ffe18f", Filenames.hashSize(587443083))
    }

    @Test
    fun `hashed and hidden names match the plain name`() {
        assertTrue(Filenames.same(ep1, "fd0cfaa80c3c"))
        assertTrue(Filenames.same("fd0cfaa80c3c", ep1))
        assertTrue(Filenames.same(ep1, Filenames.HIDDEN))
        assertFalse(Filenames.same(ep1.replace("S01E01", "S01E02"), "fd0cfaa80c3c"))
    }

    @Test
    fun `sizes compare plainly or by hash, and unknown sizes match`() {
        assertEquals(emptySet<FileDifference>(), FileMatch.differences(mine, mine.copy(name = "fd0cfaa80c3c")))
        assertEquals(setOf(FileDifference.SIZE), FileMatch.differences(mine, mine.copy(size = 587443084)))
        assertTrue(FileMatch.sameSize(mine, mine.copy(size = 0, sizeHash = "194298ffe18f")))
        assertFalse(FileMatch.sameSize(mine, mine.copy(size = 0, sizeHash = Filenames.hashSize(1))))
        assertTrue(FileMatch.sameSize(mine, mine.copy(size = 0)))
        assertTrue(FileMatch.sameSize(mine.copy(size = 0, sizeHash = "194298ffe18f"), mine.copy(size = 0, sizeHash = "194298ffe18f")))
    }

    @Test
    fun `durations within the threshold match`() {
        assertTrue(FileMatch.sameDuration(mine, mine.copy(duration = 1421.9)))
        assertEquals(setOf(FileDifference.NAME, FileDifference.DURATION), FileMatch.differences(mine, FileInfo("other.mkv", 1300.0, 587443083)))
    }

    @Test
    fun `a size sent as a hash is kept as one`() {
        fun parse(json: String) = SyncplayClient.parseFile(Json.parseToJsonElement(json))!!
        assertEquals(FileInfo("a.mkv", 10.0, 0, "194298ffe18f"), parse("""{"name":"a.mkv","duration":10.0,"size":"194298ffe18f"}"""))
        // An all-digit hash is still a hash when it comes as a string.
        assertEquals(FileInfo("a.mkv", 10.0, 0, "123456789012"), parse("""{"name":"a.mkv","duration":10.0,"size":"123456789012"}"""))
        assertEquals(FileInfo("a.mkv", 10.0, 587443083), parse("""{"name":"a.mkv","duration":10.0,"size":587443083}"""))
        assertEquals(FileInfo("a.mkv", 10.0, 0), parse("""{"name":"a.mkv","duration":10.0,"size":0}"""))
    }
}
