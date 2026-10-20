package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.ui.mobile.MobileRoomScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import java.io.File
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The "Join a Syncplay room" screen on desktop: rooms you know beside the form; phones stack them. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RoomScreenUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private var server: ServerSocket? = null

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = desktopSettingsStore(tmp.root, storeScope)
        runBlocking {
            store.saveRoom(SavedRoom("myname.duckdns.org", 8999, true, "Sam", "anime-club", label = "Anime night", lastUsedMillis = 2))
            store.saveRoomVisit(SavedRoom("syncplay.pl", 8997, true, "Sam", "movie-night", lastUsedMillis = 1))
        }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
    }

    @After
    fun tearDown() {
        if (::app.isInitialized) runBlocking(main) { app.sync.disconnect() }
        server?.close()
        // Join before resetMain: leaving Main work running races on Windows/macOS CI.
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    private fun ComposeUiTest.show(width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.SyncplayConnect))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, height.dp)) { MobileRoomScreen(app, nav) } }
            }
        }
        waitForIdle()
    }

    @Test
    fun `wide windows show the known rooms beside the form, and a known room fills it`() = runComposeUiTest {
        show(1280, 800)
        // Saved rooms arrive on a flow; waitForIdle alone can race on Windows CI.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("join_two_panes").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithTag("known_room_anime-club").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("join_two_panes").assertIsDisplayed()
        onNodeWithTag("known_room_anime-club").assertIsDisplayed()
        onNodeWithTag("known_room_movie-night").assertIsDisplayed()
        screenshot("join_wide")

        onNodeWithTag("public_8996").performClick()
        onNodeWithTag("field_host").assertTextEquals("Server", "syncplay.pl", includeEditableText = true)
        onNodeWithTag("field_port").assertTextEquals("Port", "8996", includeEditableText = true)

        onNodeWithTag("more_options").performClick()
        onNodeWithTag("field_server_password").assertIsDisplayed()
    }

    @Test
    fun `narrow windows stack the rooms above the form`() = runComposeUiTest {
        show(420, 900)
        onNodeWithTag("join_card").assertIsDisplayed()
        onNodeWithTag("join_two_panes").assertDoesNotExist()
        screenshot("join_narrow")
    }

    @Test
    fun `a small phone shows the join form whole`() = runComposeUiTest {
        show(360, 760)
        onNodeWithTag("join_card").assertIsDisplayed()
        screenshot("join_phone")
    }

    @Test
    fun `in a room, wide windows show the playlist, the chat and the room side by side`() = runComposeUiTest {
        joinRoom()
        show(1280, 800)
        onNodeWithTag("room_columns_3").assertIsDisplayed()
        screenshot("room_wide")
    }

    @Test
    fun `in a room, middle widths use two columns and phones one`() = runComposeUiTest {
        joinRoom()
        var width by mutableStateOf(800)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.SyncplayConnect))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, 800.dp)) { MobileRoomScreen(app, nav) } }
            }
        }
        onNodeWithTag("room_columns_2").assertExists()
        width = 420
        waitForIdle()
        onNodeWithTag("room_columns_1").assertExists()
    }

    /** Joins a room on a server that answers Hello and nothing else. */
    private fun joinRoom() {
        val socket = ServerSocket(0).also { server = it }
        thread(isDaemon = true) {
            runCatching {
                val client = socket.accept()
                val reader = client.getInputStream().bufferedReader()
                val writer = PrintWriter(client.getOutputStream().writer(), true)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("\"Hello\"")) {
                        writer.print(
                            """{"Hello":{"username":"Sam","room":{"name":"movie-night"},"version":"1.2.255","realversion":"1.7.0",""" +
                                """"features":{"chat":true,"readiness":true,"sharedPlaylists":true},"motd":""}}""" + "\r\n",
                        )
                        writer.flush()
                    }
                }
            }
        }
        runBlocking(main) { app.sync.connect(SyncplayConfig("127.0.0.1", socket.localPort, "Sam", "movie-night", useTls = false)) }
        runBlocking {
            withTimeout(10_000) { while (app.sync.room.value.status != ConnectionStatus.CONNECTED) delay(20) }
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
