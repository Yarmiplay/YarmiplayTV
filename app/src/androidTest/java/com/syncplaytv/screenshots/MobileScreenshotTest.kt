package com.syncplaytv.screenshots

import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syncplaytv.DeviceKind
import com.syncplaytv.DeviceUi
import com.syncplaytv.MainActivity
import com.syncplaytv.TestSupport
import com.syncplaytv.support.AppState
import com.syncplaytv.support.AppState.Companion.onMain
import com.syncplaytv.support.FakeJellyfin
import com.syncplaytv.support.FakeSyncplayServer
import com.syncplaytv.support.Scenarios
import com.syncplaytv.support.Screenshots
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reference screenshots of every phone/tablet screen in fixed states (fake Syncplay server, fake
 * Jellyfin, fixed status bar). Part of the Android safety net: the screens must stay pixel-identical
 * while code moves into the shared module.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MobileScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val scenarios = Scenarios(state, syncplay, jellyfin)
    private var activity: ActivityScenario<MainActivity>? = null
    private val phone get() = DeviceUi.kind(TestSupport.app) == DeviceKind.PHONE

    @Before
    fun setUp() {
        Screenshots.idle = { compose.waitForIdle() }
        scenarios.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        Screenshots.idle = {}
        Screenshots.rotate(landscape = false)
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

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun tapText(text: String) = compose.onNodeWithText(text).performClick()
    private fun await(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 10_000)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 10_000)

    /** A real BACK key, so dialogs and bottom sheets (separate windows) get it too. */
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        activity!!.onActivity {
            it.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        Thread.sleep(800)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Screenshots.check(name)
    }

    @Test
    fun signedOutScreens() {
        launch()
        await("home")
        shot("home")
        tap("tab_Room"); await("field_host")
        shot("connect")
        tap("tab_Settings"); await("settings")
        shot("settings")
        tap("tab_Search"); await("search_field")
        shot("search_signed_out")
        tap("tab_Home"); await("home")
        tap("card_local"); await("open_video")
        shot("local_files_empty")
        back()
        tap("card_jellyfin"); await("jellyfin_url")
        awaitText("Living Room")
        shot("jellyfin_login")
        back()
        if (phone) {
            Screenshots.rotate(landscape = true)
            shot("home_landscape")
            tap("tab_Room"); await("field_host")
            shot("connect_landscape")
            tap("tab_Settings"); await("settings")
            shot("settings_landscape")
            Screenshots.rotate(landscape = false)
        }
        Screenshots.assertAllMatched()
    }

    @Test
    fun signedInAndInRoom() {
        scenarios.busyRoom()
        launch()
        await("home")
        shot("home_full")
        if (phone) {
            Screenshots.rotate(landscape = true)
            shot("home_full_landscape")
            Screenshots.rotate(landscape = false)
        }
        tapText("Shows"); await("grid"); awaitText("Harbor Lights")
        shot("browse_shows")
        tapText("Orbit Station"); awaitText("Docking")
        shot("browse_series")
        compose.onNode(hasText("Docking", substring = true)).performClick()
        await("action_play_room")
        shot("item_actions")
        back()
        compose.waitUntilDoesNotExist(hasTestTag("action_play_room"), 5_000)

        tap("tab_Search"); await("search_field")
        compose.onNodeWithTag("search_field").performTextInput("orbit")
        hideKeyboard()
        compose.mainClock.advanceTimeBy(1_000) // the search debounce runs on the test clock
        awaitText("Docking")
        shot("search_results")

        tap("tab_Home"); await("home")
        tap("card_local"); await("local_list"); awaitText("Paper Boats")
        shot("local_files")
        tapText("Orbit Station S01E01.mkv"); await("local_play_room")
        shot("local_file_actions")
        back()
        compose.waitUntilDoesNotExist(hasTestTag("local_play_room"), 5_000)

        tap("tab_Room"); await("disconnect")
        shot("room")
        compose.onNodeWithTag("chat_field").performScrollTo()
        shot("room_chat")
        tap("tab_Settings"); await("settings")
        shot("settings_signed_in")
        tap("tab_Home"); await("home")
        tap("card_jellyfin"); await("jellyfin_signout")
        shot("jellyfin_signed_in")
        Screenshots.assertAllMatched()
    }

    @Test
    fun playerAndSheets() {
        scenarios.clipPlayingInRoom()
        launch()
        await("player_controls"); await("play_pause")
        scenarios.awaitCacheSettled()
        shot("player")
        val sheets = listOf("Shared playlist" to "playlist", "Room" to "room", "Chat" to "chat", "Audio" to "audio", "Subtitles" to "subtitles")
        for ((label, slug) in sheets) {
            tap("sheet_$label"); await("player_sheet")
            shot("player_sheet_$slug")
            back()
            compose.waitUntilDoesNotExist(hasTestTag("player_sheet"), 5_000)
        }
        Screenshots.assertAllMatched()
    }

    @Test
    fun playerStatusCards() {
        scenarios.loadingStream()
        launch()
        await("player"); awaitText("Loading")
        shot("player_loading")

        closeActivity()
        onMain { TestSupport.container.playlist.stop() }
        scenarios.failedStream()
        launch()
        await("not_found")
        shot("player_failed")

        closeActivity()
        onMain { TestSupport.container.playlist.stop() }
        scenarios.fileNotFound()
        launch()
        await("not_found"); await("pick_file")
        shot("player_not_found")
        tapText("Dismiss"); awaitText("Nothing playing")
        shot("player_nothing_playing")
        Screenshots.assertAllMatched()
    }

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
