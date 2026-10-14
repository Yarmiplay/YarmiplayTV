package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.KnownServer
import com.yarmiplaytv.data.KnownServers
import com.yarmiplaytv.relay.SessionDns
import com.yarmiplaytv.syncplay.LanServer
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.SyncplayDiscovery
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** What the app keeps about a server's home addresses, and how it uses it. */
class LanConnectTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        SyncplayDiscovery.scanner = null
        SessionDns.use(null)
        scope.cancel()
    }

    @Test
    fun `known servers keep the certificate's name and LAN addresses, and old lines still parse`() {
        val known = mapOf(
            "myname.duckdns.org:8999" to KnownServer(true, "9f00", "approved", "myname.duckdns.org", listOf("192.168.1.20:8999", "[2a02:6b8::20]:8999")),
            "syncplay.pl:8997" to KnownServer(false),
        )
        assertEquals(known, KnownServers.decode(KnownServers.encode(known)))
        // Written before 1.7.7: four columns.
        val old = KnownServers.decode("home.example:8999\tyarmiplay\tabc\topen\nsyncplay.pl:8999\tsyncplay\t\t")
        assertEquals(KnownServer(true, "abc", "open"), old["home.example:8999"])
        assertEquals(KnownServer(false), old["syncplay.pl:8999"])
    }

    @Test
    fun `relay HTTP resolves the session's name to the address the connection reached`() {
        SessionDns.use(YarmiplaySession("t", 1, "https://myname.duckdns.org:8999", resolveTo = "192.168.1.20"))
        assertEquals(listOf(InetAddress.getByName("192.168.1.20")), SessionDns.lookup("MyName.DuckDNS.org"))
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), SessionDns.lookup("127.0.0.1"))
        SessionDns.use(null)
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), SessionDns.lookup("127.0.0.1"))
        // A session on an IP needs nothing mapped.
        SessionDns.use(YarmiplaySession("t", 1, "http://192.168.1.20:8999", resolveTo = "192.168.1.20"))
        assertEquals(listOf(InetAddress.getByName("10.0.0.1")), SessionDns.lookup("10.0.0.1"))
    }

    @Test
    fun `the player streams the host's Jellyfin from where the connection reached the server`() {
        val stream = "https://MyName.duckdns.org:8999/Videos/abc/stream?static=true&api_key=k"
        assertEquals(stream, SessionDns.direct(stream))
        SessionDns.use(YarmiplaySession("t", 1, "https://myname.duckdns.org:8999", resolveTo = "192.168.1.20"))
        assertEquals("https://192.168.1.20:8999/Videos/abc/stream?static=true&api_key=k", SessionDns.direct(stream))
        assertEquals("http://other.example:8096/Videos/abc/stream", SessionDns.direct("http://other.example:8096/Videos/abc/stream"))
        assertEquals("content://media/1", SessionDns.direct("content://media/1"))
        SessionDns.use(YarmiplaySession("t", 1, "https://myname.duckdns.org:8999", resolveTo = "2a02:6b8::20"))
        assertEquals("https://[2a02:6b8::20]:8999/Videos/abc/stream?static=true&api_key=k", SessionDns.direct(stream))
    }

    @Test
    fun `discovery sends nothing off Wi-Fi and Ethernet`() = runBlocking {
        val asked = mutableListOf<Set<Int>>()
        SyncplayDiscovery.scanner = { ports -> asked += ports; listOf(LanServer("id", "1.7.2", 8999, null, "192.168.1.20")) }
        assertEquals(emptyList<LanServer>(), discoverSyncplayServers(setOf(8999), onLocalNetwork = false))
        assertTrue("no broadcast on mobile data", asked.isEmpty())
        assertEquals(1, discoverSyncplayServers(setOf(8999, 8995), onLocalNetwork = true).size)
        assertEquals(listOf(setOf(8999, 8995)), asked)
    }

    @Test
    fun `connecting uses what's known about the server, and an IP gets its certificate's name`() {
        val sync = SyncController(scope, FakePlayer())
        val known = KnownServer(true, "9f00", "approved", "myname.duckdns.org", listOf("192.168.1.20:8999"))
        sync.knownServer = { server -> known.takeIf { server == "myname.duckdns.org:8999" || server == "192.168.1.20:8999" } }

        sync.connect(SyncplayConfig("myname.duckdns.org", 8999, "ana", "room"))
        val byName = sync.client!!.config
        assertEquals(listOf("192.168.1.20:8999"), byName.lanAddresses)
        assertEquals("9f00", byName.knownServerId)
        assertNull("a name is verified as itself", byName.tlsName)

        sync.connect(SyncplayConfig("192.168.1.20", 8999, "ana", "room"))
        assertEquals("myname.duckdns.org", sync.client!!.config.tlsName)
        sync.disconnect()
    }
}
