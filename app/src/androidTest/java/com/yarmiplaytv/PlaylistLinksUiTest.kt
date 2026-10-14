package com.yarmiplaytv

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.support.AppState
import com.yarmiplaytv.support.AppState.Companion.waitUntil
import com.yarmiplaytv.support.FakeSyncplayServer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Links added to the shared playlist from the Room tab on a phone or tablet. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistLinksUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val container get() = TestSupport.container
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        syncplay.reset()
        state.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        container.sync.disconnect()
    }

    @Test
    fun linksPastedTogetherAreAddedAndAnythingElseIsLeftOut() {
        state.joinRoom(syncplay)
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 10_000)
        compose.onNodeWithTag("tab_Room").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("playlist_add_links"), 10_000)

        compose.onNodeWithTag("playlist_add_links").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("playlist_links_field"), 5_000)
        compose.onNodeWithTag("playlist_links_field").performTextInput("not a link")
        compose.onNodeWithTag("playlist_links_add").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter links starting with http:// or https://"), 5_000)

        compose.onNodeWithTag("playlist_links_field").performTextInput(" https://example.org/a.mp4\nhttps://www.youtube.com/watch?v=abc")
        compose.onNodeWithTag("playlist_links_add").performClick()
        waitUntil(10_000) { container.sync.room.value.playlist.size == 2 }
        assertEquals(listOf("https://example.org/a.mp4", "https://www.youtube.com/watch?v=abc"), container.sync.room.value.playlist)
        // Added, the field closes.
        compose.waitUntilDoesNotExist(hasTestTag("playlist_links_field"), 5_000)
    }

    companion object {
        private val state = AppState()
        private lateinit var syncplay: FakeSyncplayServer

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            assumeTrue("phone/tablet only", DeviceUi.kind(TestSupport.app) != DeviceKind.TV)
            syncplay = FakeSyncplayServer()
            state.snapshot()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            if (!::syncplay.isInitialized) return
            syncplay.close()
            state.restore()
        }
    }
}
