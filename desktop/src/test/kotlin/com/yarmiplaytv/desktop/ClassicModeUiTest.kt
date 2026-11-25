package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.yarmiplaytv.ui.mobile.ClassicRoomWindow
import com.yarmiplaytv.ui.mobile.ClassicWindows
import com.yarmiplaytv.ui.mobile.MobileTheme
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Syncplay-style windows: the join screen, the room window, and the way back to the regular view. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ClassicModeUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private var server: ServerSocket? = null
    private var regularView = false

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = desktopSettingsStore(tmp.root, storeScope)
        runBlocking {
            store.saveClassicMode(true)
            store.saveRoom(SavedRoom("myname.duckdns.org", 8999, true, "Sam", "anime-club", label = "Anime night", lastUsedMillis = 2))
        }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        ClassicWindows.nav.popTo(Screen.Room)
        ClassicWindows.playerOpen = false
        ClassicWindows.roomHidden = false
        ClassicWindows.showMoreSettings = false
    }

    @After
    fun tearDown() {
        if (::app.isInitialized) runBlocking(main) { app.sync.disconnect() }
        server?.close()
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
        ClassicWindows.nav.popTo(Screen.Room)
        ClassicWindows.playerOpen = false
        ClassicWindows.roomHidden = false
        ClassicWindows.showMoreSettings = false
    }

    private fun ComposeUiTest.show(width: Int = 1100, height: Int = 760) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, height.dp)) { ClassicRoomWindow(app, onRegularView = { regularView = true }) } }
            }
        }
        waitForIdle()
    }

    @Test
    fun `closing either window quits by default, like Syncplay`() {
        assertTrue("on unless turned off", app.settings.value.classicCloseTogether)
        ClassicWindows.playerOpen = true
        assertTrue("closing the room window quits", ClassicWindows.closeRoom(closeTogether = true))
        assertFalse(ClassicWindows.roomHidden)
        assertTrue("closing the player window quits", ClassicWindows.closePlayer(closeTogether = true))
    }

    @Test
    fun `with closing together off, each window hides and the other brings it back`() {
        ClassicWindows.playerOpen = true
        assertFalse("the player window closes alone", ClassicWindows.closePlayer(closeTogether = false))
        assertFalse(ClassicWindows.playerOpen)
        ClassicWindows.showPlayer()

        assertFalse("the room window hides while the player stays", ClassicWindows.closeRoom(closeTogether = false))
        assertTrue(ClassicWindows.roomHidden)
        assertTrue("closing the last window quits", ClassicWindows.closePlayer(closeTogether = false))
        ClassicWindows.showRoom()
        assertFalse(ClassicWindows.roomHidden)

        ClassicWindows.playerOpen = false
        assertTrue("without the player window, closing the room quits", ClassicWindows.closeRoom(closeTogether = false))
    }

    @Test
    fun `the join screen asks for the connection and shows more settings on request`() = runComposeUiTest {
        show(720, 640)
        onNodeWithTag("classic_join").assertIsDisplayed()
        onNodeWithTag("classic_auto_join").performScrollTo().assertIsDisplayed()
        onNodeWithTag("classic_create_managed").assertIsDisplayed()
        onNodeWithTag("classic_settings_nav").assertDoesNotExist()
        screenshot("classic_join")

        onNodeWithTag("classic_server_pick").performScrollTo().performClick()
        onNodeWithTag("classic_public_8997").performClick()
        onNodeWithTag("classic_server").assertTextContains("syncplay.pl:8997")

        onNodeWithTag("classic_show_more").performClick()
        onNodeWithTag("classic_settings_nav").assertIsDisplayed()
        onNodeWithTag("classic_tls").assertIsDisplayed()
        onNodeWithTag("classic_section_playback").performClick()
        onNodeWithText("Hardware decoding").assertIsDisplayed()
        screenshot("classic_join_more")
    }

    @Test
    fun `a known room fills the form`() = runComposeUiTest {
        show()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("classic_room_pick").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("classic_room_pick").performClick()
        onNodeWithText("Anime night", substring = true).performClick()
        onNodeWithTag("classic_room").assertTextContains("anime-club")
        onNodeWithTag("classic_server").assertTextContains("myname.duckdns.org:8999")

        // Known rooms can be forgotten from the same list.
        onNodeWithTag("classic_room_pick").performClick()
        onNodeWithTag("classic_forget_anime-club").performClick()
        waitUntil(timeoutMillis = 5_000) { app.settings.value.savedRooms.none { it.room == "anime-club" } }
    }

    @Test
    fun `in a room, the window has the chat, who's playing what, the playlist and ready`() = runComposeUiTest {
        joinRoom()
        show(1180, 720)
        onNodeWithTag("classic_room").assertIsDisplayed()
        onNodeWithTag("chat_field").assertIsDisplayed()
        onNodeWithTag("classic_person_Sam").assertIsDisplayed()
        onNodeWithTag("classic_ready").assertIsDisplayed()
        onNodeWithTag("classic_room_field").assertTextContains("movie-night")
        assertTrue("the player window opens with the room", ClassicWindows.playerOpen)
        screenshot("classic_room")
    }

    @Test
    fun `settings show over the room, and the View menu goes back to the regular view`() = runComposeUiTest {
        joinRoom()
        show()
        onNodeWithTag("classic_menu_view").performClick()
        onNodeWithText("Settings…").performClick()
        onNodeWithTag("classic_back").assertIsDisplayed()
        onNodeWithTag("classic_room").assertDoesNotExist()
        onNodeWithTag("classic_back").performClick()
        onNodeWithTag("classic_room").assertIsDisplayed()

        onNodeWithTag("classic_menu_room").performClick()
        onNodeWithTag("classic_room_skip").assertExists()
        onNodeWithTag("classic_room_details_item").performClick()
        onNodeWithTag("classic_room_details").assertIsDisplayed()
        onNodeWithTag("ready_toggle").assertExists()
        onNodeWithText("Close").performClick()

        assertFalse(regularView)
        onNodeWithTag("classic_menu_view").performClick()
        onNodeWithTag("classic_regular_view").performClick()
        assertTrue(regularView)
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
