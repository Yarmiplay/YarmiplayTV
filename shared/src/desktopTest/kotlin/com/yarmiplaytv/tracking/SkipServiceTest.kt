package com.yarmiplaytv.tracking

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.aniskip.AniSkipClient
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.media.mal.MalClient
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.sync.FakeMediaSource
import com.yarmiplaytv.sync.JF
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.RoomSkip
import com.yarmiplaytv.sync.ServerCopy
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** Openings and endings: skipped by themselves, offered with a Skip button, or left alone. */
class SkipServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var scope: CoroutineScope
    private val player = SeekPlayer()
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val room = MutableStateFlow(RoomState())
    private val roomSkip = MutableStateFlow(RoomSkip())
    private val settings = MutableStateFlow(AppSettings())
    private val notes = CopyOnWriteArrayList<String>()
    private val servers = MutableStateFlow<List<MediaSource>>(emptyList())
    private val aniSkipAsks = CopyOnWriteArrayList<String>()

    /** Hero Academy (MyAnimeList 100), episode 3 of a 1440 s file: opening 28.7–118.7, ending 1350–1440. */
    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/v2/anime/100" && request.getHeader("X-MAL-CLIENT-ID") == "client-id" ->
                        json("""{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv","related_anime":[]}""")
                    path == "/skip/skip-times/100/3" -> {
                        aniSkipAsks += request.requestUrl!!.queryParameter("episodeLength")!!
                        json(
                            """{"found":true,"results":[
                            |{"interval":{"startTime":28.7,"endTime":118.7},"skipType":"op","episodeLength":1440},
                            |{"interval":{"startTime":1350,"endTime":1440},"skipType":"ed","episodeLength":1440}]}""".trimMargin(),
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun service(): SkipService {
        val store = desktopSettingsStore(tmp.root.resolve("config"), scope)
        val stored = store.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        // Signed out: MyAnimeList is asked with the Client ID alone.
        val mal = MalService(scope, store, stored, MalClient("client-id", apiUrl = server.url("/v2").toString()))
        return SkipService(
            scope, player, nowPlaying, mal, AniSkipClient(apiUrl = server.url("/skip").toString()), servers, room, roomSkip, settings,
            notify = { notes += it },
        )
    }

    private fun play(chapters: List<Chapter> = emptyList()): SkipService {
        val s = service()
        val url = "file:///anime/Hero Academy - 03.mkv"
        nowPlaying.value = NowPlaying("Hero Academy 3", "Hero Academy - 03.mkv", 0, 1440.0, url, episode = EpisodeInfo("Hero Academy", 1, 3, seriesKey = "file/hero academy", malId = 100))
        player.state.value = PlaybackState(url = url, fileLoaded = true, duration = 1440.0, paused = false, position = 0.0, chapters = chapters)
        eventually("the opening and ending") { s.segments.value.size == 2 }
        return s
    }

    private fun at(position: Double) {
        player.state.value = player.state.value.copy(position = position)
    }

    private fun eventually(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(5_000) { while (!condition()) delay(20) } }.onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    @Test
    fun `the opening and ending come from AniSkip with the file's length, without a MyAnimeList account`() {
        val s = play()
        assertEquals(SkipSegment.Kind.OPENING, s.segments.value.first().kind)
        assertEquals(listOf("1440.000"), aniSkipAsks)
    }

    @Test
    fun `the file's own chapters win over AniSkip`() {
        val s = play(listOf(Chapter("Prologue", 0.0), Chapter("Opening", 60.0), Chapter("Part A", 150.0), Chapter("ED", 1340.0), Chapter("Preview", 1425.0)))
        eventually("the chapters' opening") { s.segments.value.firstOrNull()?.start == 60.0 }
        assertEquals(
            listOf(SkipSegment(SkipSegment.Kind.OPENING, 60.0, 150.0), SkipSegment(SkipSegment.Kind.ENDING, 1340.0, 1425.0)),
            s.segments.value,
        )
    }

    @Test
    fun `a media server's markers count for a show AniSkip doesn't know`() {
        servers.value = listOf(
            FakeMediaSource(JF, markers = mapOf("ep7" to listOf(SkipSegment(SkipSegment.Kind.OPENING, 5.0, 95.0), SkipSegment(SkipSegment.Kind.ENDING, 2500.0, 2590.0)))),
        )
        val s = service()
        val url = "http://jellyfin/ep7.mkv"
        // Not an episode MyAnimeList can name: only the server knows.
        nowPlaying.value = NowPlaying("Brand New Show 7", "ep7.mkv", 0, 2600.0, url, copies = listOf(ServerCopy(JF, "ep7")))
        player.state.value = PlaybackState(url = url, fileLoaded = true, duration = 2600.0, paused = false, position = 0.0)
        eventually("the server's markers") { s.segments.value.size == 2 }
        at(6.0)
        eventually("the button") { s.prompt.value?.label == "Skip opening" }
        assertTrue("AniSkip wasn't asked", aniSkipAsks.isEmpty())
    }

    @Test
    fun `a Skip button shows for 3 seconds as the opening starts, and skips to its end`() {
        val s = play()
        at(10.0)
        assertNull(s.prompt.value)
        at(29.5)
        eventually("the button") { s.prompt.value?.label == "Skip opening" }
        s.skip()
        assertEquals(listOf(118.7), player.seeks)
        assertNull(s.prompt.value)

        // Back into the opening: it plays, no second button.
        at(40.0)
        at(41.0)
        runBlocking { delay(200) }
        assertNull(s.prompt.value)
    }

    @Test
    fun `the button goes after 3 seconds`() {
        val s = play()
        at(29.0)
        eventually("the button") { s.prompt.value != null }
        runBlocking { delay(SkipService.PROMPT_MILLIS + 300) }
        assertNull(s.prompt.value)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `with the button turned off nothing shows`() {
        settings.value = AppSettings(playback = PlaybackPrefs(showSkipButton = false))
        val s = play()
        at(29.0)
        runBlocking { delay(300) }
        assertNull(s.prompt.value)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `alone with auto-skip on, the opening is skipped by itself once`() {
        settings.value = AppSettings(playback = PlaybackPrefs(autoSkipAlone = true))
        val s = play()
        at(29.0)
        eventually("the skip") { player.seeks == listOf(118.7) }
        eventually("the note") { notes == listOf("Skipped the opening") }
        assertNull(s.prompt.value)
        at(30.0)
        runBlocking { delay(300) }
        assertEquals("seeking back into it plays it", listOf(118.7), player.seeks)
    }

    @Test
    fun `in a room the room's setting decides, and an ending that runs to the end stops just before it`() {
        settings.value = AppSettings(playback = PlaybackPrefs(autoSkipAlone = false))
        roomSkip.value = RoomSkip(inRoom = true, on = true, shared = true, canChange = true)
        play()
        at(1351.0)
        eventually("the skip") { player.seeks == listOf(1439.5) }
    }

    @Test
    fun `a viewer who follows a managed room's operators neither skips nor gets the button`() {
        roomSkip.value = RoomSkip(inRoom = true, on = true, shared = true, canChange = false)
        room.value = RoomState(
            status = ConnectionStatus.CONNECTED, username = "bob", room = "+anime:ABCDEF123456",
            users = listOf(RoomUser("bob", "+anime:ABCDEF123456", null, null), RoomUser("ana", "+anime:ABCDEF123456", null, null, isController = true)),
            serverFeatures = mapOf("managedRooms" to true),
        )
        assertTrue(room.value.followsOperators)
        val s = play()
        at(29.0)
        runBlocking { delay(300) }
        assertTrue(player.seeks.isEmpty())
        assertNull(s.prompt.value)
    }

    private class SeekPlayer : Player {
        override val state = MutableStateFlow(PlaybackState())
        override val tracks = MutableStateFlow<List<Track>>(emptyList())
        override val events = MutableSharedFlow<PlayerEvent>()
        val seeks = CopyOnWriteArrayList<Double>()
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) = Unit
        override fun stop() = Unit
        override fun setPaused(paused: Boolean) = Unit
        override fun seek(position: Double) {
            seeks += position
            state.value = state.value.copy(position = position)
        }
        override fun seekRelative(offset: Double) = seek(state.value.position + offset)
        override fun setSpeed(speed: Double) = Unit
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun showText(text: String, durationMs: Int) = Unit
        override val isFileLoaded get() = state.value.fileLoaded
        override val isPaused get() = state.value.paused
        override val duration get() = state.value.duration
        override fun currentPosition() = state.value.position
    }
}
