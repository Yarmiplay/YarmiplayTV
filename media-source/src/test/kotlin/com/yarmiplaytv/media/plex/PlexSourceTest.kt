package com.yarmiplaytv.media.plex

import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.PlaybackReport
import com.yarmiplaytv.media.ReportState
import com.yarmiplaytv.media.ResolveResult
import com.yarmiplaytv.media.jellyfin.ClientInfo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlexSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlexClient
    private var pinPolls = 0
    private var revoked = false

    private val fileName = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv"
    private val path = "/media/Neptunia/Season 1/$fileName"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (revoked && url.encodedPath == "/identity") return MockResponse().setResponseCode(401)
                if (url.encodedPath == "/notplex/identity") return MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Router login</html>")
                val body = when (url.encodedPath) {
                    "/api/v2/pins" -> """{"id":42,"code":"ABCD","expiresIn":900}"""
                    "/api/v2/pins/42" -> if (++pinPolls >= 2) """{"id":42,"code":"ABCD","authToken":"acct"}""" else """{"id":42,"code":"ABCD"}"""
                    "/api/v2/user" -> """{"username":"yarmi"}"""
                    "/api/v2/resources" -> """[
                        {"name":"Player","provides":"player","clientIdentifier":"p1"},
                        {"name":"Benny","provides":"server","clientIdentifier":"mid","accessToken":"srvtok","owned":true,
                         "connections":[{"uri":"http://127.0.0.1:1","local":true},{"uri":"${baseUrl()}","local":false}]}
                    ]"""
                    "/identity" -> """{"MediaContainer":{"machineIdentifier":"mid"}}"""
                    "/library/sections" -> """{"MediaContainer":{"Directory":[
                        {"key":"1","title":"Anime","type":"show"},
                        {"key":"2","title":"Movies","type":"movie"},
                        {"key":"3","title":"Music","type":"artist"}]}}"""
                    "/library/sections/1/all" ->
                        if (url.queryParameter("type") == "4") """{"MediaContainer":{"totalSize":1,"Metadata":[${episodeJson()}]}}"""
                        else """{"MediaContainer":{"Metadata":[{"ratingKey":"show1","type":"show","title":"Hyperdimension Neptunia","leafCount":12,"viewedLeafCount":12}]}}"""
                    "/library/sections/2/all" -> """{"MediaContainer":{"totalSize":1,"Metadata":[
                        {"ratingKey":"m1","type":"movie","title":"Other","year":2020,"Media":[{"Part":[{"key":"/library/parts/9/1/file.mkv","file":"/media/Other (2020).mkv","size":5}]}]}]}}"""
                    "/library/metadata/ep1" -> """{"MediaContainer":{"Metadata":[${episodeJson()}]}}"""
                    "/library/metadata/show1/allLeaves" -> """{"MediaContainer":{"Metadata":[${episodeJson()}]}}"""
                    "/hubs/search" -> """{"MediaContainer":{"Hub":[{"type":"show","Metadata":[{"ratingKey":"show1","type":"show","title":"Hyperdimension Neptunia"}]}]}}"""
                    "/library/onDeck" -> """{"MediaContainer":{"Metadata":[${episodeJson()}]}}"""
                    "/library/recentlyAdded" -> """{"MediaContainer":{"Metadata":[${episodeJson()},{"ratingKey":"a1","type":"album","title":"Music"}]}}"""
                    "/:/timeline", "/:/scrobble" -> ""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        client = PlexClient(ClientInfo(deviceName = "Test TV", deviceId = "dev1", version = "0.1"), plexTvUrl = baseUrl())
    }

    private fun episodeJson() = """{"ratingKey":"ep1","type":"episode","title":"The Goddess (Neptune) Of Planeptune","index":1,"parentIndex":1,
        "grandparentTitle":"Hyperdimension Neptunia","grandparentRatingKey":"show1","duration":1420000,"viewOffset":60000,
        "Media":[{"duration":1420000,"Part":[{"key":"/library/parts/7/1700000000/file.mkv","file":"$path","size":587443083}]}]}"""

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun session() = PlexSession(baseUrl(), "Benny", "mid", "yarmi", "srvtok", "acct")

    private fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.toList()

    @Test
    fun linkFlowEmitsCodeThenAccountToken() = runBlocking {
        val states = client.link(pollIntervalMs = 10).toList()
        assertEquals(PlexLinkState.WaitingForLink("ABCD"), states[0])
        assertEquals(PlexLinkState.Linked("acct", "yarmi"), states[1])
        val pin = requests().first { it.path!!.startsWith("/api/v2/pins?") }
        assertEquals("POST", pin.method)
        assertEquals("dev1", pin.getHeader("X-Plex-Client-Identifier"))
    }

    @Test
    fun listsOnlyServersAndConnectsToReachableAddress() = runBlocking {
        val servers = client.servers("acct")
        assertEquals(listOf("Benny"), servers.map { it.name })
        val session = client.connect(servers.single(), "acct", "yarmi")
        assertEquals(baseUrl(), session.serverUrl)
        assertEquals("srvtok", session.serverToken)
        assertEquals("acct", session.accountToken)
    }

    @Test
    fun connectSkipsAddressesThatAreNotPlexAndExplainsFailures() = runBlocking {
        val html = PlexResourceConnection("${baseUrl()}/notplex", local = true)
        val dead = PlexResourceConnection("http://127.0.0.1:1", local = true)
        val good = PlexResourceConnection(baseUrl())
        val server = PlexServer("Benny", "mid", "srvtok", owned = true, connections = listOf(html, dead, good))
        assertEquals(baseUrl(), client.connect(server, "acct", "yarmi").serverUrl)

        val error = runCatching { client.connect(server.copy(connections = listOf(html, dead)), "acct", "yarmi") }.exceptionOrNull()
        val lines = error!!.message!!.lines()
        assertEquals("Can't reach Benny; is it online?", lines[0])
        assertTrue(lines[1], lines[1].startsWith("${html.uri}: not a Plex server"))
        assertTrue(lines[2], lines[2].startsWith("${dead.uri}: Cannot reach 127.0.0.1:1"))
    }

    @Test
    fun refreshKeepsWorkingSessionAndDropsRevokedOne() = runBlocking {
        assertEquals(session(), client.refresh(session()))
        revoked = true
        assertNull(client.refresh(session()))
    }

    @Test
    fun refreshFindsServerAtNewAddress() = runBlocking {
        val moved = session().copy(serverUrl = "http://127.0.0.1:1")
        assertEquals(baseUrl(), client.refresh(moved)?.serverUrl)
    }

    @Test
    fun browsesVideoLibrariesAndChildren() = runBlocking {
        val source = PlexSource(client, session())
        val libs = source.libraries()
        assertEquals(listOf("Anime", "Movies"), libs.map { it.name })
        assertEquals("tvshows", libs[0].collectionType)
        assertEquals("plex:mid", source.key)
        assertTrue(libs.all { it.sourceKey == source.key })
        val shows = source.children(libs[0])
        assertEquals(MediaItemType.SERIES, shows.single().type)
        assertTrue(shows.single().played)
    }

    @Test
    fun recentKeepsVideosOnceEach() = runBlocking {
        val recent = PlexSource(client, session()).recent(10)
        assertEquals(listOf("ep1"), recent.map { it.id })
        assertEquals("Hyperdimension Neptunia", recent.single().seriesName)
    }

    @Test
    fun findExactBuildsDirectPlayUrlWithOriginalName() = runBlocking {
        val found = PlexSource(client, session()).findExact(fileName)
        assertNotNull(found)
        found!!
        assertEquals(MatchKind.EXACT_FILENAME, found.matchedBy)
        assertEquals(fileName, found.playable.fileName)
        assertEquals(587443083L, found.playable.sizeBytes)
        assertEquals(1420.0, found.playable.durationSeconds, 0.001)
        assertEquals("plex:mid", found.playable.sourceKey)
        assertEquals("${baseUrl()}/library/parts/7/1700000000/file.mkv?X-Plex-Token=srvtok", found.playable.url)
    }

    @Test
    fun findExactIgnoresLooseMatches() = runBlocking {
        val source = PlexSource(client, session())
        assertNull(source.findExact("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv"))
        assertNull(source.findExact("Something Else.mkv"))
    }

    @Test
    fun resolvesNormalizedThenParsed() = runBlocking {
        val source = PlexSource(client, session())
        val normalized = source.resolveByFilename("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv")
        assertEquals(MatchKind.NORMALIZED_FILENAME, (normalized as ResolveResult.Found).matchedBy)
        val parsed = source.resolveByFilename("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv")
        parsed as ResolveResult.Found
        assertEquals(MatchKind.PARSED_SEARCH, parsed.matchedBy)
        assertEquals("ep1", parsed.item.id)
        assertTrue(source.resolveByFilename("Something Else Entirely.mkv") is ResolveResult.NotFound)
    }

    @Test
    fun reportsTimelineAndScrobble() = runBlocking {
        val source = PlexSource(client, session())
        source.reportPlayback(PlaybackReport("ep1", "sess1", ReportState.PAUSED, 61.5, 1420.0))
        source.reportPlayback(PlaybackReport("ep1", "sess1", ReportState.PLAYING, 1300.0, 1420.0, markWatched = true))
        val sent = requests()
        val timelines = sent.filter { it.requestUrl!!.encodedPath == "/:/timeline" }
        assertEquals(listOf("paused", "playing"), timelines.map { it.requestUrl!!.queryParameter("state") })
        assertEquals("61500", timelines[0].requestUrl!!.queryParameter("time"))
        assertEquals("1420000", timelines[0].requestUrl!!.queryParameter("duration"))
        assertEquals("sess1", timelines[0].getHeader("X-Plex-Session-Identifier"))
        assertEquals("srvtok", timelines[0].getHeader("X-Plex-Token"))
        val scrobble = sent.single { it.requestUrl!!.encodedPath == "/:/scrobble" }
        assertEquals("ep1", scrobble.requestUrl!!.queryParameter("key"))
    }
}
