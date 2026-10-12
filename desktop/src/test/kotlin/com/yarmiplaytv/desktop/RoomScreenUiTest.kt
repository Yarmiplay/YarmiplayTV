package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.ui.mobile.MobileRoomScreen
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** The "Join a Syncplay room" screen on desktop: rooms you know beside the form; phones stack them. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RoomScreenUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = desktopSettingsStore(tmp.root, storeScope)
        runBlocking {
            store.saveRoom(SavedRoom("myname.duckdns.org", 8999, true, "Sam", "anime-club", label = "Anime night", lastUsedMillis = 2))
            store.saveRoomVisit(SavedRoom("syncplay.pl", 8997, true, "Sam", "movie-night", lastUsedMillis = 1))
        }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
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

    private fun ComposeUiTest.show(width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.SyncplayConnect))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, height.dp)) { MobileRoomScreen(app, nav) } }
            }
        }
        waitForIdle()
    }

    @Test
    fun `wide windows show the known rooms beside the form, and a known room fills it`() = runComposeUiTest {
        show(1280, 800)
        onNodeWithTag("join_two_panes").assertIsDisplayed()
        onNodeWithTag("known_room_anime-club").assertIsDisplayed()
        onNodeWithTag("known_room_movie-night").assertIsDisplayed()
        screenshot("join_wide")

        onNodeWithTag("public_8996").performClick()
        onNodeWithTag("field_host").assertTextEquals("Server", "syncplay.pl", includeEditableText = true)
        onNodeWithTag("field_port").assertTextEquals("Port", "8996", includeEditableText = true)

        onNodeWithTag("more_options").performClick()
        onNodeWithTag("field_server_password").assertIsDisplayed()
    }

    @Test
    fun `narrow windows stack the rooms above the form`() = runComposeUiTest {
        show(420, 900)
        onNodeWithTag("join_card").assertIsDisplayed()
        onNodeWithTag("join_two_panes").assertDoesNotExist()
        screenshot("join_narrow")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
