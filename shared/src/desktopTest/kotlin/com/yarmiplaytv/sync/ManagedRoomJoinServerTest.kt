package com.yarmiplaytv.sync

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.shared.SyncplayConnectModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/**
 * Creating a managed room from the join form, against a real syncplay-server: run with SYNCPLAY_TEST_SERVER=host:port,
 * e.g. after starting scripts/local-syncplay-server.ps1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagedRoomJoinServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val base = "club" + System.nanoTime() % 1_000_000
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root.resolve("app"), storeScope),
            deviceName = "test",
            appVersion = "test",
            createPlayer = { FakePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
    }

    @After
    fun tearDown() {
        if (!::app.isInitialized) return
        onMain { app.sync.disconnect() }
        app.scope.cancel()
        runBlocking { withTimeout(5_000) { app.scope.coroutineContext.job.join() } }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(10_000) { while (!onMain(condition)) delay(20) } }
            .onFailure { throw AssertionError("Timed out waiting for $what (${onMain { app.sync.room.value }})") }
    }

    private fun form(room: String) = onMain {
        SyncplayConnectModel(app).apply {
            val (h, p) = server.split(":")
            host = h
            port = p
            username = "alice"
            this.room = room
        }
    }

    @Test
    fun `the join form creates a managed room, joins it as its operator and saves its full name`() {
        val model = form(base)
        // The first join asks for the community rules; agreeing goes on with creating the room.
        assertFalse(onMain { model.createManaged() })
        assertTrue(onMain { model.askRules })
        assertTrue(onMain { model.acceptRules() })

        awaitTrue("the managed room") { ManagedRooms.isManaged(app.sync.room.value.room) && app.sync.room.value.isOperator }
        val room = onMain { app.sync.room.value.room }
        assertEquals(base, ManagedRooms.display(room))
        awaitTrue("the profile to point at it") { app.settings.value.syncplay.room == room }
        assertEquals(room, onMain { model.room })
        val password = onMain { app.sync.savedOperatorPassword(room) }!!
        assertTrue(password, ManagedRooms.isValidPassword(password))

        // Reconnecting goes straight back, still as its operator.
        onMain { app.sync.reconnect() }
        awaitTrue("the reconnect") { app.sync.room.value.room == room && app.sync.room.value.isOperator }
    }

    @Test
    fun `a managed room's full name or a name that's too long isn't created`() {
        val managed = form("+$base:ABCDEF123456")
        assertFalse(onMain { managed.createManaged() })
        assertEquals("That's already a managed room; join it with Connect", onMain { managed.error })

        val long = form("x".repeat(ManagedRooms.MAX_BASE_NAME_LENGTH + 1))
        assertFalse(onMain { long.createManaged() })
        assertEquals("A managed room's name has at most ${ManagedRooms.MAX_BASE_NAME_LENGTH} characters", onMain { long.error })
        assertFalse(onMain { app.sync.isActive })
    }
}
