package com.yarmiplaytv.tracking

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.mal.MalAccount
import com.yarmiplaytv.media.mal.MalClient
import com.yarmiplaytv.media.mal.MalListStatus
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.media.mal.MalStatus
import com.yarmiplaytv.media.mal.MalTokens
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.ui.shared.MalSearchModel
import com.yarmiplaytv.ui.shared.listSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class MalServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val writes = CopyOnWriteArrayList<String>()
    private val detailFetches = CopyOnWriteArrayList<Int>()
    @Volatile private var failWrites = false
    /** Hero Academy: 12 episodes, and what's on the list for it. */
    @Volatile private var listStatus: String? = null
    private lateinit var scope: CoroutineScope
    private lateinit var settings: StateFlow<AppSettings>
    private val store by lazy { desktopSettingsStore(tmp.root.resolve("config"), scope) }
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PATCH" -> {
                        if (failWrites) return MockResponse().setResponseCode(503)
                        val body = request.body.readUtf8()
                        writes += body
                        val watched = Regex("num_watched_episodes=(\\d+)").find(body)?.groupValues?.get(1) ?: "0"
                        val status = Regex("status=(\\w+)").find(body)?.groupValues?.get(1) ?: "watching"
                        listStatus = """{"status":"$status","score":0,"num_episodes_watched":$watched,"is_rewatching":false}"""
                        json(listStatus!!)
                    }
                    request.method == "DELETE" -> { writes += "DELETE"; listStatus = null; MockResponse().setResponseCode(200) }
                    path == "/v2/anime" -> {
                        val list = listStatus?.takeIf { "my_list_status" in request.requestUrl!!.queryParameter("fields").orEmpty() }?.let { ""","my_list_status":$it""" }.orEmpty()
                        json("""{"data":[{"node":{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv"$list}}]}""")
                    }
                    path == "/v2/anime/100" -> {
                        detailFetches += 100
                        val list = listStatus?.let { ""","my_list_status":$it""" }.orEmpty()
                        json("""{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv","related_anime":[]$list}""")
                    }
                    path == "/v2/users/@me/animelist" -> json("""{"data":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking { store.saveMalAccount(MalAccount(MalTokens("access", "refresh", Long.MAX_VALUE), "ana")) }
        settings = store.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        runBlocking { withTimeout(5_000) { settings.first { it.mal != null } } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun service() = MalService(
        scope, store, settings,
        MalClient("client-id", authUrl = server.url("/").toString().trimEnd('/'), apiUrl = server.url("/v2").toString(), now = { clock }),
        now = { clock },
        today = { "2026-10-12" },
    )

    private fun ep(n: Int, name: String = "Hero Academy") = EpisodeInfo(name, 1, n, seriesKey = "jellyfin:x/series-1")

    private fun eventually(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(5_000) { while (!condition()) delay(20) } }.onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    @Test
    fun `progress only goes up, the first episode starts, the last completes, and a completed show is a rewatch`() {
        val none = MalService.progressUpdate(null, 1, 12, "2026-10-12")!!
        assertEquals(MalStatus.WATCHING, none.status)
        assertEquals(1, none.watchedEpisodes)
        assertEquals("2026-10-12", none.startDate)
        assertNull(none.finishDate)

        val watching = MalListStatus("watching", watchedEpisodes = 5, startDate = "2026-01-01")
        assertNull("already counted", MalService.progressUpdate(watching, 4, 12, "d"))
        assertEquals(6, MalService.progressUpdate(watching, 6, 12, "d")!!.watchedEpisodes)
        assertNull("start date kept", MalService.progressUpdate(watching, 6, 12, "d")!!.startDate)

        val last = MalService.progressUpdate(watching, 12, 12, "2026-10-12")!!
        assertEquals(MalStatus.COMPLETED, last.status)
        assertEquals("2026-10-12", last.finishDate)

        val planned = MalService.progressUpdate(MalListStatus("plan_to_watch"), 3, 0, "d")!!
        assertEquals(MalStatus.WATCHING to 3, planned.status to planned.watchedEpisodes)

        val completed = MalListStatus("completed", watchedEpisodes = 12, timesRewatched = 1)
        assertEquals(true, MalService.progressUpdate(completed, 1, 12, "d")!!.isRewatching)
        val done = MalService.progressUpdate(completed.copy(isRewatching = true, watchedEpisodes = 11), 12, 12, "d")!!
        assertEquals(false to 2, done.isRewatching to done.timesRewatched)
    }

    @Test
    fun `a sure match is updated when watched, and Undo takes it back`() {
        val mal = service()
        mal.playing(ep(1))
        eventually("the match") { mal.state.value is MalState.Matched }
        mal.watched(ep(1))
        eventually("the update") { writes.isNotEmpty() }
        assertEquals("status=watching&num_watched_episodes=1&start_date=2026-10-12", writes.single())
        eventually("the notice") { mal.notice.value?.undo != null }
        assertTrue(mal.notice.value!!.text, "Hero Academy · ep 1/12" in mal.notice.value!!.text)
        assertTrue(mal.canUndo.value)

        mal.undo()
        eventually("the undo") { writes.size == 2 }
        assertEquals("DELETE", writes[1])
        eventually("undo used") { !mal.canUndo.value }
    }

    @Test
    fun `asking first sends nothing until confirmed`() {
        runBlocking { store.saveMalPrefs(autoUpdate = true, askFirst = true) }
        eventually("the setting") { settings.value.malAskFirst }
        val mal = service()
        mal.playing(ep(2))
        eventually("the match") { mal.state.value is MalState.Matched }
        mal.watched(ep(2))
        eventually("the question") { mal.notice.value?.confirm != null }
        Thread.sleep(300)
        assertTrue(writes.isEmpty())
        mal.notice.value!!.confirm!!()
        eventually("the update") { writes.size == 1 }
    }

    @Test
    fun `an unsure match waits for the user's pick, which is remembered and applied`() {
        val mal = service()
        val info = ep(3, name = "Something Else Entirely")
        mal.playing(info)
        eventually("unsure") { mal.state.value is MalState.Unsure }
        mal.watched(info)
        eventually("the pick request") { mal.notice.value?.pick == true }
        assertTrue("nothing sent while unsure", writes.isEmpty())

        val candidate = (mal.state.value as MalState.Unsure).candidates.first { it.id == 100 }
        mal.choose(info, candidate, 3)
        eventually("the update") { writes.size == 1 }
        assertTrue(writes.single(), "num_watched_episodes=3" in writes.single())
        eventually("remembered") { settings.value.malMappings[MalService.mappingKey(info)]?.malId == 100 }
    }

    @Test
    fun `updates that can't be sent are queued and sent later`() {
        val mal = service()
        mal.playing(ep(1))
        eventually("the match") { mal.state.value is MalState.Matched }
        failWrites = true
        mal.watched(ep(1))
        eventually("queued") { settings.value.malQueue.isNotEmpty() }
        assertEquals(100, settings.value.malQueue.single().animeId)
        failWrites = false
        runBlocking { mal.flushQueue() }
        eventually("sent") { writes.size == 1 && settings.value.malQueue.isEmpty() }
    }

    @Test
    fun `signing out deletes everything kept for MyAnimeList`() {
        runBlocking {
            store.saveMalPrefs(autoUpdate = false, askFirst = true)
            store.saveMalMapping("a|1", com.yarmiplaytv.media.mal.MalMapping(100))
            store.saveMalIgnored(setOf("b"))
            store.saveMalQueue(listOf(com.yarmiplaytv.media.mal.MalQueued(100, com.yarmiplaytv.media.mal.MalListUpdate(score = 8))))
        }
        eventually("saved") { settings.value.malQueue.isNotEmpty() }
        val mal = service()
        mal.signOut()
        eventually("deleted") { settings.value.mal == null }
        val s = settings.value
        assertTrue(s.malMappings.isEmpty() && s.malIgnored.isEmpty() && s.malQueue.isEmpty())
        assertEquals(AppSettings().malAutoUpdate to AppSettings().malAskFirst, s.malAutoUpdate to s.malAskFirst)
        assertFalse(mal.signedIn)
        assertEquals(MalState.SignedOut, mal.state.value)
    }

    @Test
    fun `MyAnimeList content is kept at most 24 hours`() = runBlocking {
        val mal = service()
        mal.lookup(ep(1))
        val first = detailFetches.size
        mal.lookup(ep(1))
        assertEquals("cached", first, detailFetches.size)
        clock += 24 * 60 * 60 * 1000L
        mal.lookup(ep(1))
        assertTrue("fetched again after a day", detailFetches.size > first)
    }

    @Test
    fun `the tracker reports an episode once it's 90 percent watched`() {
        val mal = service()
        val player = FakePlayer()
        val nowPlaying = MutableStateFlow<NowPlaying?>(NowPlaying("Hero Academy · S01E04", "x.mkv", 1, 100.0, "url-4", episode = ep(4)))
        MalTracker(scope, player, nowPlaying, mal)
        eventually("the match") { mal.state.value is MalState.Matched }
        player.state.value = PlaybackState(url = "url-4", fileLoaded = true, duration = 100.0, position = 50.0)
        Thread.sleep(300)
        assertTrue(writes.isEmpty())
        player.state.value = PlaybackState(url = "url-4", fileLoaded = true, duration = 100.0, position = 91.0)
        eventually("the update") { writes.size == 1 }
        player.state.value = PlaybackState(url = "url-4", fileLoaded = true, duration = 100.0, position = 99.0)
        Thread.sleep(300)
        assertEquals("once per file", 1, writes.size)
    }

    @Test
    fun `the loopback callback takes the code with the right state only`() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val callback = MalCallbackServer(port)
        val code = scope.async(Dispatchers.IO) { callback.await("good-state", timeoutMs = 10_000) }
        val http = OkHttpClient()
        fun get(query: String): Int {
            repeat(50) {
                runCatching { return http.newCall(Request.Builder().url("http://127.0.0.1:$port/mal-callback?$query").build()).execute().use { it.code } }
                Thread.sleep(50)
            }
            error("no listener")
        }
        assertEquals(400, get("code=x&state=bad-state"))
        assertEquals(200, get("code=the%20code&state=good-state"))
        assertEquals("the code", code.await())
    }

    @Test
    fun `the search finds shows with your entry, and adds, changes and removes them`() {
        val mal = service()
        val model = MalSearchModel(mal, { true }, scope)
        model.query = "He"
        model.search()
        assertEquals("Type at least 3 letters", model.error)

        model.query = "Hero Academy"
        model.search()
        eventually("the results") { model.results != null && !model.busy }
        val found = model.results!!.single()
        assertNull("not on the list yet", found.myListStatus)
        assertEquals("Not on your list", found.listSummary())

        model.update(found, MalListUpdate(status = MalStatus.PLAN_TO_WATCH))
        eventually("added") { model.results!!.single().myListStatus?.statusValue == MalStatus.PLAN_TO_WATCH }
        assertEquals(1, model.changes)
        assertEquals("status=plan_to_watch", writes.last())

        model.watchedOneMore(model.results!!.single())
        eventually("one watched") { model.results!!.single().myListStatus?.watchedEpisodes == 1 }
        assertEquals("status=watching&num_watched_episodes=1&start_date=2026-10-12", writes.last().replace(Regex("start_date=[0-9-]+"), "start_date=2026-10-12"))

        // Searching again shows the entry as MyAnimeList has it.
        model.search()
        eventually("searched again") { !model.busy && model.results!!.single().myListStatus?.watchedEpisodes == 1 }

        model.remove(model.results!!.single())
        eventually("removed") { model.results!!.single().myListStatus == null }
        assertEquals("DELETE", writes.last())
        assertEquals(3, model.changes)
    }
}
