package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.ui.mobile.MobilePlayerScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The "Couldn't find this file" card: on a narrow phone it sits in the middle of the screen with
 * one-line actions, and on a wide player it stays on the video in one row.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerStatusCardUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private val player = IdlePlayer()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root, storeScope),
            deviceName = "test",
            appVersion = "test",
            createPlayer = { player },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        player.state.value = player.state.value.copy(paused = true, fileLoaded = false)
    }

    @After
    fun tearDown() {
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    @Test
    fun `phone portrait card sits below the video and its actions stay on one line`() = runComposeUiTest {
        showMissingFile(360.dp, 800.dp, DeviceKind.PHONE)
        val screen = onRoot().fetchSemanticsNode().boundsInRoot
        val video = onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag("not_found").fetchSemanticsNode().boundsInRoot
        assertTrue("card is below the video (card $card, video $video)", card.top >= video.bottom - 1f)
        assertTrue("card stays on screen", card.left >= screen.left - 1f && card.right <= screen.right + 1f && card.bottom <= screen.bottom + 1f)
        val mid = (card.top + card.bottom) / 2f
        val screenMid = (screen.top + screen.bottom) / 2f
        assertTrue(
            "card is in the middle of the screen (card mid $mid, screen mid $screenMid)",
            abs(mid - screenMid) <= screen.height * 0.25f,
        )
        assertEquals("card is centered horizontally", (screen.left + screen.right) / 2f, (card.left + card.right) / 2f, 2f)
        val back = onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        assertFalse("card covers Back", overlaps(card, back))
        runBlocking(main) { app.sync.postLocal("Can't load this file", isError = true) }
        settle()
        val toast = onNodeWithText("Can't load this file").fetchSemanticsNode().boundsInRoot
        assertFalse("card covers the error toast", overlaps(card, toast))
        assertActions(card, oneRow = false)
        onNodeWithTag("dismiss_status").performClick()
        settle()
        assertTrue(onAllNodesWithTag("not_found").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `phone landscape keeps the card on the video in one row`() = runComposeUiTest {
        showMissingFile(800.dp, 360.dp, DeviceKind.PHONE)
        assertCardOnVideo()
        assertActions(onNodeWithTag("not_found").fetchSemanticsNode().boundsInRoot, oneRow = true)
    }

    @Test
    fun `tablet portrait keeps the card on the video`() = runComposeUiTest {
        showMissingFile(800.dp, 1280.dp, DeviceKind.TABLET)
        assertCardOnVideo()
        assertActions(onNodeWithTag("not_found").fetchSemanticsNode().boundsInRoot, oneRow = true)
    }

    @Test
    fun `desktop keeps the card on the video in one row`() = runComposeUiTest {
        showMissingFile(960.dp, 540.dp, DeviceKind.DESKTOP)
        assertCardOnVideo()
        assertActions(onNodeWithTag("not_found").fetchSemanticsNode().boundsInRoot, oneRow = true)
    }

    @Test
    fun `phone portrait untrusted link card is below the video with one-line actions`() = runComposeUiTest {
        app.deviceKind = DeviceKind.PHONE
        runBlocking(main) {
            setPlaylistStatus(PlaylistStatus.Untrusted("https://files.noire.example.com/live.mkv", "files.noire.example.com"))
        }
        show(360.dp, 800.dp)
        val video = onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag("untrusted_url").fetchSemanticsNode().boundsInRoot
        assertTrue("untrusted card is below the video", card.top >= video.bottom - 1f)
        listOf("Open", "Dismiss").forEach { assertSingleLine(it, allowEllipsis = false) }
        assertSingleLine("Always trust files.noire.example.com", allowEllipsis = true)
        val buttons = listOf("open_link", "trust_domain", "dismiss_status").map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        buttons.forEach { assertInside(it, card, "action") }
        assertNoOverlap(buttons)
        val widths = buttons.map { it.width }
        assertTrue("stacked actions match in width ($widths)", widths.max() - widths.min() < 2f)
    }

    private fun ComposeUiTest.showMissingFile(width: Dp, height: Dp, kind: DeviceKind) {
        app.deviceKind = kind
        runBlocking(main) {
            app.addJellyfin(JellyfinSession("http://127.0.0.1:1/noire", "NoireServerTV", "noire", "u-noire", "sam", "token-noire"))
            withTimeout(2_000) {
                while (app.mediaSource.value == null) delay(10)
            }
            setPlaylistStatus(PlaylistStatus.NotFound("🔴 Live: tooo schi", "Not found in NoireServerTV"))
        }
        show(width, height)
        onNodeWithText("Couldn't find this file").assertExists()
        onNodeWithText("Not found in NoireServerTV").assertExists()
        onNodeWithTag("pick_file").assertExists()
        onNodeWithTag("search_servers").assertExists()
        onNodeWithTag("dismiss_status").assertExists()
    }

    private fun ComposeUiTest.show(width: Dp, height: Dp) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width, height)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private fun ComposeUiTest.assertCardOnVideo() {
        val video = onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag("not_found").fetchSemanticsNode().boundsInRoot
        assertTrue("card stays on the video (card $card, video $video)", card.top >= video.top - 1f && card.bottom <= video.bottom + 1f)
    }

    /** [oneRow] when the three actions have room to sit side by side. */
    private fun ComposeUiTest.assertActions(card: Rect, oneRow: Boolean) {
        listOf("Pick file", "Search servers", "Dismiss").forEach { assertSingleLine(it, allowEllipsis = false) }
        val buttons = listOf("pick_file", "search_servers", "dismiss_status").map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val screen = onRoot().fetchSemanticsNode().boundsInRoot
        buttons.forEach { button ->
            assertInside(button, card, "action")
            assertInside(button, screen, "action on screen")
        }
        assertNoOverlap(buttons)
        val centers = buttons.map { (it.top + it.bottom) / 2f }
        val stacked = centers.max() - centers.min() > 20f
        if (oneRow) {
            assertFalse("actions wrap onto another row (centers $centers)", stacked)
            buttons.zipWithNext().forEach { (left, right) ->
                assertTrue("actions stay in order", left.right <= right.left + 1f)
            }
        } else if (stacked) {
            val widths = buttons.map { it.width }
            assertTrue("stacked actions match in width ($widths)", widths.max() - widths.min() < 2f)
            buttons.zipWithNext().forEach { (above, below) ->
                assertTrue("stacked actions don't collide", above.bottom <= below.top + 1f)
            }
        }
    }

    private fun ComposeUiTest.assertSingleLine(label: String, allowEllipsis: Boolean) {
        val node = onNodeWithText(label, substring = allowEllipsis, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        val reported = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout) == true
        assertTrue("\"$label\" reports its layout", reported && layout.isNotEmpty())
        val result = layout.single()
        assertEquals("\"$label\" stays on one line", 1, result.lineCount)
        if (!allowEllipsis) {
            // hasVisualOverflow is also set for a fraction of a pixel and is not a clipped label.
            assertFalse("\"$label\" is cut off", result.isLineEllipsized(0))
        }
        val bounds = node.boundsInRoot
        assertTrue("\"$label\" reads across (w=${bounds.width}, h=${bounds.height})", bounds.width > bounds.height)
    }

    private fun overlaps(a: Rect, b: Rect) =
        a.left < b.right - 1f && b.left < a.right - 1f && a.top < b.bottom - 1f && b.top < a.bottom - 1f

    private fun assertInside(inner: Rect, outer: Rect, name: String) {
        assertTrue(
            "$name $inner is outside $outer",
            inner.left >= outer.left - 1f && inner.right <= outer.right + 1f && inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f,
        )
    }

    private fun assertNoOverlap(boxes: List<Rect>) {
        boxes.forEachIndexed { i, a ->
            boxes.drop(i + 1).forEach { b -> assertFalse("actions overlap ($a and $b)", overlaps(a, b)) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPlaylistStatus(status: PlaylistStatus) {
        val field = app.playlist.javaClass.getDeclaredField("_status")
        field.isAccessible = true
        (field.get(app.playlist) as MutableStateFlow<PlaylistStatus>).value = status
    }
}
