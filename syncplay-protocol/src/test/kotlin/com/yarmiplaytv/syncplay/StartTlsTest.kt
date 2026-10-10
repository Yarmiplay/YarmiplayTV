package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class StartTlsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = Collections.synchronizedList(mutableListOf<ScriptedTransport>())
    private val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun client(tlsError: Exception): SyncplayClient {
        val clock = FakeClock()
        val c = SyncplayClient(
            SyncplayConfig("yarmi.example", 8999, "ana", "movie night", useTls = true),
            FakePlayer(clock),
            parentScope = scope,
            clock = clock,
            transportFactory = { _, _ -> ScriptedTransport(tlsError).also { transports += it } },
        )
        c.events.onEach { events += it }.launchIn(scope)
        c.start()
        return c
    }

    private fun acceptStartTls() {
        eventually("the STARTTLS request") { transports.lastOrNull()?.sent?.any { "startTLS" in it } == true }
        transports.last().receive("""{"TLS":{"startTLS":"true"}}""")
    }

    @Test
    fun `a certificate that fails validation stops without retrying or falling back to plain TCP`() {
        val c = client(SSLHandshakeException("No subject alternative DNS name matching yarmi.example found."))
        acceptStartTls()

        val disconnected = eventuallyValue("the fatal disconnect") { events.filterIsInstance<SyncplayEvent.Disconnected>().firstOrNull() }
        assertEquals(false, disconnected.willReconnect)
        assertTrue(disconnected.reason!!, "certificate isn't valid for yarmi.example" in disconnected.reason!!)
        assertTrue(disconnected.reason!!, "Turn TLS off" in disconnected.reason!!)
        eventually("disconnected") { c.state.value.status == ConnectionStatus.DISCONNECTED }
        Thread.sleep(1_500)
        assertEquals("no reconnect", 1, transports.size)
        assertTrue("no Hello in plain text", transports.single().sent.none { "Hello" in it })
    }

    @Test
    fun `a TLS error that isn't about the certificate reconnects`() {
        val c = client(SSLException("Connection reset during the handshake"))
        acceptStartTls()

        val disconnected = eventuallyValue("the disconnect") { events.filterIsInstance<SyncplayEvent.Disconnected>().firstOrNull() }
        assertEquals(true, disconnected.willReconnect)
        eventually("another attempt") { transports.size >= 2 }
        assertEquals(ConnectionStatus.RECONNECTING, c.state.value.status)
    }

    private fun eventually(what: String, timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for: $what\n events=$events")
    }

    private fun <T : Any> eventuallyValue(what: String, timeoutMs: Long = 4_000, value: () -> T?): T {
        var result: T? = null
        eventually(what, timeoutMs) { value().also { result = it } != null }
        return result!!
    }
}
