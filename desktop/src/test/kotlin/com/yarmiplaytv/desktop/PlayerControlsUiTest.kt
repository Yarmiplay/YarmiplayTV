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
import androidx.compose.ui.platform.LocalDensity
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
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.DesktopPip
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.MobilePlayerScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.PipContent
import com.yarmiplaytv.ui.mobile.PlayerVolume
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.voice.VoiceCaptureKind
import com.yarmiplaytv.voice.VoiceConnection
import com.yarmiplaytv.voice.VoiceUiState
import java.io.File
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
        ).also { it.deviceKind = DeviceKind.DESKTOP }
        player.state.value = PlaybackState(fileLoaded = true, duration = 600.0, paused = false)
        DesktopPlayerInput.volume = volume
    }

    @After
    fun tearDown() {
        DesktopPlayerInput.volume = null
        DesktopPip.open = false
        DesktopPip.toggle = { DesktopPip.open = !DesktopPip.open }
        // Join before resetMain: leaving Main work running races and fails on macOS CI.
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
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
    fun `desktop has no Menus button and keeps 2_7_9 sheet controls directly accessible`() = runComposeUiTest {
        player.state.value = player.state.value.copy(paused = true)
        showPlayer()
        onNodeWithTag("player_menus").assertDoesNotExist()
        // Controls that 2.8.0 wrongly moved into Menus on desktop — restored as direct sheet buttons.
        listOf(
            "sheet_Shared playlist",
            "sheet_Room",
            "sheet_Chat",
            "sheet_Audio",
            "sheet_Subtitles",
            "sheet_Video",
        ).forEach { tag ->
            onNodeWithTag(tag).assertExists()
        }
        // 2.8.0 keepers still present beside the restored row.
        onNodeWithTag("volume_mute").assertExists()
        onNodeWithTag("player_ready").assertDoesNotExist() // not in a room in this fixture
    }

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
        waitUntil("the stream frame", timeoutMillis = 5_000) { frames.asked.isNotEmpty() }
        assertTrue(frames.asked.last().first.startsWith("https://example.com/"))
        assertEquals(300.0, frames.asked.last().second, 3.0)
        waitUntil("the stream picture", timeoutMillis = 5_000) { onAllNodesWithTag("seek_frame").fetchSemanticsNodes().isNotEmpty() }
        frames.asked.clear()

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
        onNodeWithTag("pip").assertContentDescriptionEquals("Picture-in-picture (F12)")
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

    @Test
    fun `portrait player title expands to show full details and collapses on toggle`() = runComposeUiTest {
        val testFile = File(tmp.root, "Attack on Titan Final Season - The Rumbling (1080p HEVC Dual-Audio AAC).mkv").apply {
            writeBytes(ByteArray(1024))
        }
        app.playlist.playLocal(testFile.toURI().toString(), inRoom = false)

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

        onNodeWithTag("title_card").assertExists()
        onNodeWithTag("full_title", useUnmergedTree = true).assertExists()
        onNodeWithTag("title_expand").assertExists()
        assertTrue("initially collapsed", onAllNodesWithTag("full_specs", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        // Expand title
        onNodeWithTag("title_expand").performMouseInput { click() }
        settle()
        assertTrue("expanded shows full specs", onAllNodesWithTag("full_specs", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        // Collapse title
        onNodeWithTag("title_expand").performMouseInput { click() }
        settle()
        assertTrue("collapsed hides specs again", onAllNodesWithTag("full_specs", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `portrait player displays room details and ready button in dedicated room card without tapping video`() = runComposeUiTest {
        app.sync.setTestRoom(
            RoomState(
                status = ConnectionStatus.CONNECTED,
                room = "AnimeNight",
                username = "Alice",
                isReady = false,
                users = listOf(RoomUser(name = "Alice", room = "AnimeNight", isReady = false), RoomUser(name = "Bob", room = "AnimeNight", isReady = true)),
                rttMillis = 42.0,
            )
        )

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

        // Room card and ready button exist in the portrait bottom section
        onNodeWithTag("portrait_room_card").assertExists()
        onNodeWithTag("player_ready").assertExists()

        // Toggling ready works from this dedicated position
        onNodeWithTag("player_ready").performMouseInput { click() }
        settle()
        assertTrue("ready toggled to true", app.sync.room.value.isReady == true)

        // Video overlay auto-hides after timeout
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertFalse("video overlay auto-hided", onAllNodesWithTag("video_overlay").fetchSemanticsNodes().isNotEmpty())

        // Room card and ready button stay visible even when video overlay is hidden
        onNodeWithTag("portrait_room_card").assertExists()
        onNodeWithTag("player_ready").assertExists()
    }

    @Test
    fun `phone portrait Menus is fully on screen and opens sheets, Mic is 48dp beside Ready`() = runComposeUiTest {
        app.deviceKind = DeviceKind.PHONE
        player.state.value = player.state.value.copy(paused = true)
        app.sync.setTestRoom(
            RoomState(
                status = ConnectionStatus.CONNECTED,
                room = "AnimeNight",
                username = "Alice",
                isReady = false,
                users = listOf(RoomUser(name = "Alice", room = "AnimeNight", isReady = false)),
            )
        )
        forceVoiceConnected()

        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        var densityDpi = 1f
        setContent {
            densityDpi = LocalDensity.current.density
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                // Narrow phone portrait — the regression that crushed Mic and clipped Menus.
                MobileTheme { Box(Modifier.size(360.dp, 800.dp)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()

        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val menus = onNodeWithTag("player_menus").fetchSemanticsNode().boundsInRoot
        assertTrue("Menus fully on-screen horizontally", menus.left >= root.left - 0.5f && menus.right <= root.right + 0.5f)
        assertTrue("Menus fully on-screen vertically", menus.top >= root.top - 0.5f && menus.bottom <= root.bottom + 0.5f)
        assertTrue("Menus touch target at least 48dp", menus.height >= 48f * densityDpi - 1f)

        onNodeWithTag("player_menus").performMouseInput { click() }
        settle()
        onNodeWithTag("player_sheet").assertExists()
        onNodeWithTag("sheet_tab_room").assertExists()
        onNodeWithTag("sheet_tab_chat").assertExists()
        onNodeWithTag("sheet_tab_playlist").assertExists()
        onNodeWithTag("sheet_tab_audio").assertExists()
        onNodeWithTag("sheet_tab_subtitles").assertExists()

        val mic = onNodeWithTag("player_mic_toggle").fetchSemanticsNode().boundsInRoot
        assertEquals("Mic is 48dp tall", 48f * densityDpi, mic.height, 1.5f)
        assertEquals("Mic is 48dp wide", 48f * densityDpi, mic.width, 1.5f)
        val ready = onNodeWithTag("player_ready").fetchSemanticsNode().boundsInRoot
        assertTrue("Mic stays next to Ready on the room card", kotlin.math.abs(mic.center.y - ready.center.y) < 24f * densityDpi)
        assertTrue("Mic is after Ready", mic.left >= ready.right - 1f)
    }

    @Test
    fun `tablet portrait Menus stays reachable`() = runComposeUiTest {
        app.deviceKind = DeviceKind.TABLET
        player.state.value = player.state.value.copy(paused = true)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(800.dp, 1280.dp)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val menus = onNodeWithTag("player_menus").fetchSemanticsNode().boundsInRoot
        assertTrue(menus.right <= root.right + 0.5f)
        onNodeWithTag("player_menus").performMouseInput { click() }
        settle()
        onNodeWithTag("player_sheet").assertExists()
    }

    @Test
    fun `phone landscape Menus is on the video controls bar`() = runComposeUiTest {
        app.deviceKind = DeviceKind.PHONE
        player.state.value = player.state.value.copy(paused = true)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Player))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(800.dp, 360.dp)) { MobilePlayerScreen(app, nav) } }
            }
        }
        settle()
        onNodeWithTag("player_menus").assertExists()
        onNodeWithTag("player_menus").performMouseInput { click() }
        settle()
        onNodeWithTag("player_sheet").assertExists()
    }

    /** Drive VoiceController's private UI flow so the Mic button is composed in player tests. */
    @Suppress("UNCHECKED_CAST")
    private fun forceVoiceConnected() {
        val field: Field = app.voice.javaClass.getDeclaredField("_ui").apply { isAccessible = true }
        val flow = field.get(app.voice) as MutableStateFlow<VoiceUiState>
        flow.value = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            capture = VoiceCaptureKind.OPEN,
            talkLatched = false,
            selfMuted = true,
        )
    }
}

