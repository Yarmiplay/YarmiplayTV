package com.syncplaytv.tv

import android.graphics.RectF
import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syncplaytv.DeviceKind
import com.syncplaytv.DeviceUi
import com.syncplaytv.MainActivity
import com.syncplaytv.TestSupport
import com.syncplaytv.sync.PlaylistStatus
import com.syncplaytv.support.AppState
import com.syncplaytv.support.AppState.Companion.onMain
import com.syncplaytv.support.AppState.Companion.waitUntil
import com.syncplaytv.support.FakeJellyfin
import com.syncplaytv.support.FakeSyncplayServer
import com.syncplaytv.support.Scenarios
import com.syncplaytv.support.Screenshots
import com.syncplaytv.support.TvUi
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reference screenshots of every TV screen, player overlay and panel in fixed states. */
@RunWith(AndroidJUnit4::class)
class TvScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val tv = TvUi(compose)
    private val scenarios = Scenarios(state, syncplay, jellyfin)
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        Screenshots.idle = { compose.waitForIdle() }
        scenarios.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        Screenshots.idle = {}
        scenarios.tearDown()
    }

    private fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    /** Close before setting up the next state, or the open activity handles its "open the player" request. */
    private fun closeActivity() {
        activity?.close()
        activity = null
    }

    private fun shot(name: String, ignore: List<RectF> = emptyList()) {
        compose.waitForIdle()
        Screenshots.check(name, ignore = ignore)
    }

    @Test
    fun signedOutScreens() {
        launch()
        tv.await("Join a Syncplay room")
        shot("home")
        tv.click("Join a Syncplay room"); tv.await("Syncplay room")
        shot("connect")
        tv.back(); tv.await("Connect Jellyfin")
        tv.click("Connect Jellyfin"); tv.await("Quick Connect")
        tv.await("Living Room")
        shot("jellyfin_login")
        tv.back(); tv.await("Join a Syncplay room")
        tv.click("Settings"); tv.await("When I unpause")
        shot("settings")
        Screenshots.assertAllMatched()
    }

    @Test
    fun signedInAndInRoom() {
        scenarios.busyRoom()
        launch()
        tv.await("Continue watching")
        shot("home_full")
        tv.click("Shows"); tv.await("Harbor Lights")
        shot("browse_shows")
        tv.click("Orbit Station"); tv.await("Docking")
        shot("browse_series")
        tv.click("Docking"); tv.await("Play for everyone")
        shot("item_actions")
        tv.back(); tv.awaitGone("Play for everyone"); tv.awaitActivityFocus()
        tv.back(); tv.awaitGone("Docking")
        tv.back(); tv.await("Continue watching")
        tv.click("Search"); tv.await("Type to search")
        shot("search")
        tv.back()
        tv.click("Room: ${FakeSyncplayServer.ROOM}"); tv.await("Connected to")
        shot("connect_connected")
        tv.back()
        tv.click("Jellyfin: "); tv.await("Signed in to")
        shot("jellyfin_signed_in")
        Screenshots.assertAllMatched()
    }

    @Test
    fun playerControlsAndPanels() {
        scenarios.clipPlayingInRoom()
        launch()
        waitUntil(10_000) { TestSupport.container.player.state.value.fileLoaded }
        scenarios.awaitCacheSettled()
        shot("player")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER); tv.await("0:00")
        shot("player_controls", ignore = listOf(SEEK_BAR_CACHE_END))
        val panels = listOf("Shared playlist" to "playlist", "Room" to "room", "Chat" to "chat", "Audio" to "audio", "Subtitles" to "subtitles")
        for ((label, slug) in panels) {
            tv.click(label)
            tv.await(
                when (slug) {
                    "playlist" -> "everyone sees these changes"
                    "room" -> "I'm ready"
                    "chat" -> "Message"
                    "audio" -> "Audio"
                    else -> "Off"
                },
            )
            shot("player_panel_$slug")
            tv.back() // closes the panel and the controls
            tv.key(KeyEvent.KEYCODE_DPAD_CENTER); tv.await("0:00")
        }
        Screenshots.assertAllMatched()
    }

    @Test
    fun playerStatusCards() {
        scenarios.loadingStream()
        launch()
        tv.await("Loading")
        shot("player_loading")

        closeActivity()
        onMain { TestSupport.container.playlist.stop() }
        scenarios.failedStream()
        launch()
        tv.await("Couldn't play this file")
        shot("player_failed")

        closeActivity()
        onMain { TestSupport.container.playlist.stop() }
        scenarios.fileNotFound()
        launch()
        tv.await("Couldn't find this file")
        shot("player_not_found")
        tv.click("Dismiss"); tv.await("Nothing playing")
        shot("player_nothing_playing")
        Screenshots.assertAllMatched()
    }

    @Test
    fun pickManuallyFromJellyfin() {
        state.signInJellyfin(jellyfin)
        scenarios.fileNotFound()
        waitUntil(10_000) { (TestSupport.container.playlist.status.value as? PlaylistStatus.NotFound)?.reason?.contains("Screenshot Server") == true }
        launch()
        tv.await("Pick manually")
        shot("player_not_found_jellyfin")
        tv.click("Pick manually"); tv.await("Pick the item to play for")
        compose.mainClock.advanceTimeBy(1_000) // the search debounce runs on the test clock
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Harbor Lights")).fetchSemanticsNodes().size >= 2 }
        shot("search_pick")
        Screenshots.assertAllMatched()
    }

    companion object {
        /** The end of the seek bar's buffered part follows mpv's demuxer cache, which settles a frame apart between runs. */
        private val SEEK_BAR_CACHE_END = RectF(0.80f, 0.765f, 0.95f, 0.79f)

        private val state = AppState()
        private lateinit var syncplay: FakeSyncplayServer
        private lateinit var jellyfin: FakeJellyfin

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            assumeTrue("TV only", DeviceUi.kind(TestSupport.app) == DeviceKind.TV)
            assumeTrue("needs -e screenshots true", Screenshots.enabled)
            syncplay = FakeSyncplayServer()
            jellyfin = FakeJellyfin()
            state.snapshot()
            Screenshots.prepareDevice()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            if (!::syncplay.isInitialized) return
            Screenshots.restoreDevice()
            syncplay.close()
            jellyfin.close()
            state.restore()
        }
    }
}
