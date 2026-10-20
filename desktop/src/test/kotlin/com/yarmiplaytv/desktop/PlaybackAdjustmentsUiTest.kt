package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.ui.mobile.LocalTvPlaybackControls
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.VideoAdjustments
import com.yarmiplaytv.ui.theme.Palettes
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The mpv adjustment rows. On TV they use the app's colours and the D-pad; on desktop the same rows stay material
 * buttons that a click still drives.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlaybackAdjustmentsUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private val player = RecordingPlayer()

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
    }

    @After
    fun tearDown() {
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `tv rows use theme text and the remote moves and activates them`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var focusManager: FocusManager? = null
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumed(), LocalTvPlaybackControls provides true) {
                focusManager = LocalFocusManager.current
                Box(Modifier.size(720.dp, 480.dp).background(Palettes.Dark.surface)) {
                    VideoAdjustments(app)
                }
            }
        }
        mainClock.advanceTimeBy(100)
        waitForIdle()

        val speed = onNodeWithText("Speed", substring = true)
        val pixels = speed.captureToImage().toPixelMap()
        var light = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.alpha > 0.5f && c.red > 0.75f && c.green > 0.75f && c.blue > 0.75f) light++
            }
        }
        assertTrue("speed label is theme text, not black on the dark menu", light > 8)

        onNodeWithContentDescription("Reset speed").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onNodeWithContentDescription("Reset speed").assertIsFocused()
        val move = checkNotNull(focusManager)
        step(move, FocusDirection.Right, "Slower")
        step(move, FocusDirection.Right, "Faster")
        step(move, FocusDirection.Down, "Reset zoom")
        step(move, FocusDirection.Up, "Reset speed")
        onNodeWithContentDescription("Reset speed").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(listOf(1.0), player.speeds)
    }

    @Test
    fun `desktop rows still reset speed from a click`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumed()) {
                MobileTheme {
                    Box(Modifier.size(720.dp, 480.dp).background(Color(0xFF171B22))) {
                        VideoAdjustments(app)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(100)
        waitForIdle()
        onNodeWithContentDescription("Reset speed").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithContentDescription("Faster").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1.0, player.speeds.first(), 0.0)
        assertTrue(player.speeds.size >= 2)
    }

    /** The same search a TV remote's D-pad runs. */
    private fun ComposeUiTest.step(focus: FocusManager, direction: FocusDirection, expected: String) {
        runOnUiThread { assertTrue(focus.moveFocus(direction)) }
        mainClock.advanceTimeBy(50)
        waitForIdle()
        val focused = onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().ifEmpty { "unlabelled" }
        }
        assertEquals(expected, focused)
    }

    private fun resumed(): LifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private class RecordingPlayer : Player {
        override val state = MutableStateFlow(PlaybackState(speed = 1.0))
        override val tracks = MutableStateFlow<List<Track>>(emptyList())
        override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)
        val speeds = mutableListOf<Double>()
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) = Unit
        override fun stop() = Unit
        override fun setPaused(paused: Boolean) = Unit
        override fun seek(position: Double) = Unit
        override fun seekRelative(offset: Double) = Unit
        override fun setSpeed(speed: Double) { speeds += speed }
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun showText(text: String, durationMs: Int) = Unit
        override val isFileLoaded = false
        override val isPaused = true
        override val duration = 0.0
        override fun currentPosition() = 0.0
    }
}
