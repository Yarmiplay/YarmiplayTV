package com.yarmiplaytv

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.support.AppState
import com.yarmiplaytv.support.FakeJellyfin
import com.yarmiplaytv.ui.shared.serverTag
import com.yarmiplaytv.update.Update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = TestSupport.container
    private lateinit var restoreRoom: () -> Unit

    @Before
    fun setUp() {
        restoreRoom = TestSupport.isolateFromRoom()
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 10_000)
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { container.updates.dismiss(remember = false) }
        restoreRoom()
    }

    @Test
    fun phoneGetsTouchUiWithBottomNavigation() {
        assertNotEquals(DeviceKind.TV, compose.activity.deviceKind)
        listOf("Home", "Search", "Room", "Settings").forEach { compose.onNodeWithTag("tab_$it").assertIsDisplayed() }
        compose.onNodeWithTag("card_room").assertIsDisplayed()
        compose.onNodeWithTag("card_local").assertIsDisplayed()
    }

    @Test
    fun tabsSwitchScreensAndBackReturnsHome() {
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("settings"), 5_000)
        compose.onNodeWithTag("tab_Room").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("field_host"), 5_000)
        compose.onNodeWithTag("tab_Search").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("search_field"), 5_000)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 5_000)
    }

    @Test
    fun homeCardsOpenLocalFilesJellyfinAndPlex() {
        compose.onNodeWithTag("card_local").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("open_video"), 5_000)
        compose.onNodeWithTag("add_folder").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("card_jellyfin").performClick()
        // With a server of that kind already added, the card opens the server list instead.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("jellyfin_url")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("servers")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 5_000)
        compose.onNodeWithTag("card_plex").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("plex_link")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("servers")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addTwoServersAndSignOutOfOne() {
        val first = FakeJellyfin()
        val second = FakeJellyfin(FakeJellyfin.PORT + 1, serverId = "fake-server-2", serverName = "Bedroom Server")
        val state = AppState()
        try {
            state.signInJellyfin(first)
            state.signInJellyfin(second)
            compose.onNodeWithTag("card_jellyfin").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("servers"), 5_000)
            compose.onNodeWithTag("server_${serverTag(first.key)}").assertIsDisplayed()
            compose.onNodeWithTag("server_${serverTag(second.key)}").assertIsDisplayed()

            compose.onNodeWithTag("signout_${serverTag(first.key)}").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("server_${serverTag(first.key)}"), 5_000)
            compose.onNodeWithTag("server_${serverTag(second.key)}").assertIsDisplayed()
            compose.waitUntil(5_000) {
                val ids = container.settings.value.jellyfinServers.map { it.serverId }
                first.session.serverId !in ids && second.session.serverId in ids
            }
        } finally {
            compose.runOnUiThread { listOf(first.key, second.key).forEach(container::removeServer) }
            first.close()
            second.close()
        }
    }

    @Test
    fun reportingToggleIsPersisted() {
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("settings"), 5_000)
        val before = container.settings.value.reportPlayback
        compose.onNodeWithTag("toggle_report_playback").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.settings.value.reportPlayback == !before }
        compose.onNodeWithTag("toggle_report_playback").performClick()
        compose.waitUntil(5_000) { container.settings.value.reportPlayback == before }
    }

    @Test
    fun connectFormValidatesAndPersistsProfile() {
        compose.onNodeWithTag("tab_Room").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("field_room"), 5_000)

        compose.onNodeWithTag("field_room").performTextReplacement("")
        compose.onNodeWithTag("connect").performClick()
        compose.onNodeWithText("Enter a room name").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("field_host").performTextReplacement("127.0.0.1")
        compose.onNodeWithTag("field_port").performTextReplacement("9")
        compose.onNodeWithTag("field_name").performTextReplacement("Instrumented")
        compose.onNodeWithTag("field_room").performTextReplacement("instrumented-room")
        compose.onNodeWithTag("connect").performClick()
        compose.waitUntil(5_000) {
            val p = container.settings.value.syncplay
            p.host == "127.0.0.1" && p.port == 9 && p.username == "Instrumented" && p.room == "instrumented-room"
        }
        container.sync.disconnect()
    }

    @Test
    fun licensesOpenFromSettings() {
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("settings"), 5_000)
        compose.onNodeWithText("Open-source licenses").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("licenses"), 5_000)
        compose.onNodeWithText("FFmpeg").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasTestTag("settings"), 5_000)
    }

    @Test
    fun settingsToggleIsPersisted() {
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("toggle_ready_at_start"), 5_000)
        val before = container.settings.value.sync.readyAtStart
        compose.onNodeWithTag("toggle_ready_at_start").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.settings.value.sync.readyAtStart == !before }
        compose.onNodeWithTag("toggle_ready_at_start").performClick()
        compose.waitUntil(5_000) { container.settings.value.sync.readyAtStart == before }
    }

    @Test
    fun updateNoticeShowsAndIsDismissed() {
        val dismissed = container.settings.value.dismissedUpdate
        compose.runOnUiThread { container.updates.show(TEST_UPDATE) }
        compose.waitUntilExactlyOneExists(hasTestTag("update_banner"), 5_000)
        compose.onNodeWithText("YarmiplayTV 99.0 is available").assertIsDisplayed()
        compose.onNodeWithText("Download page").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("update_banner"), 5_000)
        compose.waitUntil(5_000) { container.settings.value.dismissedUpdate == "99.0" }
        runBlocking { container.settingsStore.saveDismissedUpdate(dismissed) }
    }

    @Test
    fun updateCheckCanBeTurnedOff() {
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("settings"), 5_000)
        val before = container.settings.value.checkForUpdates
        compose.onNodeWithTag("toggle_check_updates").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.settings.value.checkForUpdates == !before }
        compose.onNodeWithTag("toggle_check_updates").performClick()
        compose.waitUntil(5_000) { container.settings.value.checkForUpdates == before }
    }

    private companion object {
        val TEST_UPDATE = Update("99.0", "https://yarmiplay.github.io/YarmiplayTV/", "https://yarmiplay.github.io/YarmiplayTV/YarmiplayTV.apk", null)
    }
}
