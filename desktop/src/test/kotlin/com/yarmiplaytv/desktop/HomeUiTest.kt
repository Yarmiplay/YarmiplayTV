package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.RecentRow
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/** The home screen on desktop: every card's text shows in full, from the smallest window to a large one. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HomeUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private lateinit var store: com.yarmiplaytv.data.SettingsStore

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = desktopSettingsStore(tmp.newFolder(), storeScope)
        runBlocking { store.saveRoomVisit(SavedRoom("syncplay.example-friends-server.net", 8999, false, "desk", "Saturday anime night")) }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "1.9.1",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            malClientId = "test",
        ).also { it.deviceKind = DeviceKind.DESKTOP }
        runBlocking { app.settings.first { it.savedRooms.isNotEmpty() } }
    }

    @After
    fun tearDown() {
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    private fun ComposeUiTest.show(width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme(appPalette(app)) { Box(Modifier.size(width.dp, height.dp)) { MobileRoot(app, DeviceKind.DESKTOP) } }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() &&
                app.settings.value.savedRooms.isNotEmpty()
        }
        waitForIdle()
    }

    /** Texts that are cut off, or wrapped one character at a time, anywhere on the home window (page and side list). */
    private fun ComposeUiTest.cutOff(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val cut = layouts.any { layout ->
                    layout.multiParagraph.didExceedMaxLines ||
                        (0 until layout.lineCount).any { layout.isLineEllipsized(it) } ||
                        (0 until layout.lineCount - 1).any { line ->
                            layout.getLineEnd(line, visibleEnd = true) - layout.getLineStart(line) <= 1
                        }
                }
                node.config[SemanticsProperties.Text].joinToString().takeIf { cut }
            }

    private fun ComposeUiTest.assertOneLine(text: String) {
        val nodes = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("missing \"$text\"", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { layout ->
                assertEquals("\"$text\" should stay on one line", 1, layout.lineCount)
            }
        }
    }

    /** Labels that must stay on one line from the smallest window up to a maximized one. */
    private fun ComposeUiTest.assertHomeLabels() {
        listOf(
            "YarmiplayTV", "Home", "Player", "Search", "Room", "Settings", "Exit",
            "Join a Syncplay room", "Connect Jellyfin", "Connect Plex", "Files on this device",
        ).forEach { assertOneLine(it) }
        listOf(
            "Browse your media server",
            "Browse your Plex server",
            "Open a video or add media folders",
            "Rejoin Saturday anime night on syncplay.example-friends-server.net",
        ).forEach { text ->
            assertTrue("missing \"$text\"", onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue("cut off: ${cutOff()}", cutOff().isEmpty())
    }

    /** On a wide window the page stays in a centered column instead of stretching to the edges. */
    private fun ComposeUiTest.assertReadableWidth(windowWidth: Int) {
        val content = onNodeWithTag("desktop_home_content").fetchSemanticsNode().boundsInRoot
        assertTrue("content is ${content.width}px, wider than 1520", content.width <= 1520f + 1f)
        if (windowWidth >= 1800) {
            assertTrue("content runs to the window edge (${content.right} of $windowWidth)", content.right < windowWidth - 40f)
            assertTrue("content overlaps the side list", content.left > 280f)
        }
    }

    @Test
    fun `the cards show all their text in a large window`() = runDesktopComposeUiTest(1280, 800) {
        show(1280, 800)
        screenshot("home_wide")
        assertHomeLabels()
    }

    @Test
    fun `a 900 by 600 window keeps labels on one line`() = runDesktopComposeUiTest(900, 600) {
        show(900, 600)
        screenshot("home_900x600")
        assertHomeLabels()
    }

    @Test
    fun `a maximized 1440p window keeps a readable content width`() = runDesktopComposeUiTest(1920, 1080) {
        show(1920, 1080)
        screenshot("home_1440p")
        assertHomeLabels()
        assertReadableWidth(1920)
    }

    @Test
    fun `a 4k window keeps a readable content width`() = runDesktopComposeUiTest(2560, 1440) {
        show(2560, 1440)
        screenshot("home_4k")
        assertHomeLabels()
        assertReadableWidth(2560)
    }

    @Test
    fun `the home screen in the light, noire and monochrome themes`() = runDesktopComposeUiTest(1280, 800) {
        saveTheme("light")
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "light" }
        show(1280, 800)
        screenshot("home_light")
        assertTrue("cut off: ${cutOff()}", cutOff().isEmpty())
        saveTheme("noire")
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "noire" }
        waitForIdle()
        screenshot("home_noire")
        saveTheme("monochrome")
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "monochrome" }
        waitForIdle()
        screenshot("home_monochrome")
        // The player takes the light theme; what's written on the video stays white on see-through black.
        saveTheme("light")
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "light" }
        onNodeWithText("Player").performClick()
        waitForIdle()
        screenshot("player_in_light_theme")
    }

    @Test
    fun `the cards show all their text in the smallest window`() = runDesktopComposeUiTest(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt()) {
        show(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt())
        screenshot("home_narrow")
        assertHomeLabels()
        onNodeWithText("MyAnimeList").performScrollTo().assertIsDisplayed()
        onNodeWithText("Open a video or add media folders").performScrollTo().assertIsDisplayed()
        screenshot("home_narrow_scrolled")
    }

    @Test
    fun `the What's new card is highlighted within its rounded corners`() = runDesktopComposeUiTest(1280, 800) {
        runBlocking { store.saveLastSeenVersion("1.9.0") }
        show(1280, 800)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("whats_new_card").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("whats_new_card").performMouseInput { moveTo(Offset(width * 0.4f, height / 2f)) }
        waitForIdle()
        screenshot("home_whats_new_hover")
        // The corner just outside the rounding shows the page, not the highlight.
        val image = onRoot().captureToImage().toAwtImage() as java.awt.image.BufferedImage
        val card = onNodeWithTag("whats_new_card").fetchSemanticsNode().boundsInRoot
        val corner = image.getRGB(card.left.toInt() + 1, card.top.toInt() + 1)
        val page = image.getRGB(card.left.toInt() - 4, card.top.toInt() + 1)
        assertEquals("corner outside the rounding", page, corner)
    }

    @Test
    fun `action cards show a pointer hover and a keyboard focus ring`() = runDesktopComposeUiTest(1280, 800) {
        show(1280, 800)
        onNodeWithTag("card_room").performMouseInput { moveTo(Offset(width * 0.5f, height * 0.5f)) }
        waitForIdle()
        screenshot("home_card_hover")
        onNodeWithTag("card_room").requestFocus()
        onNodeWithTag("card_room").assertIsFocused()
        waitForIdle()
        screenshot("home_card_focus")
        assertTrue("cut off: ${cutOff()}", cutOff().isEmpty())
    }

    @Test
    fun `every home card still opens its screen`() = runDesktopComposeUiTest(1280, 800) {
        show(1280, 800)
        onNodeWithTag("card_room").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("connect").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("tab_Home").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() &&
                app.settings.value.savedRooms.isNotEmpty()
        }
        onNodeWithTag("card_local").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("local_list").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("tab_Home").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("card_jellyfin").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("card_jellyfin").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("jellyfin_url").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("tab_Home").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("card_plex").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("card_plex").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("plex_link").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `exit option on home triggers exitApp`() = runDesktopComposeUiTest(1280, 800) {
        var exited = false
        app.onExitApp = { exited = true }
        show(1280, 800)
        onNodeWithTag("home_exit_app").performClick()
        assertTrue("Exit option on home should trigger exitApp", exited)
    }

    @Test
    fun `exit option on sidebar triggers exitApp`() = runDesktopComposeUiTest(1280, 800) {
        var exited = false
        app.onExitApp = { exited = true }
        show(1280, 800)
        onNodeWithTag("sidebar_exit").performClick()
        assertTrue("Exit option on sidebar should trigger exitApp", exited)
    }

    @Test
    fun `continue watching wraps onto new rows on desktop rather than scrolling right`() = runDesktopComposeUiTest(600, 800) {
        val nav = Navigator(mutableStateListOf(Screen.Home))
        val items = (1..6).map { i ->
            MediaItem("id_$i", "Episode $i", MediaItemType.EPISODE, isFolder = false, played = false, seriesName = "Series $i")
        }
        setContent {
            MobileTheme {
                Box(Modifier.size(600.dp, 800.dp)) {
                    RecentRow(app, nav, "Continue watching & recently added", items, null, 260.dp, wrap = true)
                }
            }
        }
        waitForIdle()
        val top1 = onNodeWithText("Episode 1").fetchSemanticsNode().boundsInRoot.top
        val top2 = onNodeWithText("Episode 2").fetchSemanticsNode().boundsInRoot.top
        val top3 = onNodeWithText("Episode 3").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Episode 1 and 2 should be on the first row", top1, top2, 1.0f)
        assertTrue("Episode 3 should wrap to a new row below row 1", top3 > top1 + 100f)
    }

    /**
     * Windows sometimes refuses the rename DataStore uses to replace its file, and reports that as this store
     * being open twice. The next attempt succeeds once the previous handle is gone.
     */
    private fun saveTheme(key: String) {
        runBlocking {
            var last: IOException? = null
            repeat(5) { attempt ->
                try {
                    app.settingsStore.saveTheme(key)
                    return@runBlocking
                } catch (e: IOException) {
                    last = e
                    if (attempt < 4) Thread.sleep(50L * (attempt + 1))
                }
            }
            throw last!!
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
