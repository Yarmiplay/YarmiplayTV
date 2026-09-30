package com.syncplaytv.media.jellyfin

import com.syncplaytv.media.MatchKind
import com.syncplaytv.media.ResolveResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        return """{"Id":"ep1","Name":"The Goddess (Neptune) Of Planeptune","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,"SeriesName":"Hyperdimension Neptunia","SeriesId":"series1","Path":"$escapedPath","RunTimeTicks":14200000000$sources}"""
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun session() = JellyfinSession(baseUrl(), "Benny", "srv", "u1", "yarmi", "tok")

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
}
