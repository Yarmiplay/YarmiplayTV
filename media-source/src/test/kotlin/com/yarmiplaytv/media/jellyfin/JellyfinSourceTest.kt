package com.yarmiplaytv.media.jellyfin

import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.PlaybackReport
import com.yarmiplaytv.media.ReportState
import com.yarmiplaytv.media.ResolveResult
import com.yarmiplaytv.media.SkipSegment
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

class JellyfinSourceTest {
    private lateinit var server: MockWebServer
    private val client = JellyfinClient(ClientInfo(deviceName = "Test TV", deviceId = "dev1", version = "0.1"))
    private var quickConnectPolls = 0

    private val fileName = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"
    private val path = "F:\\Torrents\\Neptunia\\Season 1\\$fileName"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val body = when (url.encodedPath) {
                    "/System/Info/Public" -> """{"ServerName":"Benny","Version":"10.11.6","Id":"srv"}"""
                    "/QuickConnect/Initiate" -> """{"Secret":"s3cret","Code":"123456","Authenticated":false}"""
                    "/QuickConnect/Connect" -> """{"Secret":"s3cret","Code":"123456","Authenticated":${++quickConnectPolls >= 2}}"""
                    "/Users/AuthenticateWithQuickConnect", "/Users/AuthenticateByName" ->
                        """{"User":{"Id":"u1","Name":"yarmi"},"AccessToken":"tok","ServerId":"srv"}"""
                    "/Items" -> when {
                        url.queryParameter("ids") == "ep1" -> """{"Items":[${episodeJson(withSources = true)}],"TotalRecordCount":1}"""
                        url.queryParameter("fields") == "Path" && url.queryParameter("recursive") == "true" ->
                            """{"Items":[${episodeJson(withSources = false)},{"Id":"m1","Name":"Other","Type":"Movie","Path":"/x/Other.mkv"}],"TotalRecordCount":2}"""
                        url.queryParameter("includeItemTypes") == "Series" -> """{"Items":[{"Id":"series1","Name":"Hyperdimension Neptunia","Type":"Series","IsFolder":true}],"TotalRecordCount":1}"""
                        url.queryParameter("includeItemTypes") == "Episode" -> """{"Items":[${episodeJson(withSources = true)}],"TotalRecordCount":1}"""
                        else -> """{"Items":[],"TotalRecordCount":0}"""
                    }
                    "/Shows/series1/Episodes" -> if (url.queryParameter("seasonId") != "season1") """{"Items":[]}""" else {
                        val second = path.replace("\\", "\\\\").replace("E01", "E02")
                        """{"Items":[${episodeJson(withSources = true)},
                            {"Id":"ep2","Name":"Two","Type":"Episode","IndexNumber":2,"ParentIndexNumber":1,"SeriesName":"Hyperdimension Neptunia","SeriesId":"series1",
                             "MediaSources":[{"Id":"ms2","Path":"$second","Size":5,"RunTimeTicks":14300000000,"Protocol":"File"}]},
                            {"Id":"ep3","Name":"No file","Type":"Episode","IndexNumber":3}]}"""
                    }
                    "/MediaSegments/ep1" -> """{"Items":[
                        {"Id":"s1","ItemId":"ep1","Type":"Recap","StartTicks":0,"EndTicks":300000000},
                        {"Id":"s2","ItemId":"ep1","Type":"Intro","StartTicks":300000000,"EndTicks":1200000000},
                        {"Id":"s3","ItemId":"ep1","Type":"Outro","StartTicks":13500000000,"EndTicks":14400000000}]}"""
                    "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped", "/Users/u1/PlayedItems/ep1" ->
                        return MockResponse().setResponseCode(204)
                    "/Users/Me" -> """{"Id":"u1","Name":"yarmi"}"""
                    "/UserViews" -> """{"Items":[{"Id":"lib1","Name":"Movies","Type":"UserView","CollectionType":"movies","IsFolder":true}],"TotalRecordCount":1}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
    }

    private fun episodeJson(withSources: Boolean): String {
        val escapedPath = path.replace("\\", "\\\\")
        val sources = if (withSources) ""","MediaSources":[{"Id":"ms1","Path":"$escapedPath","Size":587443083,"RunTimeTicks":14200000000,"Protocol":"File"}]""" else ""
        return """{"Id":"ep1","Name":"The Goddess (Neptune) Of Planeptune","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,"SeriesName":"Hyperdimension Neptunia","SeriesId":"series1","SeasonId":"season1","Path":"$escapedPath","RunTimeTicks":14200000000$sources}"""
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun session() = JellyfinSession(baseUrl(), "Benny", "srv", "u1", "yarmi", "tok")

    @Test
    fun keyOfUsesNormalizedUrlNotServerId() {
        val lan = JellyfinSession("http://192.168.1.20:8096/", "Home", "srv", "u1", "sam", "tok")
        val remote = JellyfinSession("https://home.duckdns.org:8920", "Home", "srv", "u1", "sam", "tok")
        assertEquals("jellyfin:http://192.168.1.20:8096", JellyfinSource.keyOf(lan))
        assertEquals("jellyfin:https://home.duckdns.org:8920", JellyfinSource.keyOf(remote))
        assertNotEquals(JellyfinSource.keyOf(lan), JellyfinSource.keyOf(remote))
        assertEquals("jellyfin:srv", JellyfinSource.legacyKeyOf(lan))
    }

    @Test
    fun migrateStoredKeyRewritesLegacyServerIdKeys() {
        val lan = JellyfinSession("http://192.168.1.20:8096", "Home", "srv", "u1", "sam", "tok")
        assertEquals("jellyfin:http://192.168.1.20:8096", JellyfinSource.migrateStoredKey("jellyfin:srv", listOf(lan)))
        assertEquals(
            "jellyfin:http://192.168.1.20:8096/series-1",
            JellyfinSource.migrateStoredKey("jellyfin:srv/series-1", listOf(lan)),
        )
        assertEquals(
            "jellyfin:http://192.168.1.20:8096/series-1|1",
            JellyfinSource.migrateStoredKey("jellyfin:srv/series-1|1", listOf(lan)),
        )
        assertEquals("jellyfin:other", JellyfinSource.migrateStoredKey("jellyfin:other", listOf(lan)))
        assertEquals("plex:mid", JellyfinSource.migrateStoredKey("plex:mid", listOf(lan)))
    }

    @Test
    fun mediaSegmentsGiveTheIntroAndOutro() = runBlocking {
        val source = JellyfinSource(client, session())
        assertEquals(
            listOf(SkipSegment(SkipSegment.Kind.OPENING, 30.0, 120.0), SkipSegment(SkipSegment.Kind.ENDING, 1350.0, 1440.0)),
            source.skipSegments("ep1"),
        )
        val asked = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.first { it.path!!.startsWith("/MediaSegments") }
        assertEquals(listOf("Intro", "Outro"), asked.requestUrl!!.queryParameterValues("includeSegmentTypes"))
        // Servers before 10.10 have no media segments.
        assertEquals(emptyList<SkipSegment>(), source.skipSegments("old"))
    }

    @Test
    fun seasonEpisodesAreTheSeasonsPlayableEpisodes() = runBlocking {
        val episodes = JellyfinSource(client, session()).seasonEpisodes("ep1")
        assertEquals(listOf("ep1", "ep2"), episodes.map { it.itemId })
        assertEquals(listOf(1, 2), episodes.map { it.episode?.episode })
        assertEquals(1430.0, episodes[1].durationSeconds, 0.001)
        assertTrue(episodes[1].url.contains("Videos/ep2/stream") && episodes[1].url.contains("api_key=tok"))
        assertEquals(emptyList<Any>(), JellyfinSource(client, session()).seasonEpisodes("missing"))
    }

    @Test
    fun quickConnectFlowEmitsCodeThenSession() = runBlocking {
        val states = client.quickConnect(baseUrl(), pollIntervalMs = 10).toList()
        assertEquals(QuickConnectState.WaitingForApproval("123456"), states[0])
        val session = (states[1] as QuickConnectState.Authorized).session
        assertEquals("tok", session.accessToken)
        assertEquals("Benny", session.serverName)
        val initiate = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }
            .first { it.path!!.startsWith("/QuickConnect/Initiate") }
        assertEquals("POST", initiate.method)
        assertTrue(initiate.getHeader("Authorization")!!.contains("DeviceId=\"dev1\""))
    }

    @Test
    fun discoveryReplacesLoopbackAddressWithSender() {
        val sender = java.net.InetAddress.getByName("10.0.2.2")
        val fixed = JellyfinDiscovery.withReachableAddress(DiscoveredServer("http://127.0.0.1:8096", "id", "Benny"), sender)
        assertEquals("http://10.0.2.2:8096", fixed.address)
        val kept = JellyfinDiscovery.withReachableAddress(DiscoveredServer("http://192.168.1.5:8096", "id", "Benny"), sender)
        assertEquals("http://192.168.1.5:8096", kept.address)
    }

    @Test
    fun normalizesServerUrls() {
        assertEquals("http://10.0.2.2:8096", client.normalizeServerUrl("10.0.2.2"))
        assertEquals("http://10.0.2.2:8096", client.normalizeServerUrl("http://10.0.2.2:8096/"))
        assertEquals("https://jf.example.com", client.normalizeServerUrl("https://jf.example.com"))
        assertEquals("http://host:9000", client.normalizeServerUrl("host:9000"))
    }

    @Test
    fun resolvesExactFilenameAndBuildsStreamUrl() = runBlocking {
        val source = JellyfinSource(client, session())
        val result = source.resolveByFilename(fileName)
        result as ResolveResult.Found
        assertEquals(MatchKind.EXACT_FILENAME, result.matchedBy)
        assertEquals(fileName, result.playable.fileName)
        assertEquals(587443083L, result.playable.sizeBytes)
        assertEquals(1420.0, result.playable.durationSeconds, 0.001)
        assertTrue(result.playable.url.contains("/Videos/ep1/stream?static=true&mediaSourceId=ms1&api_key=tok"))
    }

    @Test
    fun resolvesNormalizedFilename() = runBlocking {
        val source = JellyfinSource(client, session())
        val result = source.resolveByFilename("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv")
        assertEquals(MatchKind.NORMALIZED_FILENAME, (result as ResolveResult.Found).matchedBy)
    }

    @Test
    fun fallsBackToParsedSearch() = runBlocking {
        val source = JellyfinSource(client, session())
        val result = source.resolveByFilename("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv")
        result as ResolveResult.Found
        assertEquals(MatchKind.PARSED_SEARCH, result.matchedBy)
        assertEquals("ep1", result.item.id)
    }

    @Test
    fun reportsNotFound() = runBlocking {
        val source = JellyfinSource(client, session())
        assertTrue(source.resolveByFilename("Something Else Entirely.mkv") is ResolveResult.NotFound)
    }

    @Test
    fun findExactOnlyAcceptsTheExactName() = runBlocking {
        val source = JellyfinSource(client, session())
        assertEquals("jellyfin:${baseUrl()}", source.findExact(fileName)?.playable?.sourceKey)
        assertNull(source.findExact("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv"))
        assertNull(source.findExact("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv"))
    }

    @Test
    fun reportsSessionProgressAndMarksPlayedWithFallback() = runBlocking {
        val source = JellyfinSource(client, session())
        source.playable(source.findExact(fileName)!!.item)
        source.reportPlayback(PlaybackReport("ep1", "sess1", ReportState.STARTED, 0.0, 1420.0))
        source.reportPlayback(PlaybackReport("ep1", "sess1", ReportState.PAUSED, 61.5, 1420.0))
        source.reportPlayback(PlaybackReport("ep1", "sess1", ReportState.STOPPED, 1300.0, 1420.0, markWatched = true))
        val sent = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.toList()
        val sessions = sent.filter { it.requestUrl!!.encodedPath.startsWith("/Sessions/Playing") }
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), sessions.map { it.requestUrl!!.encodedPath })
        val paused = sessions[1].body.readUtf8()
        assertTrue(paused, paused.contains("\"ItemId\":\"ep1\""))
        assertTrue(paused, paused.contains("\"MediaSourceId\":\"ms1\""))
        assertTrue(paused, paused.contains("\"PlaySessionId\":\"sess1\""))
        assertTrue(paused, paused.contains("\"PositionTicks\":615000000"))
        assertTrue(paused, paused.contains("\"IsPaused\":true"))
        val played = sent.filter { it.requestUrl!!.encodedPath.contains("PlayedItems") }
        assertEquals(listOf("/UserPlayedItems/ep1", "/Users/u1/PlayedItems/ep1"), played.map { it.requestUrl!!.encodedPath })
        assertEquals("u1", played[0].requestUrl!!.queryParameter("userId"))
    }

    @Test
    fun plainHttpUrlKeepsHostAndPort() {
        assertEquals(
            "http://yarmiplay.duckdns.org:8999",
            JellyfinClient.plainHttpUrl("https://yarmiplay.duckdns.org:8999"),
        )
        assertEquals(
            "http://yarmiplay.duckdns.org:8999",
            JellyfinClient.plainHttpUrl("https://yarmiplay.duckdns.org:8999/"),
        )
        assertNull(JellyfinClient.plainHttpUrl("http://yarmiplay.duckdns.org:8999"))
    }

    @Test
    fun isPlainHttpSslFailureRecognizesOpensslWording() {
        val ssl = SSLException("Unsupported or unrecognized SSL message")
        assertTrue(
            JellyfinClient.isPlainHttpSslFailure(
                MediaSourceException("Cannot reach yarmiplay.duckdns.org:8999 (${ssl.message})", cause = ssl),
            ),
        )
        assertTrue(JellyfinClient.isPlainHttpSslFailure(SSLException("error:0A00010B:SSL routines::wrong version number")))
        assertFalse(JellyfinClient.isPlainHttpSslFailure(MediaSourceException("Cannot reach host:8096 (timed out)")))
    }

    @Test
    fun loginOverHttpsToAPlainHttpPortFallsBackToHttp() {
        // A Noire-style port: TLS ClientHello gets a plain-HTTP reply (OpenSSL "Unsupported or unrecognized SSL
        // message"); the same port answers Jellyfin over cleartext HTTP.
        plainHttpPeer { http, https ->
            val session = client.login(https, "yarmi", "pw")
            assertEquals(http, session.serverUrl)
        }
    }

    @Test
    fun resolveReachableUrlKeepsHttpsWhenTheServerReallySpeaksTls() = runBlocking {
        // A plain-HTTP mock cannot prove TLS success; only that non-SSL failures do not flip the scheme.
        val err = runCatching { client.resolveReachableUrl("https://127.0.0.1:1") }.exceptionOrNull()
        assertTrue(err is MediaSourceException)
        assertFalse(JellyfinClient.isPlainHttpSslFailure(err!!))
        assertTrue("expected reach error: ${err.message}", err.message!!.contains("Cannot reach"))
    }

    @Test
    fun bothSchemesFailedNamesHttpsAndHttpReasons() {
        val ex = JellyfinClient.bothSchemesFailed(
            "https://yarmiplay.duckdns.org:8999",
            MediaSourceException("Cannot reach yarmiplay.duckdns.org:8999 (Unsupported or unrecognized SSL message)"),
            "http://yarmiplay.duckdns.org:8999",
            MediaSourceException("Cannot reach yarmiplay.duckdns.org:8999 (Connection refused)"),
        )
        assertTrue("msg=${ex.message}", ex.message!!.contains("HTTPS"))
        assertTrue("msg=${ex.message}", ex.message!!.contains("HTTP"))
        assertTrue("msg=${ex.message}", ex.message!!.contains("Unsupported or unrecognized SSL message"))
        assertTrue("msg=${ex.message}", ex.message!!.contains("Connection refused"))
    }

    @Test
    fun librariesOnHttpsPlainHttpPortRelocateAndNotify() {
        plainHttpPeer { http, https ->
            val httpsSession = JellyfinSession(https, "Benny", "srv", "u1", "yarmi", "tok")
            var relocated: JellyfinSession? = null
            val source = JellyfinSource(client, httpsSession) { previous, moved ->
                assertEquals(httpsSession.serverUrl, previous.serverUrl)
                relocated = moved
            }
            val libs = source.libraries()
            assertEquals(listOf("Movies"), libs.map { it.name })
            assertEquals(http, source.session.serverUrl)
            assertEquals(http, relocated!!.serverUrl)
            assertEquals(httpsSession.accessToken, relocated!!.accessToken)
            assertNotEquals(JellyfinSource.keyOf(httpsSession), JellyfinSource.keyOf(relocated!!))
        }
    }

    /**
     * Opens a loopback port that answers TLS with a plain-HTTP reply (so JSSE raises
     * "Unsupported or unrecognized SSL message") and answers cleartext HTTP with a tiny Jellyfin API.
     */
    private fun plainHttpPeer(block: suspend (http: String, https: String) -> Unit) = runBlocking {
        val listen = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val alive = AtomicBoolean(true)
        val pool: ExecutorService = Executors.newCachedThreadPool()
        pool.execute {
            while (alive.get()) {
                val socket = try {
                    listen.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { handlePlainHttpPeer(socket) }
            }
        }
        val http = "http://127.0.0.1:${listen.localPort}"
        val https = "https://127.0.0.1:${listen.localPort}"
        try {
            block(http, https)
        } finally {
            alive.set(false)
            runCatching { listen.close() }
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun handlePlainHttpPeer(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = BufferedInputStream(socket.getInputStream())
            input.mark(1)
            val first = input.read()
            if (first < 0) {
                socket.close()
                return
            }
            // TLS records start with 0x16 (Handshake). Reply as plain HTTP so the client gets the OpenSSL wording.
            if (first == 0x16) {
                socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                socket.close()
                return
            }
            input.reset()
            val requestLine = readHttpRequest(input)
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val body = when (path) {
                "/System/Info/Public" -> """{"ServerName":"Benny","Version":"10.11.6","Id":"srv"}"""
                "/Users/AuthenticateByName" ->
                    """{"User":{"Id":"u1","Name":"yarmi"},"AccessToken":"tok","ServerId":"srv"}"""
                "/Users/Me" -> """{"Id":"u1","Name":"yarmi"}"""
                "/UserViews" ->
                    """{"Items":[{"Id":"lib1","Name":"Movies","Type":"UserView","CollectionType":"movies","IsFolder":true}],"TotalRecordCount":1}"""
                else -> null
            }
            writeHttpResponse(socket.getOutputStream(), body)
        } catch (_: Exception) {
            // Peer closed or timed out.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHttpRequest(input: BufferedInputStream): String {
        val buf = ByteArray(8192)
        val out = StringBuilder()
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (out.contains("\r\n\r\n")) break
        }
        return out.toString().substringBefore("\r\n")
    }

    private fun writeHttpResponse(out: OutputStream, body: String?) {
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(),
            )
            out.write(bytes)
        }
        out.flush()
    }
}
