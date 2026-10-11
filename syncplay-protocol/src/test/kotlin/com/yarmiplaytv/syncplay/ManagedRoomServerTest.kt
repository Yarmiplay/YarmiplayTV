package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Managed rooms against a real server: the official syncplay-server (start it with a salt, e.g.
 * `scripts/local-syncplay-server.ps1 -Salt SOMESALT`) or YarmiplayServerTV 1.7.1+. Enabled with
 * SYNCPLAY_TEST_SERVER=host:port.
 */
class ManagedRoomServerTest {
    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val episode = FileInfo("Orbit Station - S01E01 - Docking.mkv", 1420.0, 587443083)
    private val baseName = "mr-" + System.nanoTime().toString().takeLast(8)

    private lateinit var anaPlayer: RealtimePlayer
    private lateinit var bobPlayer: RealtimePlayer
    private lateinit var ana: SyncplayClient
    private lateinit var bob: SyncplayClient
    private val anaEvents = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
    private val bobEvents = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
    private lateinit var host: String
    private var port = 0

    @Before
    fun setUp() {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        server.split(":").let { host = it[0]; port = it[1].toInt() }
        anaPlayer = RealtimePlayer()
        bobPlayer = RealtimePlayer()
        ana = SyncplayClient(SyncplayConfig(host, port, "ana", "$baseName-lobby", useTls = false), anaPlayer, parentScope = scope)
        ana.events.onEach { anaEvents += it }.launchIn(scope)
    }

    @After
    fun tearDown() {
        if (::ana.isInitialized) ana.close()
        if (::bob.isInitialized) bob.close()
        scope.cancel()
    }

    @Test
    fun `an operator controls the room, others follow until they identify`() {
        ana.start()
        eventually("ana connected") { ana.state.value.status == ConnectionStatus.CONNECTED }
        assumeTrue("the server has no managed rooms", ana.state.value.managedRooms)

        assertTrue(ana.createManagedRoom(baseName))
        val created = eventuallyValue("room created") { anaEvents.filterIsInstance<SyncplayEvent.ManagedRoomCreated>().firstOrNull() }
        assertTrue(created.room, ManagedRooms.isManaged(created.room))
        assertTrue(created.room, ManagedRooms.display(created.room) == baseName)
        eventually("ana is the operator") { ana.state.value.room == created.room && ana.state.value.isOperator }
        ana.fileLoaded(episode)

        // Bob joins by the full name and follows: his own pause is put back.
        bob = SyncplayClient(SyncplayConfig(host, port, "bob", created.room, useTls = false), bobPlayer, parentScope = scope)
        bob.events.onEach { bobEvents += it }.launchIn(scope)
        bob.start()
        bob.fileLoaded(episode)
        eventually("bob is in, not an operator") { bob.state.value.inManagedRoom && bob.state.value.users.size == 2 && !bob.state.value.isOperator }
        assertTrue(bob.state.value.users.first { it.name == "ana" }.isController)
        ana.setReady(true)
        bob.setReady(true)
        anaPlayer.setPaused(false)
        eventually("bob plays along") { !bobPlayer.isPaused }
        Thread.sleep(1_500)
        bobPlayer.setPaused(true)
        eventually("bob's pause put back") { !bobPlayer.isPaused }
        Thread.sleep(1_500)
        assertFalse("the room kept playing", anaPlayer.isPaused)
        assertTrue(bobEvents.any { it is SyncplayEvent.Notification && it.message == "Only room operators can control playback" })

        // The operator sets bob's readiness.
        ana.setOthersReady("bob", false)
        eventually("bob told who") { SyncplayEvent.ReadySetBy("bob", "ana", false) in bobEvents }
        eventually("bob not ready") { bob.state.value.isReady == false }

        // A wrong password fails; the right one makes bob an operator, and then his pause pauses the room.
        bob.identifyAsOperator("ZZ-000-000")
        eventually("wrong password") { bobEvents.any { it is SyncplayEvent.ControllerAuth && it.user == "bob" && !it.success } }
        bob.identifyAsOperator(created.password.lowercase())
        eventually("bob is an operator") { bob.state.value.isOperator }
        eventually("ana sees it") { anaEvents.any { it is SyncplayEvent.ControllerAuth && it.user == "bob" && it.success } }
        bobPlayer.setPaused(true)
        eventually("the room paused") { anaPlayer.isPaused }
    }

    @Test
    fun `a saved password makes us an operator again after joining`() {
        ana.start()
        eventually("ana connected") { ana.state.value.status == ConnectionStatus.CONNECTED }
        assumeTrue("the server has no managed rooms", ana.state.value.managedRooms)
        ana.createManagedRoom(baseName)
        val created = eventuallyValue("room created") { anaEvents.filterIsInstance<SyncplayEvent.ManagedRoomCreated>().firstOrNull() }

        // A later session (another device, or after a restart) with the password saved.
        bob = SyncplayClient(SyncplayConfig(host, port, "bob", created.room, useTls = false), bobPlayer, parentScope = scope)
        bob.savedOperatorPassword = { if (it == created.room) created.password else null }
        bob.events.onEach { bobEvents += it }.launchIn(scope)
        bob.start()
        eventually("bob identified by himself") { bob.state.value.isOperator }
    }

    private fun eventually(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what\n ana=${ana.state.value}\n bob events=$bobEvents")
    }

    private fun <T : Any> eventuallyValue(what: String, timeoutMs: Long = 8_000, value: () -> T?): T {
        var result: T? = null
        eventually(what, timeoutMs) { value().also { result = it } != null }
        return result!!
    }
}
