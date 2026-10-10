package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.ui.shared.connectionStatusLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ConnectionErrorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sync = SyncController(scope, FakePlayer())

    @After
    fun tearDown() {
        sync.disconnect()
        scope.cancel()
    }

    private fun line() = connectionStatusLine(sync.room.value.status, sync.connectionError.value)

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what (${sync.room.value.status}, ${sync.connectionError.value})")
            Thread.sleep(20)
        }
    }

    @Test
    fun `a server that refuses the connection shows why it's reconnecting until the user disconnects`() {
        val port = ServerSocket(0).use { it.localPort }
        sync.connect(SyncplayConfig("127.0.0.1", port, "ana", "movie night"))
        eventually("a reconnect") { sync.room.value.status == ConnectionStatus.RECONNECTING && sync.connectionError.value != null }
        assertTrue(line()!!, line()!!.startsWith("Reconnecting: "))

        sync.disconnect()
        assertNull(sync.connectionError.value)
    }

    @Test
    fun `a server error that ends the connection stays on the form`() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { s ->
                s.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write("{\"Error\":{\"message\":\"Wrong password supplied\"}}\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    Thread.sleep(500)
                }
            }
        }
        sync.connect(SyncplayConfig("127.0.0.1", server.localPort, "ana", "movie night", useTls = false))
        eventually("the refusal") { sync.room.value.status == ConnectionStatus.DISCONNECTED && sync.connectionError.value != null }
        assertEquals("Disconnected: Wrong password supplied", line())
    }
}
