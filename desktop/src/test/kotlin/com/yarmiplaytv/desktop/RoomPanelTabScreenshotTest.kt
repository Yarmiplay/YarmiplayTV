package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.ui.mobile.DesktopRoomPanel
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.RoomPanelTab
import com.yarmiplaytv.ui.mobile.RoomSidePanel
import com.yarmiplaytv.ui.theme.AppColors
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.yarmiplaytv.sync.FeedMessage

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RoomPanelTabScreenshotTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root, storeScope),
            deviceName = "test",
            appVersion = "3.0.5",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            malClientId = "test-client",
        )
        DesktopRoomPanel.open = true
        DesktopRoomPanel.tab = RoomPanelTab.PLAYLIST
    }

    @After
    fun tearDown() {
        DesktopRoomPanel.open = false
        if (::app.isInitialized) app.scope.cancel()
        if (::storeScope.isInitialized) storeScope.cancel()
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    private fun testRoom() = RoomState(
        status = ConnectionStatus.CONNECTED,
        room = "movie-night",
        username = "alice",
        playlist = listOf("Episode 01.mkv", "Episode 02.mkv"),
        playlistIndex = 0,
    )

    private fun ComposeUiTest.saveScreenshot(name: String) {
        waitForIdle()
        val outDir = File("build/screenshots")
        outDir.mkdirs()
        val file = File(outDir, "$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        println("SAVED_SCREENSHOT: " + file.absolutePath)
    }

    @Test
    fun `capture side panel tabs at default width and min width`() = runComposeUiTest {
        app.sync.setTestRoom(testRoom())
        DesktopRoomPanel.widthDp = 400f
        DesktopRoomPanel.tab = RoomPanelTab.PLAYLIST
        DesktopRoomPanel.unread = 1

        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(400.dp, 600.dp).background(AppColors.Background)) {
                        RoomSidePanel(app)
                    }
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("room_panel").fetchSemanticsNodes().isNotEmpty() }
        
        // 1-digit unread badge (circle)
        saveScreenshot("after_tab_bar_400dp")
        saveScreenshot("after_badge_1_digit")

        // Assert all 4 tabs exist
        RoomPanelTab.entries.forEach { tab ->
            onNodeWithTag("panel_tab_${tab.name}").assertExists()
        }

        // 2-digit unread badge (pill)
        DesktopRoomPanel.unread = 12
        saveScreenshot("after_badge_2_digits")

        // 99+ unread badge (pill)
        DesktopRoomPanel.unread = 150
        saveScreenshot("after_badge_99_plus")

        // Reset to 2 unread for subsequent width tests
        DesktopRoomPanel.unread = 2

        // Switch to Room tab
        onNodeWithTag("panel_tab_ROOM").performClick()
        saveScreenshot("after_tab_bar_room_selected")
        org.junit.Assert.assertEquals(RoomPanelTab.ROOM, DesktopRoomPanel.tab)

        // Switch to MAL tab
        onNodeWithTag("panel_tab_MAL").performClick()
        saveScreenshot("after_tab_bar_mal_selected")
        org.junit.Assert.assertEquals(RoomPanelTab.MAL, DesktopRoomPanel.tab)

        // Switch to Chat tab clears unread
        onNodeWithTag("panel_tab_CHAT").performClick()
        org.junit.Assert.assertEquals(RoomPanelTab.CHAT, DesktopRoomPanel.tab)
        saveScreenshot("after_tab_bar_chat_selected")

        // Min width 300dp: labels must never wrap or truncate
        DesktopRoomPanel.tab = RoomPanelTab.PLAYLIST
        DesktopRoomPanel.unread = 3
        DesktopRoomPanel.widthDp = 300f
        saveScreenshot("after_tab_bar_300dp")

        // Max width 640dp
        DesktopRoomPanel.widthDp = 640f
        saveScreenshot("after_tab_bar_640dp")
    }
}
