package com.yarmiplaytv.media.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleSearchTest {

    @Test
    fun openSubtitlesHashComputes64BitHashForValidFile() {
        val tmp = Files.createTempFile("oshash", ".mkv").toFile()
        try {
            val bytes = ByteArray(200 * 1024) { (it and 0xFF).toByte() }
            tmp.writeBytes(bytes)
            val hash = OpenSubtitlesHash.compute(tmp.absolutePath)
            assertNotNull(hash)
            assertEquals(16, hash!!.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
            // Deterministic across runs
            assertEquals(hash, OpenSubtitlesHash.compute(tmp.absolutePath))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun openSubtitlesHashReturnsNullForSmallFiles() {
        val tmp = Files.createTempFile("oshash-small", ".mkv").toFile()
        try {
            tmp.writeBytes(ByteArray(1024))
            assertNull(OpenSubtitlesHash.compute(tmp.absolutePath))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun defaultsAndLanguageLabels() {
        assertEquals("en", SubtitleLanguages.DEFAULT)
        assertEquals("English", SubtitleLanguages.labelOf("en"))
        assertEquals("Portuguese (Brazil)", SubtitleLanguages.labelOf("pt-br"))
        assertEquals("Any language", SubtitleLanguages.labelOf("all"))
        assertTrue(SubtitleLanguages.COMMON.first() == ("en" to "English"))
    }

    @Test
    fun unconfiguredClientThrowsWithoutNetwork() = runBlocking {
        val client = SubtitleSearchClient(openSubtitlesApiKey = "", subdlApiKey = "")
        assertFalse(client.configured)
        var threw = false
        try {
            client.search(SubtitleSearchQuery(query = "Frieren", language = "en"))
        } catch (_: com.yarmiplaytv.media.MediaSourceException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun extractSubtitleFromZipPicksSubtitleEntry() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("ignore me".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("folder/Frieren.S01E01.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:03,000\nHello\n".toByteArray())
            zos.closeEntry()
        }
        val extracted = SubtitleSearchClient.extractSubtitle(baos.toByteArray(), "fallback.srt", "en")
        assertEquals("Frieren.S01E01.srt", extracted.fileName)
        assertTrue(String(extracted.content).contains("Hello"))
    }
}
