package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import javax.net.ssl.SSLHandshakeException

/** Reaching a YarmiplayServerTV at home: its `tls` field, LAN discovery, and the race between typed and LAN addresses. */
class LanRoutesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports: MutableList<RouteTransport> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<SyncplayEvent> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() = scope.cancel()

    // --- Parsing ---------------------------------------------------------------------------------

    @Test
    fun `the tls field gives the name and LAN addresses, null without a certificate`() {
        val tls = ServerTlsFormat.parse(Json.parseToJsonElement("""{"name":"myname.duckdns.org","lan":["192.168.1.20:8999","[2a02:6b8::20]:8999"]}"""))
        assertEquals(ServerTls("myname.duckdns.org", listOf("192.168.1.20:8999", "[2a02:6b8::20]:8999")), tls)
        assertNull(ServerTlsFormat.parse(Json.parseToJsonElement("null")))
        assertNull(ServerTlsFormat.parse(null))
        assertNull(ServerTlsFormat.parse(Json.parseToJsonElement("""{"lan":["192.168.1.20:8999"]}""")))
        assertEquals(ServerTls("x.example"), ServerInfo.parse("""{"server":"YarmiplayServerTV","tls":{"name":"x.example"}}"""))
        assertNull(ServerInfo.parse("""{"server":"YarmiplayServerTV","tls":null}"""))
        assertNull(ServerInfo.parse("<html>"))
    }

    @Test
    fun `addresses split into host and port, and IP literals are told from names`() {
        assertEquals("192.168.1.20" to 8999, ServerTlsFormat.splitHostPort("192.168.1.20:8999"))
        assertEquals("2a02:6b8::20" to 8999, ServerTlsFormat.splitHostPort("[2a02:6b8::20]:8999"))
        assertNull(ServerTlsFormat.splitHostPort("2a02:6b8::20"))
        assertNull(ServerTlsFormat.splitHostPort("myname.duckdns.org"))
        assertNull(ServerTlsFormat.splitHostPort("1.2.3.4:99999"))
        assertTrue(ServerTlsFormat.isIpLiteral("192.168.1.20"))
        assertTrue(ServerTlsFormat.isIpLiteral("[2a02:6b8::20]"))
        assertTrue(ServerTlsFormat.isIpLiteral("::1"))
        assertFalse(ServerTlsFormat.isIpLiteral("myname.duckdns.org"))
        assertFalse(ServerTlsFormat.isIpLiteral("999.1.1.1"))
        assertFalse(ServerTlsFormat.isIpLiteral("syncplay.pl"))
    }

    @Test
    fun `discovery replies are parsed, and junk or other replies ignored`() {
        val reply = """{"yarmiplay":"server","server":"YarmiplayServerTV","version":"1.7.2","serverId":"9f00","port":8999,
            |"tls":{"name":"myname.duckdns.org","lan":["192.168.1.20:8999"]}}""".trimMargin()
        assertEquals(
            LanServer("9f00", "1.7.2", 8999, ServerTls("myname.duckdns.org", listOf("192.168.1.20:8999")), "192.168.1.20"),
            SyncplayDiscovery.parseReply(reply, "192.168.1.20"),
        )
        assertNull(SyncplayDiscovery.parseReply("""{"yarmiplay":"server","serverId":"9f00","port":8999,"tls":null}""", "x")!!.tls)
        assertNull(SyncplayDiscovery.parseReply("""{"yarmiplay":"discover","protocol":1}""", "x"))
        assertNull(SyncplayDiscovery.parseReply("""{"yarmiplay":"server"}""", "x"))
        assertNull(SyncplayDiscovery.parseReply("who is JellyfinServer?", "x"))
        assertNull(SyncplayDiscovery.parseReply("[1,2]", "x"))
        assertEquals("""{"yarmiplay":"discover","protocol":1}""", String(SyncplayDiscovery.request))
    }

    @Test
    fun `found servers match by their certificate's name or by the saved server id`() {
        val home = LanServer("id-home", "1.7.2", 8999, ServerTls("MyName.DuckDNS.org", listOf("192.168.1.20:8999")), "192.168.1.20")
        val other = LanServer("id-other", "1.7.2", 8999, ServerTls("other.example"), "192.168.1.30")
        val plain = LanServer("id-plain", "1.7.2", 8999, null, "192.168.1.40")
        val all = listOf(home, other, plain)
        assertEquals(listOf(home), SyncplayDiscovery.matching(all, "myname.duckdns.org", null))
        assertEquals(listOf(plain), SyncplayDiscovery.matching(all, "renamed.example", "id-plain"))
        assertEquals(emptyList<LanServer>(), SyncplayDiscovery.matching(all, "nobody.example", ""))
        assertEquals("myname.duckdns.org", LanServer("i", "", 8999, ServerTls("myname.duckdns.org"), "192.168.1.20").host)
        assertEquals("192.168.1.40", plain.host)
    }

    // --- The connection race ---------------------------------------------------------------------

    @Test
    fun `at home a LAN address that verifies as the name wins, and one that doesn't is dropped`() {
        val c = client(
            SyncplayConfig("myname.duckdns.org", 8999, "ana", "room", lanAddresses = listOf("10.0.0.9:8999", "192.168.1.20:8999")),
            mapOf(
                "myname.duckdns.org" to Behaviour(connect = Connect.HANG),
                "10.0.0.9" to Behaviour(tls = Tls.MISMATCH),
                "192.168.1.20" to Behaviour(),
            ),
        )
        eventually("the LAN route") { c.state.value.route != null }
        assertEquals(ConnectionRoute("192.168.1.20", "myname.duckdns.org", atHome = true), c.state.value.route)
        eventually("the Hello") { transports.any { it.dial == "192.168.1.20" && it.hello } }
        assertTrue("every attempt verifies the typed name", transports.all { it.tlsName == "myname.duckdns.org" })
        assertFalse("never plain text on the address that failed", transport("10.0.0.9").hello)
        assertTrue(transport("10.0.0.9").closed)
        eventually("the typed attempt dropped") { transport("myname.duckdns.org").closed }
        assertFalse(transport("myname.duckdns.org").hello)
    }

    @Test
    fun `the typed host wins when it answers within its head start`() {
        val c = client(
            SyncplayConfig("myname.duckdns.org", 8999, "ana", "room", lanAddresses = listOf("192.168.1.20:8999")),
            mapOf("myname.duckdns.org" to Behaviour(), "192.168.1.20" to Behaviour()),
        )
        eventually("connected") { c.state.value.route != null }
        assertEquals(ConnectionRoute("myname.duckdns.org", "myname.duckdns.org", atHome = false), c.state.value.route)
        Thread.sleep(SyncplayClient.LAN_HEAD_START_MS + 300)
        assertTrue("the LAN address never got going", transports.none { it.dial == "192.168.1.20" && it.hello })
    }

    @Test
    fun `a LAN address without TLS is never used`() {
        val c = client(
            SyncplayConfig("myname.duckdns.org", 8999, "ana", "room", lanAddresses = listOf("192.168.1.20:8999"), autoReconnect = false),
            mapOf("myname.duckdns.org" to Behaviour(connect = Connect.FAIL), "192.168.1.20" to Behaviour(tls = Tls.NONE)),
        )
        eventually("given up") { c.state.value.status == ConnectionStatus.DISCONNECTED && transports.size == 2 }
        assertTrue(transports.none { it.hello })
        assertNull(c.state.value.route)
    }

    @Test
    fun `when nothing answers, a server found on this network by its name is tried and remembered`() {
        val c = client(
            SyncplayConfig("myname.duckdns.org", 8999, "ana", "room"),
            mapOf("myname.duckdns.org" to Behaviour(connect = Connect.FAIL), "192.168.1.20" to Behaviour()),
            discovery = { port ->
                listOf(
                    LanServer("x", "1.7.2", port, ServerTls("other.example", listOf("192.168.1.30:8999")), "192.168.1.30"),
                    LanServer("y", "1.7.2", port, ServerTls("myname.duckdns.org", listOf("192.168.1.20:8999")), "192.168.1.20"),
                )
            },
        )
        eventually("the found server") { c.state.value.route?.dialed == "192.168.1.20" }
        assertTrue(c.state.value.route!!.atHome)
        assertTrue(transports.none { it.dial == "192.168.1.30" })
        assertEquals(listOf("192.168.1.20:8999"), c.config.lanAddresses)
    }

    @Test
    fun `a typed IP is verified as the name its server gives, and that name is reported`() {
        val c = client(
            SyncplayConfig("192.168.1.20", 8999, "ana", "room"),
            mapOf("192.168.1.20" to Behaviour()),
            info = { _, _ -> ServerTls("myname.duckdns.org", listOf("192.168.1.20:8999")) },
        )
        eventually("connected") { c.state.value.route != null }
        assertEquals("myname.duckdns.org", transport("192.168.1.20").tlsName)
        assertEquals(ConnectionRoute("192.168.1.20", "myname.duckdns.org", atHome = false), c.state.value.route)
        assertEquals(SyncplayEvent.ServerNamed("192.168.1.20", "myname.duckdns.org"), events.filterIsInstance<SyncplayEvent.ServerNamed>().single())
    }

    @Test
    fun `a typed IP without a certificate behaves as before`() {
        val c = client(
            SyncplayConfig("192.168.1.20", 8999, "ana", "room"),
            mapOf("192.168.1.20" to Behaviour(tls = Tls.NONE)),
            info = { _, _ -> null },
        )
        eventually("connected") { c.state.value.route != null }
        assertEquals("192.168.1.20", transport("192.168.1.20").tlsName)
        assertEquals(ConnectionRoute("192.168.1.20", null, atHome = false), c.state.value.route)
        eventually("the Hello") { transports.any { it.dial == "192.168.1.20" && it.hello } }
        assertTrue(events.none { it is SyncplayEvent.ServerNamed })
    }

    @Test
    fun `the Hello's tls field is parsed and its LAN addresses kept for the next connection`() {
        val c = client(SyncplayConfig("myname.duckdns.org", 8999, "ana", "room", deviceAuth = TestDeviceAuth()), mapOf("myname.duckdns.org" to Behaviour()))
        eventually("the Hello") { transports.any { it.dial == "myname.duckdns.org" && it.hello } }
        transport("myname.duckdns.org").receive(
            """{"Hello":{"username":"ana","room":{"name":"room"},"version":"1.2.255","realversion":"1.7.2","features":{"yarmiplay":""" +
                """{"server":"YarmiplayServerTV","version":"1.7.2","tls":{"name":"myname.duckdns.org","lan":["192.168.1.20:8999"]}}}}}""",
        )
        eventually("the server's tls") { c.state.value.yarmiplay.tls != null }
        assertEquals(ServerTls("myname.duckdns.org", listOf("192.168.1.20:8999")), c.state.value.yarmiplay.tls)
        eventually("remembered") { c.config.lanAddresses == listOf("192.168.1.20:8999") }
    }

    private fun transport(dial: String) = synchronized(transports) { transports.first { it.dial == dial } }

    private fun client(
        config: SyncplayConfig,
        behaviours: Map<String, Behaviour>,
        discovery: (suspend (Int) -> List<LanServer>)? = null,
        info: suspend (String, Int) -> ServerTls? = { _, _ -> null },
    ): SyncplayClient {
        val clock = FakeClock()
        val c = SyncplayClient(
            config,
            FakePlayer(clock),
            parentScope = scope,
            clock = clock,
            transportFactory = { host, _, name -> RouteTransport(host, name, behaviours[host] ?: Behaviour(connect = Connect.FAIL)).also { transports += it } },
            serverInfo = info,
        )
        c.lanDiscovery = discovery
        c.events.onEach { events += it }.launchIn(scope)
        c.start()
        return c
    }

    private fun eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what (${transports.toList()})")
            Thread.sleep(20)
        }
    }

    enum class Connect { OK, FAIL, HANG }
    enum class Tls { VERIFIED, MISMATCH, NONE }
    data class Behaviour(val connect: Connect = Connect.OK, val tls: Tls = Tls.VERIFIED)

    /** A server at [dial] that connects, answers STARTTLS and verifies (or not) as [behaviour] says. */
    class RouteTransport(val dial: String, val tlsName: String, private val behaviour: Behaviour) : Transport {
        private val incoming = Channel<String?>(Channel.UNLIMITED)
        @Volatile var closed = false
        @Volatile var hello = false
        @Volatile override var isTls = false
        override val remoteAddress: String? get() = dial.takeIf { ServerTlsFormat.isIpLiteral(it) }

        override suspend fun connect() {
            when (behaviour.connect) {
                Connect.OK -> Unit
                Connect.FAIL -> throw IOException("Connection refused: $dial")
                Connect.HANG -> while (!closed) delay(20).also { if (closed) throw IOException("closed") }
            }
            if (closed) throw IOException("closed")
        }

        override suspend fun readLine(): String? = if (closed) null else incoming.receive()

        override suspend fun writeLine(line: String) {
            if (closed) throw IOException("closed")
            if ("startTLS" in line) {
                incoming.trySend(if (behaviour.tls == Tls.NONE) """{"TLS":{"startTLS":"false"}}""" else """{"TLS":{"startTLS":"true"}}""")
            }
            if ("\"Hello\"" in line) hello = true
        }

        override suspend fun startTls() {
            if (behaviour.tls == Tls.MISMATCH) throw SSLHandshakeException("No name matching $tlsName found")
            isTls = true
        }

        fun receive(line: String) {
            incoming.trySend(line)
        }

        override fun close() {
            closed = true
            incoming.trySend(null)
        }

        override fun toString() = "$dial(closed=$closed, hello=$hello)"
    }
}
