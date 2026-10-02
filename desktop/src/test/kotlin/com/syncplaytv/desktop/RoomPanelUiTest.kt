package com.syncplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syncplaytv.AppContainer
import com.syncplaytv.data.desktopSettingsStore
import com.syncplaytv.local.FileLocalLibrary
import com.syncplaytv.player.PlaybackState
import com.syncplaytv.player.Player
import com.syncplaytv.player.PlayerEvent
import com.syncplaytv.player.Track
import com.syncplaytv.player.TrackType
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.syncplay.SyncplayConfig
import com.syncplaytv.ui.mobile.DesktopRoomPanel
import com.syncplaytv.ui.mobile.MobileTheme
import com.syncplaytv.ui.mobile.RoomPanelTab
import com.syncplaytv.ui.mobile.RoomSidePanel
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The desktop side panel driven like a user would, against a real syncplay-server (SYNCPLAY_TEST_SERVER=host:port).
 * Screenshots of the panel go to the syncplaytv.screenshotDir system property, for checking fonts per OS by eye.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RoomPanelUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val room = "panel-" + System.nanoTime()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private val apps = mutableListOf<AppContainer>()

    @Before
    fun setUp() {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        DesktopRoomPanel.open = true
        DesktopRoomPanel.tab = RoomPanelTab.PLAYLIST
    }

    @After
    fun tearDown() {
        if (!::main.isInitialized) return
        onMain { apps.forEach { it.sync.disconnect() } }
        apps.forEach { it.scope.cancel() }
        runBlocking { withTimeout(5_000) { apps.forEach { it.scope.coroutineContext.job.join() } } }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
        DesktopRoomPanel.open = false
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun ComposeUiTest.awaitTrue(what: String, condition: () -> Boolean) {
        runCatching { waitUntil(timeoutMillis = 10_000) { onMain(condition) } }
            .onFailure { throw AssertionError("Timed out waiting for $what (${apps.map { it.sync.room.value.playlist }})") }
    }

    private fun join(name: String): AppContainer {
        val app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root.resolve(name), storeScope),
            deviceName = name,
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        apps += app
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        onMain { app.sync.connect(SyncplayConfig(host, port, name, room)) }
        runBlocking { withTimeout(10_000) { while (onMain { app.sync.room.value.status } != ConnectionStatus.CONNECTED) delay(20) } }
        return app
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("syncplaytv.screenshotDir") ?: return
        waitForIdle()
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun `the playlist is edited with the mouse and keyboard, and chat sends, recalls and badges`() = runComposeUiTest {
        val alice = join("alice")
        val bob = join("bob")
        onMain { bob.playlist.shared.add(listOf("one.mkv", "two.mkv", "three.mkv")) }
        awaitTrue("alice to get bob's playlist") { alice.sync.room.value.playlist.size == 3 }

        // A window provides a lifecycle; this bare composition needs its own.
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(380.dp, 640.dp).background(AppColors.Background)) { RoomSidePanel(alice) }
                }
            }
        }
        waitUntil { onAllNodesWithTag("playlist_row_2").fetchSemanticsNodes().isNotEmpty() }
        screenshot("panel_playlist")

        // Click selects, Alt+Down moves, Delete removes, Ctrl+Z puts it back.
        onNodeWithTag("playlist_row_0").performMouseInput { click() }
        // Rows also take double-clicks, so a single click lands once the double-click time has passed.
        mainClock.advanceTimeBy(500)
        onNodeWithTag("playlist_list").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        awaitTrue("one.mkv to move down") { alice.sync.room.value.playlist == listOf("two.mkv", "one.mkv", "three.mkv") }
        onNodeWithTag("playlist_list").performKeyInput { pressKey(Key.Delete) }
        awaitTrue("one.mkv to be removed") { bob.sync.room.value.playlist == listOf("two.mkv", "three.mkv") }
        onNodeWithTag("playlist_list").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        awaitTrue("the removal to be undone") { bob.sync.room.value.playlist == listOf("two.mkv", "one.mkv", "three.mkv") }

        // Ctrl+A then Delete empties it; undo again. Double-click picks an entry for the room.
        onNodeWithTag("playlist_list").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.A) }
            pressKey(Key.Delete)
        }
        awaitTrue("the playlist to empty") { bob.sync.room.value.playlist.isEmpty() }
        onNodeWithTag("playlist_list").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        awaitTrue("the playlist to come back") { bob.sync.room.value.playlist.size == 3 }
        onNodeWithTag("playlist_row_2").performMouseInput { doubleClick() }
        awaitTrue("bob to follow the pick") { bob.sync.room.value.playlistIndex == 2 }

        // Chat from the panel reaches bob; Up recalls what was sent.
        onNodeWithTag("panel_tab_CHAT").performClick()
        onNodeWithTag("chat_field").performClick()
        onNodeWithTag("chat_field").performTextInput("hello from the desktop")
        onNodeWithTag("chat_field").performKeyInput { pressKey(Key.Enter) }
        awaitTrue("bob to get the message") { bob.sync.feed.value.any { it.from == "alice" && it.text == "hello from the desktop" } }
        onNodeWithTag("chat_field").assert(hasText("hello from the desktop").not())
        onNodeWithTag("chat_field").performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("chat_field").assert(hasText("hello from the desktop"))

        // A message while another tab shows raises the Chat tab's badge, which opening the tab clears.
        onNodeWithTag("panel_tab_ROOM").performClick()
        onMain { bob.sync.sendChat("hi alice") }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("panel_tab_CHAT").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("hi alice", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1").assertDoesNotExist()
        screenshot("panel_chat")
    }
}

private class IdlePlayer : Player {
    override val state = MutableStateFlow(PlaybackState())
    override val tracks = MutableStateFlow<List<Track>>(emptyList())
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
    override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) = Unit
    override fun stop() = Unit
    override fun setPaused(paused: Boolean) = Unit
    override fun seek(position: Double) = Unit
    override fun seekRelative(offset: Double) = Unit
    override fun setSpeed(speed: Double) = Unit
    override fun selectTrack(type: TrackType, id: Int?) = Unit
    override fun showText(text: String, durationMs: Int) = Unit
    override val isFileLoaded: Boolean get() = false
    override val isPaused: Boolean get() = true
    override val duration: Double get() = 0.0
    override fun currentPosition(): Double = 0.0
}
