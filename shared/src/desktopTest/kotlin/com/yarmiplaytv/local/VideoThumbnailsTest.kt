package com.yarmiplaytv.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class VideoThumbnailsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val grabs = AtomicInteger()
    private val widths = mutableListOf<Int>()
    private val dir by lazy { tmp.root.resolve("thumbnails") }

    /** Writes the URI as the "JPEG"; URIs containing "broken" can't be decoded. */
    private fun thumbnails(onGrab: () -> Unit = {}) = VideoThumbnails(dir) { uri, width, target ->
        grabs.incrementAndGet()
        synchronized(widths) { widths += width }
        onGrab()
        if ("broken" in uri) false else { target.writeText(uri); true }
    }

    private fun file(name: String, size: Long = 100, modified: Long = 1) = LocalFile(name, size, "file:///videos/$name", modified = modified)

    @Test
    fun `makes a preview once and then serves it from the cache`() = runBlocking {
        val thumbnails = thumbnails()
        val first = thumbnails.thumbnail(file("a.mkv"))
        assertNotNull(first)
        assertEquals("file:///videos/a.mkv", first!!.readText())
        assertEquals(dir, first.parentFile)
        assertTrue(first.name.endsWith(".jpg"))
        assertEquals(listOf(VideoThumbnails.WIDTH), widths)

        assertEquals(first, thumbnails.thumbnail(file("a.mkv")))
        // A later run (a new instance on the same folder) finds it on disk too.
        assertEquals(first, thumbnails().thumbnail(file("a.mkv")))
        assertEquals(1, grabs.get())
    }

    @Test
    fun `a changed file gets a new preview`() = runBlocking {
        val thumbnails = thumbnails()
        val before = thumbnails.thumbnail(file("a.mkv", size = 100, modified = 1))
        val resized = thumbnails.thumbnail(file("a.mkv", size = 200, modified = 1))
        val touched = thumbnails.thumbnail(file("a.mkv", size = 100, modified = 2))
        assertNotEquals(before, resized)
        assertNotEquals(before, touched)
        assertEquals(3, grabs.get())
    }

    @Test
    fun `a video that can't be decoded isn't tried again and leaves nothing behind`() = runBlocking {
        val thumbnails = thumbnails()
        assertNull(thumbnails.thumbnail(file("broken.mkv")))
        assertNull(thumbnails.thumbnail(file("broken.mkv")))
        assertEquals(1, grabs.get())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a grabber that throws counts as no preview`() = runBlocking {
        val thumbnails = thumbnails { error("decoder crashed") }
        assertNull(thumbnails.thumbnail(file("a.mkv")))
        assertNull(thumbnails.thumbnail(file("a.mkv")))
        assertEquals(1, grabs.get())
        assertTrue(dir.listFiles().orEmpty().none { it.extension == "jpg" })
    }

    @Test
    fun `requests for the same file at once make it only once`() = runBlocking {
        val thumbnails = thumbnails { Thread.sleep(50) }
        val results = (1..6).map { async(Dispatchers.Default) { thumbnails.thumbnail(file("a.mkv")) } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertNotNull(results.first())
        assertEquals(1, grabs.get())
    }

    @Test
    fun `keys depend on the uri, size and modification time only`() {
        val key = VideoThumbnails.keyOf(file("a.mkv"))
        assertEquals(key, VideoThumbnails.keyOf(file("a.mkv").copy(folder = "Movies", directory = "x")))
        assertNotEquals(key, VideoThumbnails.keyOf(file("b.mkv")))
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
    }
}
