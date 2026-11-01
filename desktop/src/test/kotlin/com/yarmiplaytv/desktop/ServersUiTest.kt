package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
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
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.ui.mobile.MobileServersScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.serverTag
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/** The Servers screen as the desktop window shows it: two Jellyfin servers and a Plex one, then signing out of one. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ServersUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    // Nothing listens on port 1, so the index builds and the sign-out call fail straight away.
    private fun jellyfin(id: String, name: String) =
        JellyfinSession("http://127.0.0.1:1/$id", name, id, "u-$id", "sam", "token-$id")

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
        )
    }

    @After
    fun tearDown() {
        app.scope.cancel()
        runBlocking { withTimeout(5_000) { app.scope.coroutineContext.job.join() } }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    @Test
    fun `every server is listed and signing out removes only that one`() = runComposeUiTest {
        val living = jellyfin("living", "Living room")
        val office = jellyfin("office", "Office")
        val den = PlexSession("http://127.0.0.1:1/den", "Den", "den", "sam", "server-token", "account-token")
        val livingTag = serverTag(JellyfinSource.keyOf(living))
        val officeTag = serverTag(JellyfinSource.keyOf(office))
        val denTag = serverTag(PlexSource.keyOf(den))
        onMain {
            app.addJellyfin(living)
            app.addJellyfin(office)
            app.addPlex(den)
        }

        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.Servers))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme {
                    Box(Modifier.size(420.dp, 720.dp).background(AppColors.Background)) { MobileServersScreen(app, nav) }
                }
            }
        }

        listOf(livingTag, officeTag, denTag).forEach { onNodeWithTag("server_$it").assertIsDisplayed() }
        onNodeWithText("Living room").assertIsDisplayed()
        onNodeWithText("Office").assertIsDisplayed()

        onNodeWithTag("signout_$officeTag").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("server_$officeTag").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("server_$livingTag").assertIsDisplayed()
        onNodeWithTag("server_$denTag").assertIsDisplayed()

        val saved = runBlocking {
            withTimeout(5_000) {
                var s = app.settingsStore.current()
                // The servers are saved one by one: wait for the Plex one too, not just the sign-out.
                while (s.jellyfinServers.size != 1 || s.plexServers.isEmpty()) { delay(20); s = app.settingsStore.current() }
                s
            }
        }
        assertEquals(listOf("living"), saved.jellyfinServers.map { it.serverId })
        assertEquals(listOf("den"), saved.plexServers.map { it.machineId })

        onNodeWithTag("add_plex").performClick()
        assertEquals(Screen.PlexLogin, nav.current)
    }
}
