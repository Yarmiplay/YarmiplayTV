package com.yarmiplaytv

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.support.AppState
import com.yarmiplaytv.support.AppState.Companion.waitUntil
import com.yarmiplaytv.support.FakeJellyfin
import com.yarmiplaytv.support.FakeSyncplayServer
import com.yarmiplaytv.support.Scenarios
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The player's previous and next buttons move the room's playlist. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerSkipTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val container get() = TestSupport.container
    private val scenarios = Scenarios(state, syncplay, jellyfin)
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        scenarios.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        scenarios.tearDown()
    }

    @Test
    fun previousAndNextSelectTheNeighbouringEntriesForTheRoom() {
        // The clip is the second of three entries.
        scenarios.clipPlayingInRoom()
        activity = ActivityScenario.launch(MainActivity::class.java)
        // A file playing opens the player.
        compose.waitUntilExactlyOneExists(hasTestTag("player"), 10_000)
        if (!compose.onAllNodesExist("skip_next")) compose.onNodeWithTag("player_gestures").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("skip_next"), 5_000)
        compose.onNodeWithTag("skip_previous").assertIsEnabled()

        compose.onNodeWithTag("skip_next").assertIsEnabled().performClick()
        waitUntil(10_000) { container.sync.room.value.playlistIndex == 2 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesExist(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private val state = AppState()
        private lateinit var syncplay: FakeSyncplayServer
        private lateinit var jellyfin: FakeJellyfin

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            assumeTrue("phone/tablet only", DeviceUi.kind(TestSupport.app) != DeviceKind.TV)
            syncplay = FakeSyncplayServer()
            jellyfin = FakeJellyfin()
            state.snapshot()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            if (!::syncplay.isInitialized) return
            syncplay.close()
            jellyfin.close()
            state.restore()
        }
    }
}
