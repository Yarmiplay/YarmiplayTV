package com.yarmiplaytv.tracking

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.media.mal.MalClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The MyAnimeList screen's Play: which of an entry's episodes the library has. */
class MalEpisodesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v2/anime" -> json("""{"data":[{"node":{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv"}}]}""")
                "/v2/anime/100" -> json("""{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv","related_anime":[]}""")
                else -> MockResponse().setResponseCode(404)
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

    @Test
    fun `the media folders' episodes of the entry, by its episode numbers, and nothing of other shows`() = runBlocking {
        val store = desktopSettingsStore(tmp.root.resolve("config"), scope)
        val settings = store.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        val mal = MalService(scope, store, settings, MalClient("client-id", apiUrl = server.url("/v2").toString()))
        val files = MutableStateFlow(
            listOf(
                LocalFile("Hero Academy - 04.mkv", 1, "file:///a/Hero%20Academy%20-%2004.mkv", directory = "Hero Academy"),
                LocalFile("Hero Academy - 03.mkv", 1, "file:///a/Hero%20Academy%20-%2003.mkv", directory = "Hero Academy"),
                LocalFile("Cooking Show - 01.mkv", 1, "file:///a/Cooking%20Show%20-%2001.mkv"),
            ),
        )
        val episodes = MalEpisodes(mal, MutableStateFlow<MediaSource?>(null), files)
        val found = episodes.of(MalAnime(100, "Hero Academy", numEpisodes = 12))
        assertEquals(listOf(3, 4), found.map { it.number })
        assertEquals("file:///a/Hero%20Academy%20-%2003.mkv", found.first().localUri)
    }
}
