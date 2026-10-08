package com.yarmiplaytv.relay

import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.RelayFile
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.syncplay.SharedJellyfin
import com.yarmiplaytv.syncplay.Yarmiplay
import com.yarmiplaytv.syncplay.YarmiplayCapabilities
import com.yarmiplaytv.syncplay.YarmiplayInfo
import com.yarmiplaytv.syncplay.YarmiplaySession
import com.yarmiplaytv.sync.HostJellyfinSharing
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RelayTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- Cache -------------------------------------------------------------------------------

    @Test
    fun `cache is wiped when created`() {
        val dir = tmp.newFolder("relay")
        File(dir, "old.part").writeText("left over")
        RelayCache(dir)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `cache evicts the least recently used files beyond its cap, but not the one in use`() {
        var now = 1_000L
        val cache = RelayCache(tmp.newFolder("relay"), capBytes = { 3 * RelayCache.BLOCK }, now = { now })
        fun fill(key: String) {
            val f = cache.open(key, 2 * RelayCache.BLOCK)
            f.markComplete(0)
            f.markComplete(1)
            now += 10
        }
        fill("a")
        fill("b")
        fill("c")
        cache.open("a", 2 * RelayCache.BLOCK) // used again: now the newest
        cache.trim(inUse = setOf("b"))
        // 6 MiB against a 3 MiB cap: "b" is the oldest but in use, so "c" goes, then "a" (4 MiB is still too much).
        assertEquals(setOf("b"), cache.keys())

        // Once "b" isn't playing anymore it's just the oldest.
        fill("d")
        cache.trim()
        assertEquals(setOf("d"), cache.keys())
    }

    @Test
    fun `cache keeps the newest files that fit`() {
        var now = 1_000L
        val cache = RelayCache(tmp.newFolder("relay"), capBytes = { 4 * RelayCache.BLOCK }, now = { now })
        for (key in listOf("a", "b", "c")) {
            val f = cache.open(key, 2 * RelayCache.BLOCK)
            f.markComplete(0)
            f.markComplete(1)
            now += 10
        }
        cache.trim()
        assertEquals(setOf("b", "c"), cache.keys())
    }

    @Test
    fun `cache drops files older than a day`() {
        var now = 0L
        val cache = RelayCache(tmp.newFolder("relay"), capBytes = { Long.MAX_VALUE }, now = { now })
        cache.open("old", 10)
        now = 25 * 60 * 60 * 1000L
        cache.open("new", 10)
        cache.trim()
        assertEquals(setOf("new"), cache.keys())
    }

    @Test
    fun `cached file reports contiguous bytes from an offset`() {
        val cache = RelayCache(tmp.newFolder("relay"))
        val size = 2 * RelayCache.BLOCK + 100
        val f = cache.open("k", size)
        assertEquals(0, f.availableFrom(0))
        f.markComplete(1)
        f.markComplete(2)
        assertEquals(RelayCache.BLOCK + 100, f.availableFrom(RelayCache.BLOCK))
        assertEquals(100L, f.availableFrom(2 * RelayCache.BLOCK))
        assertEquals(0, f.firstMissing(0))
        f.markComplete(0)
        assertNull(f.firstMissing(0))
        assertTrue(f.complete)
        assertEquals(size, f.cachedBytes)
    }

    // --- Stream or download ------------------------------------------------------------------

    @Test
    fun `fraction is how much of the file is here`() {
        val status = RelayStatus(
            "a.mp4", emptyList(), downloading = true, haveBytes = 250, size = 1_000,
            bytesPerSecond = null, etaSeconds = null, waitingToPlay = true, complete = false, error = null,
        )
        assertEquals(0.25, status.fraction.toDouble(), 0.0001)
        assertEquals(0.0, status.copy(size = 0).fraction.toDouble(), 0.0)
        assertEquals(1.0, status.copy(haveBytes = 1_000).fraction.toDouble(), 0.0001)
        assertEquals(RelayHint.Downloading, status.playbackHint(fileLoaded = true))
        assertEquals(RelayHint.Downloading, status.copy(waitingToPlay = false).playbackHint(fileLoaded = false))
        assertEquals(RelayHint.None, status.copy(waitingToPlay = false).playbackHint(fileLoaded = true))
        assertEquals(RelayHint.Streaming, status.copy(downloading = false, waitingToPlay = false).playbackHint(fileLoaded = true))
        assertEquals(RelayHint.None, status.copy(downloading = false).playbackHint(fileLoaded = false))
        assertEquals(RelayHint.None, status.copy(complete = true).playbackHint(fileLoaded = true))
    }

    @Test
    fun `downloads when throughput is under 1_3 times the bitrate`() {
        val size = 1_000_000_000L
        val duration = 1_000.0 // 1 MB/s
        assertTrue(RelayPolicy.shouldDownload(1_200_000.0, size, duration))
        assertFalse(RelayPolicy.shouldDownload(1_400_000.0, size, duration))
        // Idle fetches (null) mean the network keeps up.
        assertFalse(RelayPolicy.shouldDownload(null, size, duration))
        // Without a duration there's no bitrate to compare with.
        assertFalse(RelayPolicy.shouldDownload(10.0, size, 0.0))
    }

    @Test
    fun `goes back to streaming only well above the threshold`() {
        val size = 1_000_000_000L
        val duration = 1_000.0
        assertFalse(RelayPolicy.shouldStream(1_500_000.0, size, duration))
        assertTrue(RelayPolicy.shouldStream(2_100_000.0, size, duration))
    }

    @Test
    fun `ready again once the rest arrives before playback gets there, with a margin`() {
        val size = 1_000_000_000L
        val duration = 1_000.0
        // 500 MB left at 1 MB/s takes 500 s; 1000 s of playback left gives 800 s with the margin.
        assertTrue(RelayPolicy.canPlayThrough(size, 500_000_000, 1_000_000.0, duration, position = 0.0))
        // At position 400 only 600 * 0.8 = 480 s remain: not yet.
        assertFalse(RelayPolicy.canPlayThrough(size, 500_000_000, 1_000_000.0, duration, position = 400.0))
        assertFalse(RelayPolicy.canPlayThrough(size, 0, null, duration, 0.0))
        assertTrue(RelayPolicy.canPlayThrough(size, size, null, duration, 999.0))
    }

    // --- Offers --------------------------------------------------------------------------------

    private fun local(name: String, size: Long = 1_000) = LocalFile(name, size, "file:///videos/$name")

    @Test
    fun `offers the playlist files this device has, named like the playlist`() {
        val files = listOf(local("Show - S01E01.mkv"), local("Other.mkv"), local("empty.mkv", size = 0))
        val users = listOf(RoomUser("ana", "r", FileInfo("Show - S01E01.mkv", 1422.5, 1_000)))
        val offers = RelayOffers.offersFor(
            playlist = listOf("Show - S01E01.mkv", "https://example.com/stream.m3u8", "missing.mkv", "empty.mkv", "show - s01e01.mkv"),
            files = files,
            users = users,
            reported = null,
            hash = { "h-${it.name}" },
        )
        // Each playlist name the file matches is offered, since viewers look relayed files up by that name.
        assertEquals(listOf("Show - S01E01.mkv", "show - s01e01.mkv"), offers.map { it.first.name })
        val (offer, file) = offers.first()
        assertEquals(1_000L, offer.size)
        assertEquals(1422.5, offer.duration, 0.0)
        assertEquals("h-Show - S01E01.mkv", offer.quickHash)
        assertEquals(files[0], file)
    }

    @Test
    fun `skips files it can't hash and caps the offer`() {
        val names = (1..250).map { "ep$it.mkv" }
        val offers = RelayOffers.offersFor(names, names.map { local(it) }, emptyList(), null) { f -> if (f.name == "ep1.mkv") null else "h" }
        assertEquals(Yarmiplay.MAX_OFFERED_FILES, offers.size)
        assertEquals("ep2.mkv", offers.first().first.name)
    }

    @Test
    fun `offers a file opened directly when no media folder has it`() {
        val opened = LocalFile("Opened Movie.mp4", 5_000, "file:///elsewhere/Opened%20Movie.mp4")
        val folder = listOf(local("Show - S01E01.mkv"))
        val candidates = RelayOffers.candidates(folder, listOf(opened, folder[0]))
        assertEquals(listOf(folder[0], opened), candidates)
        val offers = RelayOffers.offersFor(listOf("Opened Movie.mp4"), candidates, emptyList(), null) { "h" }
        assertEquals(opened, offers.single().second)
        assertEquals("Opened Movie.mp4", offers.single().first.name)
    }

    @Test
    fun `uses our own reported duration first`() {
        val offers = RelayOffers.offersFor(listOf("a.mkv"), listOf(local("a.mkv")), emptyList(), "a.mkv" to 60.0) { "h" }
        assertEquals(60.0, offers.single().first.duration, 0.0)
    }

    // --- Finding a relayed file ------------------------------------------------------------------

    private fun relayRoom(files: List<RelayFile>, users: List<RoomUser> = emptyList(), relay: Boolean = true) = RoomState(
        username = "me",
        users = users,
        yarmiplay = YarmiplayInfo(capabilities = YarmiplayCapabilities(fileRelay = relay), relayFiles = files, session = true),
    )

    private fun entry(name: String, size: Long = 100, sources: Int = 1, cached: Long = 0) =
        RelayFile("id-$name", name, size, 10.0, "hash", sources, cached, 0)

    @Test
    fun `finds a relayed file by name when someone seeds it or the server has all of it`() {
        assertEquals("a.mkv", RelayManager.find(relayRoom(listOf(entry("a.mkv"))), "a.mkv")?.name)
        assertEquals("a.mkv", RelayManager.find(relayRoom(listOf(entry("a.mkv"))), "C:\\videos\\a.mkv")?.name)
        assertNull(RelayManager.find(relayRoom(listOf(entry("a.mkv", sources = 0, cached = 50))), "a.mkv"))
        assertEquals("a.mkv", RelayManager.find(relayRoom(listOf(entry("a.mkv", sources = 0, cached = 100))), "a.mkv")?.name)
        assertNull(RelayManager.find(relayRoom(listOf(entry("a.mkv")), relay = false), "a.mkv"))
        assertNull(RelayManager.find(relayRoom(listOf(entry("b.mkv"))), "a.mkv"))
    }

    @Test
    fun `a relayed file must have the size the room reports`() {
        val ana = RoomUser("ana", "r", FileInfo("a.mkv", 10.0, 999))
        assertNull(RelayManager.find(relayRoom(listOf(entry("a.mkv", size = 100)), listOf(ana)), "a.mkv"))
        assertEquals(100L, RelayManager.find(relayRoom(listOf(entry("a.mkv", size = 100)), listOf(ana.copy(file = FileInfo("a.mkv", 10.0, 100)))), "a.mkv")?.size)
    }

    // --- Jellyfin candidates ------------------------------------------------------------------

    @Test
    fun `tries the Syncplay server's proxy before the host's addresses`() {
        val session = YarmiplaySession("t", 1, "http://sync.example:8999")
        val shared = SharedJellyfin(true, "id", "Jelly", proxy = true, addresses = listOf("http://192.168.1.20:8096", "http://sync.example:8999"))
        assertEquals(listOf("http://sync.example:8999", "http://192.168.1.20:8096"), HostJellyfinSharing.candidates(shared, session))
        assertEquals(listOf("http://192.168.1.20:8096", "http://sync.example:8999"), HostJellyfinSharing.candidates(shared.copy(proxy = false), session))
    }

    // --- Proxy ------------------------------------------------------------------------------------

    @Test
    fun `parses single byte ranges`() {
        assertEquals(0L to 99L, RelayProxy.parseRange(null, 100))
        assertEquals(10L to 99L, RelayProxy.parseRange("bytes=10-", 100))
        assertEquals(10L to 19L, RelayProxy.parseRange("bytes=10-19", 100))
        assertEquals(10L to 99L, RelayProxy.parseRange("bytes=10-500", 100))
        assertEquals(90L to 99L, RelayProxy.parseRange("bytes=-10", 100))
        assertNull(RelayProxy.parseRange("bytes=100-", 100))
        assertNull(RelayProxy.parseRange("bytes=20-10", 100))
        assertNull(RelayProxy.parseRange("items=0-1", 100))
    }

    @Test
    fun `the player reads a relayed file through the proxy, fetched in ranges with the bearer token`() {
        val size = 5 * RelayCache.BLOCK + 12_345
        val data = ByteArray(size.toInt()) { ((it * 31 + 7) % 251).toByte() }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (start, end) = RelayProxy.parseRange(request.getHeader("Range"), size) ?: return MockResponse().setResponseCode(416)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/$size")
                    .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = RelayProxy(scope)
        try {
            val session = YarmiplaySession("secret-token", 1, server.url("/").toString().trimEnd('/'))
            val cache = RelayCache(tmp.newFolder("relay"))
            val transfer = RelayTransfer("k", "a b.mkv", size, cache.open("k", size), relayHttpClient(), scope) { RelaySource(session, "file1") }
            transfer.start()
            val url = proxy.url(transfer)
            assertTrue(url.endsWith("/a%20b.mkv"))
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

            val whole = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals(size.toString(), r.header("Content-Length"))
                r.body!!.bytes()
            }
            assertArrayEquals(data, whole)

            val part = client.newCall(Request.Builder().url(url).header("Range", "bytes=3000000-3000099").build()).execute().use { r ->
                assertEquals(206, r.code)
                assertEquals("bytes 3000000-3000099/$size", r.header("Content-Range"))
                r.body!!.bytes()
            }
            assertArrayEquals(data.copyOfRange(3_000_000, 3_000_100), part)

            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer secret-token" })
            assertTrue(requests.all { it.path!!.startsWith("/yarmiplay/files/file1?mode=stream") })
            assertTrue("the token stays out of URLs", requests.none { "secret-token" in it.path!! })
            transfer.stop()
        } finally {
            proxy.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `the proxy listens on IPv4 loopback, where its URLs point`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxy = RelayProxy(scope)
        try {
            val cache = RelayCache(tmp.newFolder("relay"))
            val transfer = RelayTransfer("k", "a.mp4", 100, cache.open("k", 100), relayHttpClient(), scope) { null }
            val url = URI(proxy.url(transfer))
            assertEquals("127.0.0.1", url.host)
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), url.port), 2_000) }
            assertTrue(proxy.isProxyUrl(url.toString()))
        } finally {
            proxy.close()
            scope.cancel()
        }
    }
}
