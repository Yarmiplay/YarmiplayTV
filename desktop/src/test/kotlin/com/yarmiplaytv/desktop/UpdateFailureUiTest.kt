package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.device.FileDeviceKeyStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.UpdateBanner
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.update.Update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.util.concurrent.Executors

/** The home notice for a failed desktop update: the step, the reported cause, and a way to copy it. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UpdateFailureUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    private val detail = "Couldn't replace the app's files (exit code 5): Access is denied " +
        "(C:\\Users\\Dor Farhi\\AppData\\Local\\YarmiplayTV)"

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root, storeScope),
            deviceName = "desk",
            appVersion = "2.9.7",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            deviceKeys = FileDeviceKeyStore(tmp.newFolder("keys")),
        )
        app.updates.platform = "windows"
        app.updates.installer = object : com.yarmiplaytv.update.UpdateInstaller {
            override suspend fun download(update: Update, progress: (Float?) -> Unit) = Unit
            override fun installAfterExit(relaunch: Boolean) = Unit
        }
    }

    @After
    fun tearDown() {
        // AppContainer collects settings on Main. Cancel and join before resetMain, or a
        // collector still reading Main fails the test with "used concurrently with setting it".
        if (::app.isInitialized) app.scope.cancel()
        if (::storeScope.isInitialized) storeScope.cancel()
        runBlocking {
            if (::app.isInitialized) app.scope.coroutineContext[Job]?.join()
            if (::storeScope.isInitialized) storeScope.coroutineContext[Job]?.join()
        }
        if (::main.isInitialized) {
            for (attempt in 0 until 8) {
                if (runCatching { Dispatchers.resetMain() }.isSuccess) break
                if (attempt < 7) Thread.sleep(40)
            }
            main.close()
        }
    }

    @Test
    fun failedUpdateShowsTheCauseAndACopyButton() = runComposeUiTest {
        app.updates.showFailed(
            Update("2.9.8", "https://tv.yarmiplay.com/", "https://tv.yarmiplay.com/YarmiplayTV.msi", "aa"),
            detail,
        )
        show()
        onNodeWithText("Couldn't update to YarmiplayTV 2.9.8").assertIsDisplayed()
        onNode(hasText(detail, substring = true)).assertIsDisplayed()
        onNodeWithText("Dor Farhi", substring = true).assertIsDisplayed()
        onNodeWithTag("update_error_copy").assertIsDisplayed().performClick()
        onNodeWithTag("update_install").assertIsDisplayed()
    }

    @Test
    fun aCheckFailureWithNoPackageStillShowsTheCause() = runComposeUiTest {
        app.updates.showProblem("Couldn't check for updates: the download page answered HTTP 503")
        show()
        onNodeWithTag("update_problem").assertIsDisplayed()
        onNodeWithText("Couldn't check for updates: the download page answered HTTP 503").assertIsDisplayed()
        onNodeWithTag("update_error_copy").assertIsDisplayed().performClick()
        onNodeWithText("Not now").performClick()
        waitForIdle()
        onNodeWithTag("update_problem").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme(appPalette(app)) {
                    Box(Modifier.size(720.dp, 280.dp)) { UpdateBanner(app) }
                }
            }
        }
        waitForIdle()
    }
}
