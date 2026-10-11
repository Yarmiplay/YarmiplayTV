package com.yarmiplaytv

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.support.AppState
import com.yarmiplaytv.support.AppState.Companion.waitUntil
import com.yarmiplaytv.support.FakeSyncplayServer
import com.yarmiplaytv.sync.SyncController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Syncplay's managed rooms in the room sheet: identifying as operator, marking others ready, creating a room. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ManagedRoomUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val container get() = TestSupport.container
    private var activity: ActivityScenario<MainActivity>? = null
    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        syncplay.reset()
        state.baseline()
    }

    @After
    fun tearDown() {
        activity?.close()
        val server = SyncController.serverKey("127.0.0.1", syncplay.port)
        runBlocking { created.forEach { container.settingsStore.saveRoomPassword("$server/$it", null) } }
        container.sync.disconnect()
    }

    private fun openRoomSheet() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 10_000)
        compose.onNodeWithTag("tab_Room").performClick()
    }

    @Test
    fun identifyingWithThePasswordMakesUsAnOperatorWhoCanMarkOthersReady() {
        val managed = syncplay.makeManaged("movie night", "AB-123-456")
        created += managed
        syncplay.addPeer("Alex", ready = true, operator = true)
        state.joinRoom(syncplay, managed)
        openRoomSheet()

        compose.waitUntilAtLeastOneExists(hasText("Managed room · only operators control playback and the playlist"), 10_000)
        compose.onNodeWithText("Alex ★ operator").assertExists()
        compose.onNodeWithText("🔒 movie night").assertExists()
        assertTrue(container.sync.room.value.followsOperators)

        compose.onNodeWithTag("operator_password_field").performScrollTo().performTextInput("ab-123-456")
        compose.onNodeWithTag("identify_operator").performClick()
        waitUntil(10_000) { container.sync.room.value.isOperator }
        compose.waitUntilAtLeastOneExists(hasText("Operator password: AB-123-456"), 10_000)
        assertTrue("Sam" in syncplay.operatorNames)
        assertTrue(container.sync.feed.value.any { it.text == "You are now a room operator" })

        compose.onNodeWithTag("mark_ready_Alex").performClick()
        waitUntil(10_000) { container.sync.feed.value.any { it.text == "Sam marked Alex not ready" } }
        waitUntil(5_000) { container.sync.room.value.users.first { it.name == "Alex" }.isReady == false }
    }

    @Test
    fun aWrongPasswordSaysSoAndChangesNothing() {
        val managed = syncplay.makeManaged("movie night", "AB-123-456")
        created += managed
        state.joinRoom(syncplay, managed)
        openRoomSheet()
        compose.waitUntilExactlyOneExists(hasTestTag("operator_password_field"), 10_000)
        compose.onNodeWithTag("operator_password_field").performScrollTo().performTextInput("ZZ-000-000")
        compose.onNodeWithTag("identify_operator").performClick()
        waitUntil(10_000) { container.sync.feed.value.any { it.text == "Wrong room password" } }
        assertTrue(!container.sync.room.value.isOperator)
    }

    @Test
    fun creatingAManagedRoomJoinsItAsItsOperator() {
        state.joinRoom(syncplay)
        openRoomSheet()
        compose.waitUntilExactlyOneExists(hasTestTag("managed_create_field"), 10_000)
        compose.onNodeWithTag("managed_create_field").performScrollTo().performTextInput("film club")
        compose.onNodeWithTag("managed_create").performClick()

        val room = "+film club:${FakeSyncplayServer.MANAGED_HASH}"
        created += room
        waitUntil(10_000) { container.sync.room.value.room == room && container.sync.room.value.isOperator }
        val password = container.sync.savedOperatorPassword(room)!!
        assertTrue(password, Regex("[A-Z]{2}-\\d{3}-\\d{3}").matches(password))
        assertEquals(room, syncplay.room)
        compose.waitUntilAtLeastOneExists(hasText("Managed room · you're an operator"), 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Operator password: $password"), 10_000)
        assertTrue(container.sync.feed.value.any { it.text.startsWith("Created managed room 'film club'") })
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
