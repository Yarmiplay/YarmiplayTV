package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Rooms joined become recent rooms by themselves; only rooms actually joined, and "Save this room" keeps one. */
class RoomHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private lateinit var sync: SyncController
    private lateinit var history: RoomHistory
    private val server = HelloServer()
    private var clock = 1000L

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        store = desktopSettingsStore(tmp.newFolder("config"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        sync = SyncController(scope, FakePlayer())
        history = RoomHistory(scope, sync, store) { clock++ }.also { it.start() }
    }

    @After
    fun tearDown() {
        onMain { sync.disconnect() }
        server.close()
        scope.cancel()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(10_000) { while (!condition()) delay(20) } }
            .onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    private fun join(room: String, port: Int = server.port) {
        onMain { sync.connect(SyncplayConfig("127.0.0.1", port, "ana", room, useTls = false)) }
    }

    private fun rooms() = runBlocking { store.current().savedRooms }

    @Test
    fun `joining and switching rooms keeps them as recent rooms, newest first, and the profile follows`() {
        runBlocking { store.saveSyncplay(SyncplayProfile("127.0.0.1", server.port, "ana", "movie night", useTls = false)) }
        join("movie night")
        awaitTrue("the first room") { rooms().any { it.room == "movie night" } }
        val first = rooms().single()
        assertEquals("127.0.0.1" to server.port, first.host to first.port)
        assertEquals("ana" to false, first.username to first.saved)

        onMain { sync.changeRoom("anime club") }
        awaitTrue("the second room") { rooms().size == 2 }
        assertEquals(listOf("anime club", "movie night"), rooms().map { it.room })
        awaitTrue("the profile to follow the room") { runBlocking { store.current().syncplay.room } == "anime club" }
    }

    @Test
    fun `save this room keeps it once it's joined`() {
        history.saveWhenJoined(server.port, "movie night ")
        join("movie night")
        awaitTrue("the room to be saved") { rooms().any { it.room == "movie night" && it.saved } }
    }

    @Test
    fun `a server that can't be reached isn't kept`() {
        val closed = ServerSocket(0).use { it.localPort }
        join("nowhere", closed)
        awaitTrue("the connection to fail") { sync.room.value.status != ConnectionStatus.CONNECTING }
        Thread.sleep(300)
        assertTrue(rooms().isEmpty())
    }

    /** Answers each Hello with the room the client asked for. */
    private class HelloServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val client = socket.accept()
                        thread(isDaemon = true) {
                            runCatching {
                                val reader = client.getInputStream().bufferedReader()
                                val writer = PrintWriter(client.getOutputStream().writer(), true)
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.contains("\"Hello\"")) {
                                        val room = Regex("\"room\":\\s*\\{\"name\":\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                                        writer.print(
                                            """{"Hello":{"username":"ana","room":{"name":"$room"},"version":"1.2.255","realversion":"1.7.0",""" +
                                                """"features":{"chat":true,"readiness":true},"motd":""}}""" + "\r\n",
                                        )
                                        writer.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }
}
