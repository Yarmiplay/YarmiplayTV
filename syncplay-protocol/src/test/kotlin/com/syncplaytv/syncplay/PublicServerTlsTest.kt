package com.syncplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks STARTTLS against the public syncplay.pl server. Enabled with SYNCPLAY_TLS_TEST=1. */
class PublicServerTlsTest {
    @Test
    fun `connects to syncplay pl over TLS`() {
        assumeTrue(System.getenv("SYNCPLAY_TLS_TEST") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = RealtimePlayer().apply { isFileLoaded = false }
        val client = SyncplayClient(
            SyncplayConfig("syncplay.pl", 8999, "SyncplayTV-test", "syncplaytv-tls-" + System.nanoTime()),
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
}
