package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
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
        store = desktopSettingsStore(tmp.root.resolve("app"), storeScope)
        runBlocking { store.saveRoomVisit(SavedRoom("syncplay.example-friends-server.net", 8999, false, "desk", "Saturday anime night")) }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "1.9.1",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            malClientId = "test",
        ).also { it.deviceKind = DeviceKind.DESKTOP }
    }

    @After
    fun tearDown() {
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun ComposeUiTest.show(width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, height.dp)) { MobileRoot(app, DeviceKind.DESKTOP) } }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    /** The texts on the home screen that are cut off (ellipsized or clipped). */
    private fun ComposeUiTest.cutOff(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag("home")), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val cut = layouts.any { layout ->
                    layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                }
                node.config[SemanticsProperties.Text].joinToString().takeIf { cut }
            }

    @Test
    fun `the cards show all their text in a large window`() = runDesktopComposeUiTest(1280, 800) {
        show(1280, 800)
        screenshot("home_wide")
        assertTrue("cut off: ${cutOff()}", cutOff().isEmpty())
    }

    @Test
    fun `the cards show all their text in the smallest window`() = runDesktopComposeUiTest(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt()) {
        show(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt())
        screenshot("home_narrow")
        assertTrue("cut off: ${cutOff()}", cutOff().isEmpty())
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

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
