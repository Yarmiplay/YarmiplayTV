package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileRoomScreen
import com.yarmiplaytv.ui.mobile.MobileSettingsScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop pictures for the Microsoft Store listing (1920x1080). Writes
 * docs/store/screenshots only when YARMIPLAYTV_STORE_SHOTS=1, so a normal test run does nothing.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class StoreListingScreenshots {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        if (System.getenv("YARMIPLAYTV_STORE_SHOTS") != "1") return
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = desktopSettingsStore(tmp.root, storeScope)
        runBlocking {
            store.saveSyncplay(SyncplayProfile(username = "Sam"))
            store.saveRoom(SavedRoom("myname.duckdns.org", 8999, true, "Sam", "anime-club", label = "Anime night", lastUsedMillis = 2))
            store.saveRoomVisit(SavedRoom("syncplay.pl", 8997, true, "Sam", "movie-night", lastUsedMillis = 1))
        }
        app = AppContainer(
            settingsStore = store,
            deviceName = "desk",
            appVersion = "2.5.7",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            malClientId = "store-shots",
        ).also { it.deviceKind = DeviceKind.DESKTOP }
    }

    @After
    fun tearDown() {
        if (System.getenv("YARMIPLAYTV_STORE_SHOTS") != "1") return
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    @Test
    fun `desktop store listing`() = runDesktopComposeUiTest(1920, 1080) {
        if (System.getenv("YARMIPLAYTV_STORE_SHOTS") != "1") return@runDesktopComposeUiTest
        val module = File(System.getProperty("user.dir"))
        val root = if (File(module, "docs/store").isDirectory) module else module.parentFile
        val out = File(root, "docs/store/screenshots")
        show { MobileRoot(app, DeviceKind.DESKTOP) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        shot(out, "01-home")

        runBlocking { app.settingsStore.saveTheme("sky") }
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "sky" }
        waitForIdle()
        shot(out, "02-home-sky")

        runBlocking { app.settingsStore.saveTheme("grass") }
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "grass" }
        waitForIdle()
        shot(out, "03-home-grass")

        runBlocking { app.settingsStore.saveTheme("dark") }
        show { MobileRoomScreen(app, Navigator(mutableStateListOf(Screen.Home, Screen.SyncplayConnect))) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("known_room_anime-club").fetchSemanticsNodes().isNotEmpty() }
        shot(out, "04-join")

        show { MobileSettingsScreen(app, Navigator(mutableStateListOf(Screen.Settings))) }
        onNodeWithTag("settings_category_appearance").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("theme_sky").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("theme_snow").performClick()
        waitUntil(timeoutMillis = 5_000) { app.settings.value.theme == "snow" }
        waitForIdle()
        shot(out, "05-appearance")
    }

    private fun ComposeUiTest.show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme(appPalette(app)) { Box(Modifier.size(1920.dp, 1080.dp)) { content() } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shot(dir: File, name: String) {
        dir.mkdirs()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }
}
