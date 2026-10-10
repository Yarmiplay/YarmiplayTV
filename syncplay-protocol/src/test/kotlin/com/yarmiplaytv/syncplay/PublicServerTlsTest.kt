package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

/** Checks STARTTLS against the public syncplay.pl server, by name and by address. Enabled with SYNCPLAY_TLS_TEST=1. */
class PublicServerTlsTest {
    @Test
    fun `connects to syncplay pl over TLS`() {
        assumeTrue(System.getenv("SYNCPLAY_TLS_TEST") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = RealtimePlayer().apply { isFileLoaded = false }
        val client = SyncplayClient(
            SyncplayConfig("syncplay.pl", 8999, "YarmiplayTV-test", "yarmiplaytv-tls-" + System.nanoTime()),
            player,
            parentScope = scope,
        )
        try {
            client.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (client.state.value.status != ConnectionStatus.CONNECTED && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val state = client.state.value
            assertTrue("not connected: $state", state.status == ConnectionStatus.CONNECTED)
            assertTrue("expected TLS: $state", state.tls)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun `stops on a certificate that isn't for the address it connected to`() {
        assumeTrue(System.getenv("SYNCPLAY_TLS_TEST") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ip = InetAddress.getByName("syncplay.pl").hostAddress
        val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
        val client = SyncplayClient(
            SyncplayConfig(ip, 8999, "YarmiplayTV-test", "yarmiplaytv-tls-" + System.nanoTime()),
            RealtimePlayer().apply { isFileLoaded = false },
            parentScope = scope,
        )
        try {
            client.events.onEach { events += it }.launchIn(scope)
            client.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (events.none { it is SyncplayEvent.Disconnected } && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val disconnected = events.filterIsInstance<SyncplayEvent.Disconnected>().firstOrNull()
            assertTrue("no disconnect: ${client.state.value}", disconnected != null)
            assertTrue(disconnected!!.reason!!, !disconnected.willReconnect && "certificate" in disconnected.reason!!)
            Thread.sleep(2_000)
            assertEquals(ConnectionStatus.DISCONNECTED, client.state.value.status)
        } finally {
            client.close()
            scope.cancel()
        }
    }
}
