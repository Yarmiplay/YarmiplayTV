package com.yarmiplaytv.relay

import com.yarmiplaytv.syncplay.GrowingMedia
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class GrowingRelayTest {
    @get:Rule val tmp = TemporaryFolder()

    private val media = GrowingMedia(growing = true, live = true, contentType = "video/mp2t", length = 0)

    // --- Index -------------------------------------------------------------------------------------

    @Test
    fun `reads the server's segment index`() {
        val page = GrowingIndex.parseIndex(
            """{"growing":true,"live":true,"length":3000,"from":2,"segments":[[1000,900,2.002],[1900,1100,2.0]],"extra":1}""",
        )
        assertNotNull(page)
        assertEquals(2, page!!.from)
        assertEquals(listOf(MediaSegment(1000, 900, 2.002), MediaSegment(1900, 1100, 2.0)), page.segments)
        assertTrue(page.growing)
        assertEquals(3000L, page.length)
        assertNull("a malformed segment would leave a gap", GrowingIndex.parseIndex("""{"from":0,"segments":[[0,10,2.0],[10,"x",2.0]]}"""))
        assertNull(GrowingIndex.parseIndex("not json"))
        assertEquals(emptyList<MediaSegment>(), GrowingIndex.parseIndex("""{"from":5,"segments":[]}""")!!.segments)
    }

    @Test
    fun `the index only takes what continues it, and is final once the server says the file ended`() {
        val index = GrowingIndex(media, OkHttpClient(), source = { null })
        index.merge(IndexPage(0, listOf(MediaSegment(0, 10, 2.0), MediaSegment(10, 10, 2.0)), growing = true, length = 25))
        // Overlapping answer: only its new tail is added.
        index.merge(IndexPage(1, listOf(MediaSegment(10, 10, 2.0), MediaSegment(20, 5, 1.0)), growing = true, length = 25))
        assertEquals(listOf(0L, 10L, 20L), index.state.value.segments.map { it.offset })
        // An answer that skips segments adds nothing, and can't make the index final.
        index.merge(IndexPage(9, listOf(MediaSegment(90, 10, 2.0)), growing = false, length = 100))
        assertEquals(3, index.state.value.segments.size)
        assertFalse(index.state.value.final)
        // The room's list saying it ended isn't enough for the playlist to end: the index has to say so.
        index.update(media.copy(growing = false, length = 30))
        assertFalse(index.state.value.growing)
        assertFalse(index.state.value.final)
        index.merge(IndexPage(3, listOf(MediaSegment(25, 5, 1.0)), growing = false, length = 30))
        assertTrue(index.state.value.final)
        assertEquals(6.0, index.state.value.indexedSeconds, 1e-9)
        // Lengths never go back.
        index.update(media.copy(length = 5))
        assertEquals(100L, index.state.value.length)
    }

    @Test
    fun `renders an event playlist of byte ranges, ended only when final`() {
        val segments = listOf(MediaSegment(0, 1500, 2.002), MediaSegment(1500, 1200, 1.96))
        val open = GrowingPlaylist.render(GrowingSnapshot(segments, growing = true, live = true, length = 3000), "media.ts")
        assertEquals(
            """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:3
            #EXT-X-PLAYLIST-TYPE:EVENT
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.002,
            #EXT-X-BYTERANGE:1500@0
            media.ts
            #EXTINF:1.960,
            #EXT-X-BYTERANGE:1200@1500
            media.ts

            """.trimIndent(),
            open,
        )
        val ended = GrowingPlaylist.render(GrowingSnapshot(segments, growing = false, live = true, length = 2700, final = true), "media.ts")
        assertTrue(ended.endsWith("media.ts\n#EXT-X-ENDLIST\n"))
        assertEquals("ts", GrowingPlaylist.extension("video/mp2t"))
        assertEquals("mp4", GrowingPlaylist.extension("video/mp4; codecs=avc1"))
    }

    // --- Cache -------------------------------------------------------------------------------------

    @Test
    fun `the written start of a block still arriving counts only for growing files`() {
        val cache = RelayCache(tmp.newFolder("relay"))
        val grows = cache.open("g", 8 * RelayCache.BLOCK, partialBlocks = true)
        grows.markComplete(0)
        grows.notePartial(1, RelayCache.BLOCK / 2)
        assertEquals(RelayCache.BLOCK + RelayCache.BLOCK / 2, grows.availableFrom(0))
        assertEquals(RelayCache.BLOCK / 2 - 100, grows.availableFrom(RelayCache.BLOCK + 100))
        assertEquals(0L, grows.availableFrom(2 * RelayCache.BLOCK))
        grows.notePartial(1, 10) // less than known: ignored
        assertEquals(RelayCache.BLOCK / 2, grows.partialOf(1))
        grows.markComplete(1)
        assertEquals(0L, grows.partialOf(1))
        assertEquals(2 * RelayCache.BLOCK, grows.availableFrom(0))

        val plain = cache.open("p", 8 * RelayCache.BLOCK)
        plain.markComplete(0)
        plain.notePartial(1, RelayCache.BLOCK / 2)
        assertEquals(RelayCache.BLOCK, plain.availableFrom(0))
    }

    @Test
    fun `a live file says so instead of a download percentage`() {
        val status = RelayStatus("x", emptyList(), false, 10, 20, null, null, false, false, null, growing = true, live = true)
        assertEquals("Live", status.text)
        assertEquals(RelayHint.Streaming, status.playbackHint(fileLoaded = true))
    }

    // --- Proxy -------------------------------------------------------------------------------------

    /** A server writing [data]: [length] bytes so far, a segment every [step] bytes, the last one to the end once finished. */
    private class GrowingServer(val data: ByteArray, val size: Long, val step: Int) {
        @Volatile var length = 0
        @Volatile var growing = true
        val fileRequests = CopyOnWriteArrayList<RecordedRequest>()
        val refused = CopyOnWriteArrayList<String>()

        fun segments(): List<Triple<Int, Int, Double>> {
            val n = length
            val out = ArrayList<Triple<Int, Int, Double>>()
            var at = 0
            while (at + step <= n) {
                val end = if (!growing && at + 2 * step > n) n else at + step
                out += Triple(at, end - at, 2.0)
                at = end
            }
            if (!growing && at < n) out += Triple(at, n - at, 1.0)
            return out
        }

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path.startsWith("/yarmiplay/files/g1/segments")) {
                    val from = path.substringAfter("from=").toInt()
                    val list = segments().drop(from).joinToString(",") { "[${it.first},${it.second},${it.third}]" }
                    return MockResponse().setBody("""{"growing":$growing,"live":true,"length":$length,"from":$from,"segments":[$list]}""")
                }
                fileRequests += request
                val have = length.toLong()
                val (start, end) = RelayProxy.parseRange(request.getHeader("Range"), size) ?: return MockResponse().setResponseCode(416)
                if (start >= have) {
                    refused += request.getHeader("Range")!!
                    return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */$size")
                }
                // Only what is written so far: a growing file's response ends at its current end.
                val last = minOf(end, have - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$last/$size")
                    .setBody(Buffer().write(data, start.toInt(), (last - start + 1).toInt()))
            }
        }
    }

    @Test
    fun `the player gets a growing file as an event playlist over byte ranges, until it ends`() {
        val total = (5 * RelayCache.BLOCK + 123_457).toInt()
        val data = ByteArray(total) { ((it * 13 + 5) % 251).toByte() }
        val size = 10L shl 30
        val step = 700_000
        val grower = GrowingServer(data, size, step).apply { length = 3 * step + 1000 }
        val server = MockWebServer()
        server.dispatcher = grower.dispatcher
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = RelayProxy(scope)
        try {
            val session = YarmiplaySession("secret-token", 1, server.url("/").toString().trimEnd('/'))
            val source = { RelaySource(session, "g1") }
            val http = relayHttpClient()
            val index = GrowingIndex(media.copy(length = grower.length.toLong()), http, source)
            val cache = RelayCache(tmp.newFolder("relay")).open("g", size, partialBlocks = true)
            val transfer = RelayTransfer("g", "Recording", size, cache, http, scope, index, source)
            transfer.demand(0)
            transfer.start()
            val url = proxy.url(transfer)
            assertTrue(url.endsWith("/index.m3u8"))
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            fun get(path: String, range: String? = null) =
                client.newCall(Request.Builder().url(url.replace("index.m3u8", path)).apply { range?.let { header("Range", it) } }.build()).execute()

            val first = get("index.m3u8").use { r ->
                assertEquals(200, r.code)
                assertEquals("application/vnd.apple.mpegurl", r.header("Content-Type"))
                r.body!!.string()
            }
            assertTrue(first.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
            assertTrue(first.contains("#EXT-X-BYTERANGE:$step@$step\nmedia.ts"))
            assertEquals(3, Regex("#EXTINF").findAll(first).count())
            assertFalse(first.contains("ENDLIST"))

            val second = get("media.ts", "bytes=$step-${2 * step - 1}").use { r ->
                assertEquals(206, r.code)
                assertEquals("video/mp2t", r.header("Content-Type"))
                assertEquals("bytes $step-${2 * step - 1}/${grower.length}", r.header("Content-Range"))
                r.body!!.bytes()
            }
            assertArrayEquals(data.copyOfRange(step, 2 * step), second)
            get("media.ts", "bytes=${grower.length}-").use { assertEquals("past what exists", 416, it.code) }

            // The server writes the rest and finishes; its last block is only partly filled.
            grower.length = total
            grower.growing = false
            index.update(media.copy(growing = false, length = total.toLong()))
            Thread.sleep(GrowingIndex.REFRESH_MS + 200)
            val last = get("index.m3u8").use { it.body!!.string() }
            assertTrue(last.endsWith("#EXT-X-ENDLIST\n"))
            val segs = Regex("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").findAll(last).map { it.groupValues[2].toLong() to it.groupValues[1].toLong() }.toList()
            assertEquals(0L, segs.first().first)
            assertEquals(total.toLong(), segs.last().first + segs.last().second)
            val (tailAt, tailLength) = segs.last()
            val tail = get("media.ts", "bytes=$tailAt-${tailAt + tailLength - 1}").use { r ->
                assertEquals(206, r.code)
                r.body!!.bytes()
            }
            assertArrayEquals("the end, partial block included", data.copyOfRange(tailAt.toInt(), total), tail)
            assertTrue(grower.fileRequests.all { it.getHeader("Authorization") == "Bearer secret-token" })
            assertEquals("never asked for bytes that weren't written", emptyList<String>(), grower.refused)
            transfer.stop()
        } finally {
            proxy.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `a normal file still gets its own name and plain byte serving`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = RelayProxy(scope)
        try {
            val cache = RelayCache(tmp.newFolder("relay"))
            val transfer = RelayTransfer("k", "a.mkv", 100, cache.open("k", 100), relayHttpClient(), scope) { null }
            assertTrue(proxy.url(transfer).endsWith("/a.mkv"))
            assertNull(transfer.growing)
        } finally {
            proxy.close()
            scope.cancel()
        }
    }
}
