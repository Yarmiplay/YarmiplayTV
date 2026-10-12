package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Syncplay's managed rooms, against a scripted server speaking the official protocol. */
class ManagedRoomsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeClock()
    private val transports = Collections.synchronizedList(mutableListOf<ScriptedTransport>())
    private val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())

    private val managed = "+movie night:D6A85289CDB5"

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun client(room: String = "movie night", saved: Map<String, String> = emptyMap()): SyncplayClient {
        val c = SyncplayClient(
            SyncplayConfig("syncplay.example", 8999, "ana", room, useTls = false),
            FakePlayer(clock),
            parentScope = scope,
            clock = clock,
            transportFactory = { _, _, _ -> ScriptedTransport().also { transports += it } },
        )
        c.savedOperatorPassword = { saved[it] }
        c.events.onEach { events += it }.launchIn(scope)
        c.start()
        return c
    }

    private val transport: ScriptedTransport
        get() {
            eventually("a connection") { transports.isNotEmpty() }
            return transports.last()
        }

    private fun hello(room: String = "movie night", managedRooms: Boolean? = true): String {
        val flag = managedRooms?.let { ""","managedRooms":$it""" } ?: ""
        return """{"Hello":{"username":"ana","room":{"name":"$room"},"version":"1.2.255","realversion":"1.7.6","features":{"readiness":true,"chat":true$flag}}}"""
    }

    private fun connect(c: SyncplayClient, room: String = "movie night", managedRooms: Boolean? = true) {
        transport.receive(hello(room, managedRooms))
        eventually("connected") { c.state.value.status == ConnectionStatus.CONNECTED }
    }

    private fun list(room: String, vararg users: Pair<String, Boolean>): String =
        """{"List":{"$room":{${users.joinToString(",") { (name, op) -> """"$name":{"position":0,"file":{},"controller":$op,"isReady":true}""" }}}}}"""

    /** The Set commands the client sent, as (command, body) in order. */
    private fun sets(): List<Pair<String, JsonObject>> = transport.sentJson().mapNotNull { it["Set"] as? JsonObject }
        .flatMap { set -> set.entries.map { (k, v) -> k to v.jsonObject } }

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `creating a managed room asks the server, joins the room it names and identifies there`() {
        val c = client()
        connect(c)
        assertTrue(c.createManagedRoom("  movie night  "))
        val first = eventuallyValue("controllerAuth") { sets().firstOrNull { it.first == "controllerAuth" }?.second }
        assertEquals("movie night", first.s("room"))
        val password = first.s("password")
        assertTrue(password, ManagedRooms.isValidPassword(password))

        transport.receive("""{"Set":{"newControlledRoom":{"roomName":"$managed","password":"$password"}}}""")
        eventually("joined the managed room") { sets().any { it.first == "room" && it.second.s("name") == managed } }
        eventually("identified there") { sets().count { it.first == "controllerAuth" } == 2 }
        val order = sets().map { it.first }.filter { it == "controllerAuth" || it == "room" }
        assertEquals(listOf("controllerAuth", "room", "controllerAuth"), order)
        val second = sets().last { it.first == "controllerAuth" }.second
        assertEquals(managed, second.s("room"))
        assertEquals(password, second.s("password"))
        eventually("created event") { SyncplayEvent.ManagedRoomCreated(managed, password) in events }

        transport.receive(list(managed, "ana" to false))
        transport.receive("""{"Set":{"controllerAuth":{"user":"ana","room":"$managed","success":true}}}""")
        eventually("operator") { c.state.value.isOperator }
        assertTrue(c.state.value.inManagedRoom)
        eventually("own success event with the password") { SyncplayEvent.ControllerAuth("ana", managed, true, password) in events }
    }

    @Test
    fun `a newControlledRoom we didn't ask for is ignored`() {
        val c = client()
        connect(c)
        transport.receive("""{"Set":{"newControlledRoom":{"roomName":"$managed","password":"ZZ-000-000"}}}""")
        Thread.sleep(200)
        assertEquals("movie night", c.state.value.room)
        assertTrue(events.none { it is SyncplayEvent.ManagedRoomCreated })
    }

    @Test
    fun `controllerAuth success and failure update operators, and leaving clears the flag`() {
        val c = client(room = managed)
        connect(c, room = managed)
        transport.receive(list(managed, "ana" to false, "bob" to false))
        eventually("users") { c.state.value.users.size == 2 }

        transport.receive("""{"Set":{"controllerAuth":{"user":"bob","room":"$managed","success":true}}}""")
        eventually("bob is an operator") { c.state.value.users.first { it.name == "bob" }.isController }
        assertFalse(c.state.value.isOperator)
        assertTrue(c.state.value.followsOperators)
        eventually("event for bob") { SyncplayEvent.ControllerAuth("bob", managed, true, null) in events }

        c.identifyAsOperator(" ab-123-456 ")
        eventually("sent uppercased") { sets().any { it.first == "controllerAuth" && it.second.s("password") == "AB-123-456" } }
        transport.receive("""{"Set":{"controllerAuth":{"user":"ana","room":"$managed","success":false}}}""")
        eventually("own failure event with the password") { SyncplayEvent.ControllerAuth("ana", managed, false, "AB-123-456") in events }
        assertFalse(c.state.value.isOperator)

        // Leaving ends operator status: coming back, bob is a plain user again.
        transport.receive("""{"Set":{"user":{"bob":{"room":{"name":"$managed"},"event":{"left":true}}}}}""")
        eventually("bob left") { c.state.value.users.none { it.name == "bob" } }
        transport.receive("""{"Set":{"user":{"bob":{"room":{"name":"$managed"},"event":{"joined":true}}}}}""")
        eventually("bob back") { c.state.value.users.any { it.name == "bob" } }
        assertFalse(c.state.value.users.first { it.name == "bob" }.isController)
    }

    @Test
    fun `operator status survives user updates`() {
        val c = client(room = managed)
        connect(c, room = managed)
        transport.receive(list(managed, "ana" to false, "bob" to true))
        eventually("bob is an operator") { c.state.value.users.any { it.name == "bob" && it.isController } }
        transport.receive("""{"Set":{"user":{"bob":{"room":{"name":"$managed"},"file":{"name":"a.mkv","duration":60,"size":1}}}}}""")
        eventually("bob's file") { c.state.value.users.first { it.name == "bob" }.file?.name == "a.mkv" }
        assertTrue(c.state.value.users.first { it.name == "bob" }.isController)
    }

    @Test
    fun `ready set by someone else is reported with who set it`() {
        val c = client(room = managed)
        connect(c, room = managed)
        transport.receive(list(managed, "ana" to false, "bob" to true))
        transport.receive("""{"Set":{"ready":{"username":"ana","isReady":true,"manuallyInitiated":true,"setBy":"bob"}}}""")
        eventually("ready set by bob") { SyncplayEvent.ReadySetBy("ana", "bob", true) in events }
        eventually("our ready state") { c.state.value.isReady == true }
        // Setting your own readiness names you; that's not news.
        transport.receive("""{"Set":{"ready":{"username":"bob","isReady":false,"manuallyInitiated":true,"setBy":"bob"}}}""")
        Thread.sleep(200)
        assertEquals(1, events.count { it is SyncplayEvent.ReadySetBy })

        c.setOthersReady("bob", true)
        val ready = eventuallyValue("ready for bob") { sets().lastOrNull { it.first == "ready" && it.second["username"] != null }?.second }
        assertEquals("bob", ready.s("username"))
        assertEquals("true", ready.s("isReady"))
        assertEquals("true", ready.s("manuallyInitiated"))
    }

    @Test
    fun `a saved password is sent once after joining, and again after a reconnect`() {
        val c = client(room = managed, saved = mapOf(managed to "ab-123-456"))
        connect(c, room = managed)
        eventually("identified") { sets().count { it.first == "controllerAuth" } == 1 }
        assertEquals("AB-123-456", sets().single { it.first == "controllerAuth" }.second.s("password"))
        transport.receive(list(managed, "ana" to false))
        Thread.sleep(200)
        assertEquals("once only", 1, sets().count { it.first == "controllerAuth" })

        // A reconnect is a new session: operator status is gone, so the password goes again.
        val first = transport
        first.close()
        eventually("reconnected transport") { transports.size == 2 }
        clock.advance(5.0)
        transport.receive(hello(managed))
        eventually("identified again") { transport.sentJson().any { (it["Set"] as? JsonObject)?.containsKey("controllerAuth") == true } }
    }

    @Test
    fun `changing to a managed room with a saved password identifies there`() {
        val c = client(saved = mapOf(managed to "AB-123-456"))
        connect(c)
        Thread.sleep(100)
        assertTrue("not managed: nothing sent", sets().none { it.first == "controllerAuth" })
        c.changeRoom(managed)
        eventually("identified in the new room") { sets().any { it.first == "controllerAuth" && it.second.s("room") == managed } }
    }

    @Test
    fun `without managedRooms in the server's Hello nothing is sent`() {
        for (flag in listOf(false, null)) {
            transports.clear()
            val c = client(room = managed, saved = mapOf(managed to "AB-123-456"))
            connect(c, room = managed, managedRooms = flag)
            assertFalse(c.state.value.managedRooms)
            assertFalse(c.state.value.followsOperators)
            assertFalse(c.createManagedRoom("movie night"))
            c.identifyAsOperator("AB-123-456")
            c.setOthersReady("bob", true)
            Thread.sleep(200)
            assertTrue("$flag: ${transport.sent}", sets().none { it.first == "controllerAuth" || (it.first == "ready" && it.second["username"] != null) })
            c.close()
        }
    }

    @Test
    fun `base names longer than 21 characters are refused`() {
        val c = client()
        connect(c)
        assertFalse(c.createManagedRoom(""))
        assertFalse(c.createManagedRoom("x".repeat(22)))
        assertTrue(c.createManagedRoom("x".repeat(21)))
    }

    @Test
    fun `room names and passwords`() {
        assertTrue(ManagedRooms.isManaged(managed))
        assertEquals("movie night", ManagedRooms.display(managed))
        assertEquals("🔒 movie night", ManagedRooms.label(managed))
        // A colon inside the base name is part of it; only the last :HASH counts.
        assertEquals("a:b", ManagedRooms.display("+a:b:0123456789AB"))
        // Wrong hash lengths, a missing +, or no hash: ordinary names, shown as they are.
        for (name in listOf("+movie night:D6A85289CDB", "+movie night:D6A85289CDB51", "movie night:D6A85289CDB5", "+movie night", "lobby")) {
            assertFalse(name, ManagedRooms.isManaged(name))
            assertEquals(name, ManagedRooms.display(name))
            assertEquals(name, ManagedRooms.label(name))
        }
        repeat(50) { assertTrue(ManagedRooms.isValidPassword(ManagedRooms.generatePassword())) }
        assertFalse(ManagedRooms.isValidPassword("ab-123-456"))
        assertEquals("AB-123-456", ManagedRooms.normalizePassword(" ab-123-456\n"))
    }

    private fun eventually(what: String, timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for: $what\n sent=${transports.lastOrNull()?.sent}\n events=$events")
    }

    private fun <T : Any> eventuallyValue(what: String, timeoutMs: Long = 4_000, value: () -> T?): T {
        var result: T? = null
        eventually(what, timeoutMs) { value().also { result = it } != null }
        return result!!
    }
}
