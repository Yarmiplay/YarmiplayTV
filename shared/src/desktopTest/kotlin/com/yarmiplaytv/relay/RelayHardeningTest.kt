package com.yarmiplaytv.relay

import com.yarmiplaytv.syncplay.GrowingMedia
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** A room member's announcements or a server's answers can't freeze the app or reach the player's HTTP headers. */
class RelayHardeningTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- Cache ---------------------------------------------------------------------------------------

    @Test
    fun `cached bytes of a file announced as huge are counted without walking every block`() {
        val cache = RelayCache(tmp.newFolder("relay"), capBytes = { Long.MAX_VALUE })
        // 1 TiB plus a short last block: a million blocks to walk if they were counted one by one.
        val size = (1L shl 40) + 1234
        val file = cache.open("huge", size)
        val last = file.blockOf(size - 1)
        val started = System.nanoTime()
        repeat(10_000) { file.cachedBytes }
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
        assertEquals(0L, file.cachedBytes)
        file.markComplete(0)
        file.markComplete(last)
        file.markComplete(last) // twice counts once
        file.markComplete(-1)
        file.markComplete(last + 1)
        assertEquals(RelayCache.BLOCK + 1234, file.cachedBytes)
        assertEquals(RelayCache.BLOCK + 1234, cache.totalBytes())
    }

    @Test
    fun `cached bytes add up like the blocks they count`() {
        val cache = RelayCache(tmp.newFolder("relay"), capBytes = { Long.MAX_VALUE })
        val size = 7 * RelayCache.BLOCK + 99
        val file = cache.open("f", size)
        val random = Random(4)
        val done = mutableSetOf<Int>()
        repeat(20) {
            val i = random.nextInt(8)
            file.markComplete(i)
            done += i
            assertEquals(done.sumOf { file.blockLength(it) }, file.cachedBytes)
        }
        cache.delete("f")
        assertEquals("a deleted file reports what it had, as before", done.sumOf { file.blockLength(it) }, file.cachedBytes)
        (0 until 8).firstOrNull { it !in done }?.let(file::markComplete)
        assertEquals(done.sumOf { file.blockLength(it) }, file.cachedBytes)
    }

    // --- Growing files -------------------------------------------------------------------------------

    @Test
    fun `segment durations must be finite`() {
        assertNull(GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,1e400]]}"""))
        assertNull(GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,"Infinity"]]}"""))
        assertNull(GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,"NaN"]]}"""))
        assertNull(GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,2.0],[10,10,-1]]}"""))
        assertEquals(1, GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,1e300]]}""")!!.segments.size)
    }

    @Test
    fun `the kept playlist is the same text as one rendered from scratch`() {
        val renderer = GrowingPlaylist.Renderer()
        val random = Random(7)
        val segments = mutableListOf<MediaSegment>()
        var offset = 0L
        fun check(snapshot: GrowingSnapshot, uri: String) {
            assertEquals(GrowingPlaylist.render(snapshot, uri), renderer.render(snapshot, uri))
        }
        check(GrowingSnapshot(emptyList(), growing = true, live = true, length = 0), "media.ts")
        repeat(60) { round ->
            repeat(random.nextInt(0, 4)) {
                val length = random.nextLong(1, 5_000)
                segments += MediaSegment(offset, length, random.nextDouble(0.0, 12.0))
                offset += length
            }
            val snapshot = GrowingSnapshot(segments.toList(), growing = true, live = round % 2 == 0, length = offset)
            check(snapshot, "media.ts")
            check(snapshot, "media.ts") // a reload with nothing new
        }
        // The file ends: same segments, now final.
        check(GrowingSnapshot(segments.toList(), growing = false, live = false, length = offset, final = true), "media.ts")
        // Another file name, a list that isn't a continuation, and a shorter list all start over.
        check(GrowingSnapshot(segments.toList(), growing = true, live = true, length = offset), "media.mp4")
        val other = segments.map { it.copy(duration = it.duration + 1) }
        check(GrowingSnapshot(other, growing = true, live = true, length = offset), "media.mp4")
        check(GrowingSnapshot(other.take(3), growing = true, live = true, length = offset), "media.mp4")
        check(GrowingSnapshot(emptyList(), growing = true, live = true, length = offset), "media.mp4")
    }

    @Test
    fun `an index that learns nothing new keeps its list instead of copying it`() {
        val index = GrowingIndex(GrowingMedia(growing = true, live = true, contentType = "video/mp2t", length = 0), OkHttpClient(), source = { null })
        index.merge(IndexPage(0, listOf(MediaSegment(0, 10, 2.0)), growing = true, length = 10))
        val before = index.state.value.segments
        index.merge(IndexPage(0, listOf(MediaSegment(0, 10, 2.0)), growing = true, length = 20))
        assertSame(before, index.state.value.segments)
        assertEquals(20L, index.state.value.length)
        index.merge(IndexPage(1, listOf(MediaSegment(10, 10, 2.0)), growing = false, length = 20))
        assertEquals(listOf(0L, 10L), index.state.value.segments.map { it.offset })
        assertTrue(index.state.value.final)
    }

    // --- Proxy -----------------------------------------------------------------------------------------

    @Test
    fun `a content type can't add headers`() {
        assertEquals("video/mp2tX-Evil: 1", RelayProxy.headerValue("video/mp2t\r\nX-Evil: 1"))
        assertEquals("video/mp4; codecs=avc1", RelayProxy.headerValue("video/mp4; codecs=avc1"))
        assertEquals("ab", RelayProxy.headerValue("a\u0000\u007f\nb"))
    }
}
