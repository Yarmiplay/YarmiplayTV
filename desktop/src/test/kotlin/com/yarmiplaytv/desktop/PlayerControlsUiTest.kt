package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
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
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/** The player's controls on desktop: they hide quickly while a video plays, and can be hidden by hand. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerControlsUiTest {
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
        player.state.value = PlaybackState(fileLoaded = true, duration = 600.0, paused = false)
    }

    @After
    fun tearDown() {
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

    private fun ComposeUiTest.controlsShowing() = onAllNodesWithTag("player_controls").fetchSemanticsNodes().isNotEmpty()

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
}
