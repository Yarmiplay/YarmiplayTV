package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.media.mal.MalAccount
import com.yarmiplaytv.media.mal.MalClient
import com.yarmiplaytv.media.mal.MalTokens
import com.yarmiplaytv.tracking.MalService
import com.yarmiplaytv.ui.mobile.MalShowSearch
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.shared.MalSearchModel
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** The MyAnimeList search: find a show, add it to the list, rate it, count an episode and take it off again. */
@OptIn(ExperimentalTestApi::class)
class MalSearchUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writes = CopyOnWriteArrayList<String>()
    /** Hero Academy's entry on the list, as MyAnimeList's JSON; null when it isn't on it. */
    @Volatile private var entry: String? = null
    private lateinit var model: MalSearchModel

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PATCH" -> {
                        val form = request.body.readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                        writes += form.keys.sorted().joinToString(",")
                        val now = entry?.let { Regex("\"(\\w+)\":\"?([\\w]+)\"?").findAll(it).associate { m -> m.groupValues[1] to m.groupValues[2] } }.orEmpty()
                        val status = form["status"] ?: now["status"] ?: "plan_to_watch"
                        val score = form["score"] ?: now["score"] ?: "0"
                        val watched = form["num_watched_episodes"] ?: now["num_episodes_watched"] ?: "0"
                        entry = """{"status":"$status","score":$score,"num_episodes_watched":$watched,"is_rewatching":false}"""
                        json(entry!!)
                    }
                    request.method == "DELETE" -> {
                        writes += "DELETE"
                        entry = null
                        MockResponse().setResponseCode(200)
                    }
                    path == "/v2/anime" -> json("""{"data":[{"node":${anime(withEntry = "my_list_status" in request.requestUrl!!.queryParameter("fields").orEmpty())}}]}""")
                    path == "/v2/anime/100" -> json(anime(withEntry = true))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val store = desktopSettingsStore(tmp.root, scope)
        runBlocking { store.saveMalAccount(MalAccount(MalTokens("access", "refresh", Long.MAX_VALUE), "ana")) }
        val settings = store.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        runBlocking { withTimeout(5_000) { settings.first { it.mal != null } } }
        val mal = MalService(scope, store, settings, MalClient("client-id", apiUrl = server.url("/v2").toString()))
        model = MalSearchModel(mal, { true }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun anime(withEntry: Boolean): String {
        val list = entry?.takeIf { withEntry }?.let { ""","my_list_status":$it""" }.orEmpty()
        return """{"id":100,"title":"Hero Academy","num_episodes":12,"media_type":"tv","start_date":"2024-04-06"$list}"""
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun ComposeUiTest.listSays(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag("mal_found_list_100").assertTextEquals(text) }.isSuccess
        }
    }

    @Test
    fun `a show found is added, rated, counted and taken off the list`() = runComposeUiTest {
        setContent {
            // On a surface, as on the MyAnimeList screen and in the player's tab.
            MobileTheme { Surface(color = AppColors.Surface, modifier = Modifier.size(420.dp, 560.dp)) { Box(Modifier.padding(16.dp)) { MalShowSearch(model) } } }
        }
        onNodeWithTag("mal_find").performTextInput("Hero Academy")
        onNodeWithTag("mal_find_go").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("mal_found_100").fetchSemanticsNodes().isNotEmpty() }
        listSays("Not on your list")

        onNodeWithTag("mal_found_add_100").performClick()
        onNodeWithText("Watching").performClick()
        listSays("Watching · 0 of 12 watched")

        onNodeWithTag("mal_found_score_100").performClick()
        onNodeWithText("8").performClick()
        listSays("Watching · 0 of 12 watched · 8/10")

        onNodeWithTag("mal_found_plus_100").performClick()
        listSays("Watching · 1 of 12 watched · 8/10")
        screenshot()

        onNodeWithTag("mal_found_more_100").performClick()
        onNodeWithTag("mal_found_remove_100").performClick()
        listSays("Not on your list")
        assertEquals(listOf("status", "score", "num_watched_episodes,start_date,status", "DELETE"), writes.toList())
    }

    private fun ComposeUiTest.screenshot() {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/mal_search.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
