package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.DesktopPip
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.MobilePlayerScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.PipContent
import com.yarmiplaytv.ui.mobile.PlayerVolume
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The player's controls on desktop: they hide quickly while a video plays, can be hidden by hand, and have a
 * volume control. A screenshot of the bar goes to the yarmiplaytv.screenshotDir system property.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerControlsUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private val player = IdlePlayer()
    private val volume = object : PlayerVolume {
        override val volume = MutableStateFlow(80.0)
        override val muted = MutableStateFlow(false)
        override val max = 130.0
        override fun setVolume(percent: Double) { volume.value = percent; muted.value = false }
        override fun setMuted(muted: Boolean) { this.muted.value = muted }
    }

    /** Seek bar previews: a blank frame, and which video and time each was asked for. */
    private val frames = object : SeekFrames {
        val asked: MutableList<Pair<String, Double>> = Collections.synchronizedList(mutableListOf())
        override fun frame(uri: String, seconds: Double, width: Int): ImageBitmap {
            asked += uri to seconds
            return ImageBitmap(width, width * 9 / 16)
        }
    }

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
            seekFrames = frames,
        )
        player.state.value = PlaybackState(fileLoaded = true, duration = 600.0, paused = false)
        DesktopPlayerInput.volume = volume
    }

    @After
    fun tearDown() {
        DesktopPlayerInput.volume = null
        DesktopPip.open = false
        DesktopPip.toggle = { DesktopPip.open = !DesktopPip.open }
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun ComposeUiTest.showPlayer() {
        // A window provides a lifecycle; this bare composition needs its own.
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(960.dp, 540.dp)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()
    }

    /** Lets the fade animations finish without reaching the 2 s auto-hide. */
    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private fun ComposeUiTest.bubbleText(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag("seek_preview")))

    private fun ComposeUiTest.controlsShowing() = onAllNodesWithTag("player_controls").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the volume control shows the level, mutes and sets the volume`() = runComposeUiTest {
        player.state.value = player.state.value.copy(paused = true)
        showPlayer()
        onNodeWithTag("volume_label").assertTextEquals("80%")
        screenshot("player_controls")

        onNodeWithTag("volume_mute").performMouseInput { click() }
        settle()
        assertTrue(volume.muted.value)
        onNodeWithTag("volume_label").assertTextEquals("Muted")
        onNodeWithTag("volume_mute").assertContentDescriptionEquals("Unmute (M)")
        onNodeWithTag("volume_mute").performMouseInput { click() }
        settle()
        assertFalse(volume.muted.value)

        onNodeWithTag("volume_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(65f) }
        settle()
        assertEquals(65.0, volume.volume.value, 0.0)
        onNodeWithTag("volume_label").assertTextEquals("65%")
    }

    @Test
    fun `clicking or dragging along the seek bar seeks there`() = runComposeUiTest {
        player.state.value = player.state.value.copy(paused = true)
        showPlayer()
        onNodeWithTag("seek_slider").performMouseInput { click(Offset(width * 0.25f, centerY)) }
        settle()
        assertEquals(150.0, player.seeks.single(), 1.0)

        onNodeWithTag("seek_slider").performMouseInput {
            moveTo(Offset(width * 0.5f, centerY))
            press()
            moveTo(Offset(width * 0.75f, centerY))
            release()
        }
        settle()
        assertEquals("one seek when the button is let go", 2, player.seeks.size)
        assertEquals(450.0, player.seeks.last(), 1.0)

        onNodeWithTag("seek_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        assertEquals(60.0, player.seeks.last(), 0.0)
    }

    @Test
    fun `the seek bar shows the time it points at, with a frame there for a file on this device`() = runComposeUiTest {
        player.state.value = player.state.value.copy(paused = true, url = "https://example.com/stream.mkv")
        showPlayer()
        onNodeWithTag("seek_slider").performMouseInput { moveTo(Offset(width * 0.5f, centerY)) }
        settle()
        onNodeWithTag("seek_preview").assertExists()
        bubbleText("5:00").assertExists()
        screenshot("seek_time")
        assertTrue("a stream isn't previewed", frames.asked.isEmpty())
        assertTrue(onAllNodesWithTag("seek_frame").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("seek_slider").performMouseInput { moveTo(Offset(width * 0.5f, -200f)) }
        settle()
        onNodeWithTag("seek_preview").assertDoesNotExist()

        player.state.value = player.state.value.copy(url = File(tmp.root, "episode.mkv").toURI().toString())
        settle()
        onNodeWithTag("seek_slider").performMouseInput {
            moveTo(Offset(width * 0.25f, centerY))
            press()
            moveTo(Offset(width * 0.75f, centerY))
        }
        settle()
        bubbleText("7:30").assertExists()
        waitUntil("the frame", timeoutMillis = 5_000) { onAllNodesWithTag("seek_frame").fetchSemanticsNodes().isNotEmpty() }
        screenshot("seek_preview")
        assertEquals("only where the drag stopped", 450.0, frames.asked.last().second, 3.0)
        assertTrue(frames.asked.last().first.endsWith("episode.mkv"))
        onNodeWithTag("seek_slider").performMouseInput { release() }
        settle()
        assertEquals(450.0, player.seeks.last(), 1.0)
    }

    @Test
    fun `picture-in-picture moves the video to its own window, and the player says where it went`() = runComposeUiTest {
        DesktopPip.toggle = { DesktopPip.open = !DesktopPip.open }
        showPlayer()
        onNodeWithTag("pip").assertContentDescriptionEquals("Picture-in-picture (P)")
        // The click action, not a mouse click: the pointer left over the video would show the hover controls.
        onNodeWithTag("pip").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertTrue(DesktopPip.open)
        onNodeWithTag("player_pip_elsewhere").assertExists()
        assertFalse("no controls while it plays elsewhere", controlsShowing())

        onNodeWithTag("pip_back_here").performMouseInput { click() }
        settle()
        assertFalse(DesktopPip.open)
        onNodeWithTag("player_pip_elsewhere").assertDoesNotExist()
        assertTrue("the controls are back", controlsShowing())

        // Esc (the back handler) brings it back here too, without leaving the player.
        DesktopPip.open = true
        settle()
        runOnUiThread { assertTrue(DesktopBack.dispatch()) }
        settle()
        assertFalse(DesktopPip.open)
        onNodeWithTag("player").assertExists()
    }

    @Test
    fun `the picture-in-picture window plays, pauses, goes back and closes`() = runComposeUiTest {
        var back = 0
        var closed = 0
        val moves = mutableListOf<Pair<Int, Int>>()
        player.state.value = player.state.value.copy(paused = true)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(400.dp, 225.dp)) {
                        PipContent(app, onBack = { back++ }, onClose = { closed++ }, onMove = { dx, dy -> moves += dx to dy }, onResize = { _, _ -> })
                    }
                }
            }
        }
        settle()
        onNodeWithTag("pip_play_pause").assertDoesNotExist()
        onNodeWithTag("pip_window").performMouseInput { moveTo(center) }
        settle()
        screenshot("pip_window")
        onNodeWithTag("pip_play_pause").performMouseInput { click() }
        settle()
        assertEquals(listOf(false), player.pauses)
        onNodeWithTag("seek_slider").assertExists()
        onNodeWithTag("pip_back").performMouseInput { click() }
        onNodeWithTag("pip_close").performMouseInput { click() }
        settle()
        assertEquals(1 to 1, back to closed)
        onNodeWithTag("pip_window").performMouseInput { doubleClick(Offset(width * 0.15f, height * 0.5f)) }
        settle()
        assertEquals("double-click goes back to the app", 2, back)
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun `controls hide after 2 seconds of playing, but not while the mouse is on them`() = runComposeUiTest {
        showPlayer()
        assertTrue(controlsShowing())
        mainClock.advanceTimeBy(2_500)
        waitForIdle()
        assertFalse("hidden after 2 s", controlsShowing())

        onNodeWithTag("player").performMouseInput { moveTo(Offset(100f, 100f)); moveTo(Offset(400f, 300f)) }
        settle()
        assertTrue("a mouse move shows them", controlsShowing())
        onNodeWithTag("seek_slider").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        assertTrue("kept while the mouse is on the bar", controlsShowing())
    }

    @Test
    fun `Hide and H hide the controls by hand, and only a real mouse move brings them back`() = runComposeUiTest {
        player.state.value = player.state.value.copy(paused = true)
        showPlayer()
        // The player fills the root, so the button's root position is also its position in the player.
        val button = onNodeWithTag("hide_controls").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag("player").performMouseInput { moveTo(button); click(button) }
        settle()
        assertFalse("hidden by the button, also while paused", controlsShowing())

        onNodeWithTag("player").performMouseInput { moveTo(button + Offset(3f, 2f)) }
        settle()
        assertFalse("a nudged mouse leaves them hidden", controlsShowing())
        onNodeWithTag("player").performMouseInput { moveTo(button + Offset(-80f, 60f)) }
        settle()
        assertTrue("moving the mouse shows them", controlsShowing())

        DesktopPlayerInput.input!!.value.toggleControls()
        settle()
        assertFalse("H hides them", controlsShowing())
        DesktopPlayerInput.input!!.value.toggleControls()
        settle()
        assertTrue("H shows them again", controlsShowing())
    }

    @Test
    fun `portrait player keeps bottom controls visible while video overlay auto-hides`() = runComposeUiTest {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(412.dp, 892.dp)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()
        assertTrue(controlsShowing())
        assertTrue(onAllNodesWithTag("video_overlay").fetchSemanticsNodes().isNotEmpty())

        // After the auto-hide timeout, the top video overlay fades out, while the bottom portrait controls remain visible.
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertFalse("video overlay auto-hides while playing", onAllNodesWithTag("video_overlay").fetchSemanticsNodes().isNotEmpty())
        assertTrue("portrait bottom controls stay visible", controlsShowing())
        onNodeWithTag("play_pause").assertExists()
        onNodeWithTag("seek_slider").assertExists()
    }
}
