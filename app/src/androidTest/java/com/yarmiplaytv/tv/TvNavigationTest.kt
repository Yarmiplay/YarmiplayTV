package com.yarmiplaytv.tv

import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.DeviceUi
import com.yarmiplaytv.MainActivity
import com.yarmiplaytv.TestSupport
import com.yarmiplaytv.support.AppState
import com.yarmiplaytv.support.AppState.Companion.onMain
import com.yarmiplaytv.support.AppState.Companion.waitUntil
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.support.FakeJellyfin
import com.yarmiplaytv.support.FakePlex
import com.yarmiplaytv.support.FakeSyncplayServer
import com.yarmiplaytv.support.Scenarios
import com.yarmiplaytv.support.Screenshots
import com.yarmiplaytv.support.TvUi
import com.yarmiplaytv.update.Update
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D-pad navigation through the TV UI: home, connect, settings, browse, the player panels and local playback. */
@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val tv = TvUi(compose)
    private val scenarios = Scenarios(state, syncplay, jellyfin)
    private var activity: ActivityScenario<MainActivity>? = null
    private val container get() = TestSupport.container

    @Before
    fun setUp() {
        Screenshots.idle = { compose.waitForIdle() }
        scenarios.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        scenarios.tearDown()
        Screenshots.idle = {}
    }

    private fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @Test
    fun homeTilesAndConnectForm() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Connect Jellyfin")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Connect Plex")
        tv.key(KeyEvent.KEYCODE_DPAD_LEFT, times = 2)
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.await("Syncplay room")
        tv.awaitFocus(AppState.PROFILE.host)
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus(AppState.PROFILE.username)
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus(AppState.PROFILE.room)
        tv.back()
        tv.awaitFocus("Join a Syncplay room")
    }

    @Test
    fun settingsToggleWithDpad() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Settings")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        // The categories come first; Syncing's settings show beside them, and Right goes into them.
        tv.awaitFocus("Media")
        tv.click("Syncing")
        tv.await("When I unpause")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("When I unpause")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("Rewind when I'm ahead")
        val before = container.settings.value.sync.rewindOnDesync
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(5_000) { container.settings.value.sync.rewindOnDesync == !before }
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(5_000) { container.settings.value.sync.rewindOnDesync == before }
        tv.back()
        tv.awaitFocus("Join a Syncplay room")
    }

    @Test
    fun controlsHideSettingPresetsAndCustom() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Settings")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Media")
        tv.click("Playback")
        tv.await("Hide player controls after")
        fun hideSeconds() = container.settings.value.playback.controlsHideSeconds
        assertEquals(3, hideSeconds())
        for (expected in listOf(5, 10, 0)) {
            tv.click("Hide player controls after")
            waitUntil(5_000) { hideSeconds() == expected }
        }
        tv.await("Never")
        tv.click("Hide player controls after")
        tv.await("Custom time (seconds)")
        // On the Playback page the custom field comes first, then the language fields.
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("7")
        waitUntil(5_000) { hideSeconds() == 7 }
        tv.await("7 s (custom)")
        tv.click("Hide player controls after")
        waitUntil(5_000) { hideSeconds() == 2 }
        tv.awaitGone("Custom time (seconds)")
    }

    @Test
    fun updateNoticeWithDpad() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        onMain { container.updates.show(Update("99.0", "https://tv.yarmiplay.com/", "https://tv.yarmiplay.com/YarmiplayTV.apk", null)) }
        tv.await("YarmiplayTV 99.0 is available")
        tv.await("To install it, open Downloader and enter tv.yarmiplay.com/a")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("YarmiplayTV 99.0 is available")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Settings")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("YarmiplayTV 99.0 is available")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(5_000) { container.updates.state.value == null && container.settings.value.dismissedUpdate == "99.0" }
        tv.awaitFocus("Join a Syncplay room")
    }

    @Test
    fun licensesScrollWithDpad() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Settings")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Media")
        tv.click("About")
        tv.click("Open-source licenses")
        tv.awaitFocus("mpv")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("FFmpeg")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN, times = 30)
        tv.awaitFocus("LWJGL")
        tv.back()
        tv.awaitFocus("Media")
    }

    @Test
    fun settingsOpensMediaServersWithDpad() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Settings")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        // Media is the first category, and its settings show beside it: Right goes to Media servers.
        tv.awaitFocus("Media")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Add Jellyfin and Plex servers")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.await("No media servers yet")
        tv.awaitFocus("Add Jellyfin server")
        tv.back()
        tv.await("Add Jellyfin and Plex servers")
    }

    @Test
    fun browseLibraryWithDpad() {
        state.signInJellyfin(jellyfin)
        launch()
        tv.await("Libraries")
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("Shows")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Orbit Station")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Harbor Lights")
        tv.key(KeyEvent.KEYCODE_DPAD_LEFT)
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Docking")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Play")
        tv.await("Join a Syncplay room to watch together")
        tv.back()
        tv.awaitFocus("Docking")
        tv.awaitActivityFocus()
        tv.back()
        tv.awaitGone("Docking")
        tv.back()
        tv.await("Libraries")
    }

    @Test
    fun plexLinkAndServersScreens() {
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        tv.awaitFocus("Connect Plex")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.await("Link this TV to your Plex account")
        tv.awaitFocus("Get a link code")
        tv.back()
        tv.await("Connect Plex")

        state.signInPlex(plex)
        tv.click("Plex: Plex Server")
        tv.await("Media servers")
        tv.awaitFocus("Sign out")
        tv.awaitGone("Get a link code")
        tv.back()
        tv.await("Plex: Plex Server")
    }

    @Test
    fun signOutOfOneOfTwoServersWithDpad() {
        state.signInJellyfin(jellyfin)
        state.signInJellyfin(jellyfin2)
        launch()
        tv.awaitFocus("Join a Syncplay room")
        tv.click("Jellyfin: 2 servers")
        tv.await("Media servers")
        tv.await("Bedroom Server")
        tv.awaitFocus("Sign out")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(5_000) { container.servers.value.map { it.key } == listOf(jellyfin2.key) }
        tv.awaitGone("Screenshot Server")
        tv.awaitFocus("Sign out")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("Add Plex server")
        tv.back()
        tv.await("Jellyfin: Bedroom Server")
    }

    @Test
    fun browseBothServersWithDpad() {
        state.signInJellyfin(jellyfin)
        state.signInPlex(plex)
        waitUntil(5_000) { container.mediaSource.value is CompositeMediaSource }
        launch()
        tv.await("Libraries")
        tv.awaitFocus("Join a Syncplay room")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("Shows")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        tv.awaitFocus("Films")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Lantern Festival")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Paper Boats")
        tv.back()
        tv.await("Libraries")
    }

    @Test
    fun playerPanelsWithDpad() {
        scenarios.clipPlayingInRoom()
        launch()
        waitUntil(10_000) { container.player.state.value.fileLoaded }

        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Play/pause")
        // Left to right, all in one row: play/pause, back 10 s, forward 10 s, the playlist's previous and next entry,
        // ready, screenshot, the menus, browse.
        for (next in listOf("Back", "Forward", "Previous in the playlist", "Next in the playlist", "Ready", "Screenshot", "Menus", "Browse")) {
            tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            tv.awaitFocus(next)
        }
        // Menus opens the room the first time.
        tv.key(KeyEvent.KEYCODE_DPAD_LEFT)
        tv.awaitFocus("Menus")
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("I'm ready")
        tv.await("Different size than yours")
        // Up reaches the room's tab, and moving along the tabs switches menu.
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Room")
        tv.key(KeyEvent.KEYCODE_DPAD_LEFT)
        tv.awaitFocus("Shared playlist")
        tv.await("everyone sees these changes"); tv.await("File size differs from Alex's")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        tv.awaitFocus("Chat")
        tv.await("Give me a minute")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Audio")
        tv.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        tv.awaitFocus("Subtitles")
        tv.key(KeyEvent.KEYCODE_DPAD_DOWN)
        tv.awaitFocus("Off")
        // The focus stays in the menu: left goes nowhere behind it.
        tv.key(KeyEvent.KEYCODE_DPAD_LEFT)
        tv.awaitFocus("Off")
        // Back closes it, straight back to the video; up opens the menu used last.
        tv.back()
        tv.awaitGone("Off")
        tv.awaitGone(HIDE_HINT)
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Off")
        // Menu closes it too.
        tv.key(KeyEvent.KEYCODE_MENU)
        tv.awaitGone("Off")
        // Down and Menu each show and hide the controls.
        for (key in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU)) {
            tv.key(key)
            tv.awaitFocus("Play/pause")
            tv.await(HIDE_HINT)
            tv.key(key)
            tv.awaitGone(HIDE_HINT)
        }
        // Up from the controls' buttons opens the menu used last.
        tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.awaitFocus("Play/pause")
        tv.key(KeyEvent.KEYCODE_DPAD_UP)
        tv.awaitFocus("Off")
        tv.awaitGone(HIDE_HINT)
        tv.back()
        tv.awaitGone("Off")
        tv.awaitGone(HIDE_HINT)
        assertEquals(true, container.player.state.value.paused)
    }

    @Test
    fun localClipPlaysOnTv() {
        val uri = TestSupport.insertClip("YarmiplayTV TV Clip - ${System.currentTimeMillis() % 100000}.mp4")
        try {
            launch()
            tv.awaitFocus("Join a Syncplay room")
            container.playlist.playLocal(uri.toString(), inRoom = false)
            waitUntil(20_000) { container.player.state.value.fileLoaded }
            waitUntil(10_000) { !container.player.state.value.paused && container.player.currentPosition() > 0.5 }
            // While playing, the controls hide after 3 s of the test clock, which the position ticker races through.
            onMain { container.player.setPaused(true) }
            tv.key(KeyEvent.KEYCODE_DPAD_CENTER)
            tv.awaitFocus("Play/pause")
            tv.await("YarmiplayTV TV Clip")
        } finally {
            TestSupport.delete(uri)
        }
    }

    companion object {
            private const val HIDE_HINT = "▼ Hide"
        private val state = AppState()
        private lateinit var syncplay: FakeSyncplayServer
        private lateinit var jellyfin: FakeJellyfin
        private lateinit var jellyfin2: FakeJellyfin
        private lateinit var plex: FakePlex

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            assumeTrue("TV only", DeviceUi.kind(TestSupport.app) == DeviceKind.TV)
            syncplay = FakeSyncplayServer()
            jellyfin = FakeJellyfin()
            jellyfin2 = FakeJellyfin(FakeJellyfin.PORT + 1, serverId = "fake-server-2", serverName = "Bedroom Server")
            plex = FakePlex()
            state.snapshot()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            if (!::syncplay.isInitialized) return
            syncplay.close()
            jellyfin.close()
            jellyfin2.close()
            plex.close()
            state.restore()
        }
    }
}
