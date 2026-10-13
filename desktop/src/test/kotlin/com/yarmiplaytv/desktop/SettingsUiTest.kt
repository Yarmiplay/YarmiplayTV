package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sun.net.httpserver.HttpServer
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.device.FileDeviceKeyStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.MobileSettingsScreen
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** Settings on desktop: categories beside the settings on wide windows, and text boxes that keep the caret. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SettingsUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private lateinit var site: HttpServer
    /** The version the fake download page offers. */
    @Volatile private var offered = "1.0"

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/version.json") { exchange ->
                val body = """{"page": "./", "platforms": {"windows": {"version": "$offered", "file": "YarmiplayTV.msi", "sha256": "00"}}}"""
                    .toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        System.setProperty("yarmiplaytv.updateManifest", "http://127.0.0.1:${site.address.port}/version.json")
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root, storeScope),
            deviceName = "desk",
            appVersion = "1.0",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            deviceKeys = FileDeviceKeyStore(tmp.newFolder("keys")),
        )
        app.updates.platform = "windows"
    }

    @After
    fun tearDown() {
        System.clearProperty("yarmiplaytv.updateManifest")
        site.stop(0)
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun ComposeUiTest.show(width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Settings))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(width.dp, height.dp)) { MobileSettingsScreen(app, nav) } }
            }
        }
        waitForIdle()
    }

    @Test
    fun `wide windows list the categories beside the chosen one's settings`() = runComposeUiTest {
        show(1280, 800)
        onNodeWithTag("settings_two_panes").assertIsDisplayed()
        onNodeWithTag("toggle_report_playback").assertIsDisplayed()
        onNodeWithTag("toggle_show_chat").assertDoesNotExist()
        screenshot("settings_wide")

        onNodeWithTag("settings_category_syncing").performClick()
        onNodeWithTag("toggle_show_chat").assertIsDisplayed()
        onNodeWithTag("toggle_report_playback").assertDoesNotExist()

        onNodeWithTag("settings_category_about").performClick()
        onNodeWithTag("about_manual").assertIsDisplayed()
        screenshot("settings_about")
    }

    @Test
    fun `phones list the categories, each opening its own page, and Back returns to the list`() = runComposeUiTest {
        show(420, 900)
        onNodeWithTag("settings_two_panes").assertDoesNotExist()
        onNodeWithTag("toggle_show_chat").assertDoesNotExist()
        screenshot("settings_phone")
        onNodeWithTag("settings_category_syncing").performClick()
        onNodeWithTag("toggle_show_chat").performScrollTo().assertIsDisplayed()
        onNodeWithTag("settings_category_media").assertDoesNotExist()
        screenshot("settings_phone_syncing")
        runOnUiThread { assertTrue(DesktopBack.dispatch()) }
        waitForIdle()
        onNodeWithTag("settings_category_updates").performClick()
        onNodeWithTag("check_updates").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `listening for openings has its own switch, and the mobile data one only while it's on`() = runComposeUiTest {
        show(1280, 800)
        onNodeWithTag("settings_category_playback").performClick()
        onNodeWithTag("toggle_detect_skips").performScrollTo().assertIsDisplayed()
        onNodeWithTag("toggle_detect_any_network").performScrollTo().assertIsDisplayed()
        onNodeWithTag("toggle_detect_skips").performClick()
        waitUntil(timeoutMillis = 5_000) { !app.settings.value.playback.detectSkips }
        waitForIdle()
        onNodeWithTag("toggle_detect_any_network").assertDoesNotExist()
        // Saved, as a later start reads it.
        assertEquals(false, runBlocking { app.settingsStore.current() }.playback.detectSkips)
    }

    @Test
    fun `typing a device name keeps the caret after each letter and saves it once typing stops`() = runComposeUiTest {
        show(1280, 800)
        onNodeWithTag("settings_category_syncing").performClick()
        val field = onNodeWithTag("device_name")
        field.performTextInput("A")
        field.performTextInput("b")
        field.performTextInput("c")
        field.assertTextContains("Abc")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        waitUntil(timeoutMillis = 5_000) { app.settings.value.deviceName == "Abc" }
        // A save landing after more typing doesn't put the old text back.
        field.performTextInput("d")
        mainClock.advanceTimeBy(100)
        field.assertTextContains("Abcd")
        waitUntil(timeoutMillis = 5_000) { app.settings.value.deviceName == "Abcd" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
    }

    @Test
    fun `check for updates says when it's up to date and shows a newer version to install`() = runComposeUiTest {
        show(1280, 800)
        onNodeWithTag("settings_category_updates").performClick()
        onNodeWithTag("check_updates").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("update_check_result") and hasText("You're up to date.")).fetchSemanticsNodes().isNotEmpty() }

        offered = "99.0"
        onNodeWithTag("check_updates").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("YarmiplayTV 99.0 is available")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("99.0", app.updates.state.value?.update?.version)
        onNodeWithTag("update_state").assertIsDisplayed()
        screenshot("settings_updates")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
