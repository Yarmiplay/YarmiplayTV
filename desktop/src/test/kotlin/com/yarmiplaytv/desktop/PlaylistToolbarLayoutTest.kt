package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
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
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The playlist toolbar at every panel width the resize handle allows (and narrower): Save/Load labels stay on one
 * line, buttons never overlap or leave the panel. Screenshots go to build/playlist-toolbar for review.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlaylistToolbarLayoutTest {
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
        DesktopRoomPanel.widthDp = 400f
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    private val toolbarTags = listOf(
        "playlist_Add videos",
        "playlist_Add the videos in a folder",
        "playlist_Add URLs",
        "playlist_Add current",
        "playlist_Shuffle",
        "playlist_Sort A to Z",
        "playlist_Undo (Ctrl+Z)",
        "playlist_Save playlist",
        "playlist_Load playlist",
        "playlist_empty",
        "playlist_Playlist options",
    )

    @Test
    fun `toolbar fits every panel width without wrapped labels or overlap`() = runComposeUiTest {
        app.sync.setTestRoom(
            RoomState(
                status = ConnectionStatus.CONNECTED,
                room = "movie-night",
                username = "alice",
                playlist = listOf("one.mkv", "two.mkv", "three.mkv"),
                playlistIndex = 0,
            ),
        )
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(700.dp, 520.dp).background(AppColors.Background)) { RoomSidePanel(app) }
                }
            }
        }
        val shots = File("build/playlist-toolbar").apply { mkdirs() }
        val failures = mutableListOf<String>()
        for (width in listOf(220, 260, 300, 340, 400, 460, 520, 580, 640)) {
            DesktopRoomPanel.widthDp = width.toFloat()
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("playlist_row_2").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            ImageIO.write(onNodeWithTag("room_panel").captureToImage().toAwtImage(), "png", File(shots, "panel-${width}dp.png"))
            failures += problemsAt(width)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun ComposeUiTest.problemsAt(width: Int): List<String> {
        val problems = mutableListOf<String>()
        val panel = onNodeWithTag("room_panel").getBoundsInRoot()
        val bounds = toolbarTags.associateWith { onNodeWithTag(it).getBoundsInRoot() }
        for ((tag, box) in bounds) {
            if (box.right - box.left < 24.dp || box.bottom - box.top < 24.dp) problems += "$width dp: $tag is ${box.right - box.left} x ${box.bottom - box.top}"
            if (box.left < panel.left - 0.5.dp || box.right > panel.right + 0.5.dp) problems += "$width dp: $tag leaves the panel"
        }
        val tags = bounds.keys.toList()
        for (i in tags.indices) for (j in i + 1 until tags.size) {
            if (bounds.getValue(tags[i]).overlaps(bounds.getValue(tags[j]))) problems += "$width dp: ${tags[i]} overlaps ${tags[j]}"
        }
        val save = bounds.getValue("playlist_Save playlist")
        val load = bounds.getValue("playlist_Load playlist")
        if (save.bottom - save.top > 56.dp) problems += "$width dp: Save playlist is ${save.bottom - save.top} tall"
        if (save.bottom - save.top != load.bottom - load.top || save.right - save.left != load.right - load.left) {
            problems += "$width dp: Save and Load differ in size"
        }
        for (label in listOf("Save playlist", "Load playlist")) {
            val nodes = onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
            for (node in nodes) {
                val layout = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                val lines = layout.singleOrNull()?.lineCount ?: continue
                if (lines != 1) problems += "$width dp: \"$label\" wraps onto $lines lines"
            }
        }
        return problems
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right - 0.5.dp && other.left < right - 0.5.dp && top < other.bottom - 0.5.dp && other.top < bottom - 0.5.dp
}
