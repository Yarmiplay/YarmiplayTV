package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.ui.mobile.DesktopRoomPanel
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.RoomPanelTab
import com.yarmiplaytv.ui.mobile.RoomSidePanel
import com.yarmiplaytv.ui.theme.AppColors
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Playlist panel Save/Load and long-name row layout without a live Syncplay server ([SyncController.setTestRoom]).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlaylistPanelUiTest {
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
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        ).also { it.deviceKind = DeviceKind.DESKTOP }
        DesktopRoomPanel.open = true
        DesktopRoomPanel.tab = RoomPanelTab.PLAYLIST
    }

    @After
    fun tearDown() {
        DesktopRoomPanel.open = false
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    private fun longPlaylistRoom() = RoomState(
        status = ConnectionStatus.CONNECTED,
        room = "movie-night",
        username = "alice",
        playlist = listOf(
            "[SubsPlease] Tensei Kizoku, Kantei Skill de Nariagaru - 30 (1080p) [518A744A].mkv",
            "two.mkv",
        ),
        playlistIndex = 0,
    )

    @Test
    fun `long playlist titles stay within two lines and save load works in-app`() = runComposeUiTest {
        app.sync.setTestRoom(longPlaylistRoom())
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(420.dp, 720.dp).background(AppColors.Background)) { RoomSidePanel(app) }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("room_panel").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("playlist_row_1").fetchSemanticsNodes().isNotEmpty() }

        val tall = onNodeWithTag("playlist_row_0").getBoundsInRoot().let { it.bottom - it.top }
        val short = onNodeWithTag("playlist_row_1").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the long row is $tall tall, the short one $short", tall < short * 3.5f && tall >= short)
        val layout = mutableListOf<TextLayoutResult>()
        onNodeWithTag("playlist_title_0").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val result = layout.single()
        assertTrue("title stays within two lines (was ${result.lineCount})", result.lineCount in 1..2)
        assertTrue("long title is ellipsized", result.isLineEllipsized(result.lineCount - 1) || result.hasVisualOverflow)

        onNodeWithTag("playlist_Save playlist").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("playlist_save_name").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("playlist_save_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("playlist_saved_box").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { runBlocking { app.settingsStore.savedPlaylists.first().isNotEmpty() } }
        val saved = runBlocking { app.settingsStore.savedPlaylists.first() }
        assertEquals(1, saved.size)
        assertEquals(2, saved.single().entries.size)
        assertTrue(saved.single().name.contains("Tensei Kizoku") || saved.single().name.contains("·"))

        onNodeWithText("Rename").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("playlist_rename_${saved.single().id}").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("playlist_rename_${saved.single().id}").performTextClearance()
        onNodeWithTag("playlist_rename_${saved.single().id}").performTextInput("Watch later")
        onNodeWithTag("playlist_rename_confirm_${saved.single().id}").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.settingsStore.savedPlaylists.first().singleOrNull()?.name == "Watch later" }
        }
        onNodeWithText("Watch later").assertExists()
    }
}
