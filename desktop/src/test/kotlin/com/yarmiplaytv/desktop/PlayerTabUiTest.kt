package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/** The navigation rail's Player entry opens the player from any screen, and Esc goes back to where it was opened. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerTabUiTest {
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
            settingsStore = desktopSettingsStore(tmp.root.resolve("app"), storeScope),
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        ).also { it.deviceKind = DeviceKind.DESKTOP }
    }

    @After
    fun tearDown() {
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun ComposeUiTest.showApp() {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(1100.dp, 700.dp)) { MobileRoot(app, DeviceKind.DESKTOP) } }
            }
        }
    }

    private fun ComposeUiTest.showing(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the Player entry opens the player over the current screen`() = runComposeUiTest {
        showApp()
        listOf("Home", "Player", "Search", "Room", "Settings").forEach { onNodeWithTag("tab_$it").assertIsDisplayed() }

        onNodeWithTag("tab_Settings").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("settings") }
        onNodeWithTag("tab_Player").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("player") }
        // Nothing is playing yet: the player offers to open a video or browse.
        onNodeWithText("Open a video").assertIsDisplayed()
        onNodeWithText("Browse").assertIsDisplayed()
        assertTrue("the rail is covered by the player", !showing("tab_Home"))

        runOnUiThread { assertTrue(DesktopBack.dispatch()) }
        waitUntil(timeoutMillis = 5_000) { showing("settings") }
        assertTrue(!showing("player"))
    }

    @Test
    fun `opening the player again from the rail doesn't stack a second one`() = runComposeUiTest {
        showApp()
        onNodeWithTag("tab_Player").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("player") }
        runOnUiThread { DesktopBack.dispatch() }
        waitUntil(timeoutMillis = 5_000) { showing("home") }
        onNodeWithTag("tab_Player").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("player") }
        runOnUiThread { DesktopBack.dispatch() }
        waitUntil(timeoutMillis = 5_000) { showing("home") }
        // Back from Home has nowhere to go.
        runOnUiThread { assertTrue(!DesktopBack.dispatch()) }
    }
}
