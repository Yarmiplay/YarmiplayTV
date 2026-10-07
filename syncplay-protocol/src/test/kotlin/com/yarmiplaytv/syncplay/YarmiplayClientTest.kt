package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class YarmiplayClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeClock()
    private val transports = Collections.synchronizedList(mutableListOf<ScriptedTransport>())
    private val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val keys = TestDeviceAuth()
    private val serverId = "9b1f0c2e4d6a8b0c1e3f5a7b9c0d2e4f"
    private val token = "9f2c" + "ab".repeat(22)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun client(deviceAuth: DeviceAuth? = keys, requestAccess: Boolean = true): SyncplayClient {
        val c = SyncplayClient(
            SyncplayConfig("yarmi.example", 8999, "ana", "movie night", useTls = false, deviceAuth = deviceAuth, appVersion = "1.6.0", requestAccess = requestAccess),
            FakePlayer(clock),
            parentScope = scope,
            clock = clock,
            transportFactory = { _, _ -> ScriptedTransport().also { transports += it } },
            log = { logs += it },
        )
        c.events.onEach { events += it }.launchIn(scope)
        c.start()
        return c
    }

    private val transport: ScriptedTransport get() = eventuallyValue("a connection") { transports.lastOrNull() }

    private fun hello(marker: String? = null): String {
        val ext = marker?.let { ""","yarmiplay":$it""" } ?: ""
        return """{"Hello":{"username":"ana","room":{"name":"movie night"},"version":"1.2.255","realversion":"1.7.4","features":{"readiness":true,"chat":true$ext}}}"""
    }

    private val marker = """{"server":"YarmiplayServerTV","version":"1.6.0","protocol":1,"capabilities":{"fileRelay":true,"jellyfin":false,"https":false},"access":"open","device":"none"}"""
    private fun session(relay: Boolean = true) =
        """{"Yarmiplay":{"capabilities":{"fileRelay":$relay,"https":false,"jellyfin":false},"jellyfin":{"available":false},"session":{"protocol":1,"token":"$token"}}}"""

    private val offer = RelayOffer("Show - S01E01.mkv", 1534098432, 1422.5, "3b0c".repeat(16))

    private fun yarmiplaySent(t: ScriptedTransport = transport) = t.sentJson().filter { "Yarmiplay" in it }.map { it["Yarmiplay"]!!.jsonObject }

    @Test
    fun `the hello opts in only with a device key`() {
        client()
        val hello = eventuallyValue("hello") { transport.sentJson().firstOrNull { "Hello" in it } }
        val ext = hello["Hello"]!!.jsonObject["features"]!!.jsonObject["yarmiplay"]!!.jsonObject
        assertEquals(1, ext["protocol"]!!.jsonPrimitive.content.toInt())
        assertEquals("YarmiplayTV", ext["client"]!!.jsonPrimitive.content)
        assertEquals("1.6.0", ext["version"]!!.jsonPrimitive.content)

        transports.clear()
        client(deviceAuth = null)
        val plain = eventuallyValue("plain hello") { transport.sentJson().firstOrNull { "Hello" in it } }
        assertNull(plain["Hello"]!!.jsonObject["features"]!!.jsonObject["yarmiplay"])
    }

    @Test
    fun `a stock server never gets a Yarmiplay command`() {
        val c = client()
        // Before the server answers, the kind is unknown: nothing goes out either.
        c.offerFiles(listOf(offer))
        c.authorizeJellyfin("123456")
        c.reportUploadFailed("a1b2", "gone")
        transport.receive(hello())
        eventually("connected") { c.state.value.status == ConnectionStatus.CONNECTED }
        assertEquals(ServerKind.SYNCPLAY, c.state.value.yarmiplay.serverKind)
        c.offerFiles(listOf(offer))
        c.authorizeJellyfin("123456")
        c.reportUploadFailed("a1b2", "gone")
        eventually("jellyfin refused locally") { events.any { it is SyncplayEvent.JellyfinAuthorized && !it.ok } }
        Thread.sleep(200)
        assertTrue(yarmiplaySent().isEmpty())
        assertNull(c.session.value)
    }

    @Test
    fun `vanilla mode looks like a stock server`() {
        val c = client()
        // A YarmiplayServerTV in vanilla mode answers without the marker and sends no Yarmiplay messages.
        transport.receive(hello())
        eventually("connected") { c.state.value.status == ConnectionStatus.CONNECTED }
        assertEquals(ServerKind.SYNCPLAY, c.state.value.yarmiplay.serverKind)
        assertFalse(c.state.value.yarmiplay.session)
    }

    @Test
    fun `the marker and session start the extensions`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(session())
        val started = eventuallyValue("session") { events.filterIsInstance<SyncplayEvent.SessionStarted>().firstOrNull() }
        assertEquals("http://yarmi.example:8999", started.session.baseUrl)
        assertEquals("Bearer $token", started.session.authorization)
        val ext = c.state.value.yarmiplay
        assertEquals(ServerKind.YARMIPLAY, ext.serverKind)
        assertEquals("open", ext.access)
        assertTrue(ext.relayActive)
        assertEquals(1, ext.protocol)
        // The token never reaches the log or the state.
        assertTrue(logs.none { token in it })
        assertFalse(started.session.toString().contains(token))
        assertFalse(c.state.value.toString().contains(token))
    }

    @Test
    fun `offers go out after the session and again whenever the server forgot them`() {
        val c = client()
        transport.receive(hello(marker))
        c.offerFiles(listOf(offer))
        transport.receive(session())
        eventually("first offer") { yarmiplaySent().count { "offer" in it } == 1 }
        val sent = yarmiplaySent().first { "offer" in it }["offer"]!!.jsonObject["files"]!!.toString()
        assertTrue(sent, offer.quickHash in sent && "1534098432" in sent)

        // The same offer isn't repeated.
        c.offerFiles(listOf(offer))
        Thread.sleep(150)
        assertEquals(1, yarmiplaySent().count { "offer" in it })

        // Room change: the server forgets; the app offers the new room's files again.
        c.changeRoom("other")
        c.offerFiles(listOf(offer))
        eventually("offer after room change") { yarmiplaySent().count { "offer" in it } == 2 }

        // The relay goes off and on: the client re-sends by itself.
        transport.receive("""{"Yarmiplay":{"capabilities":{"fileRelay":false,"jellyfin":false,"https":true},"jellyfin":{"available":false}}}""")
        eventually("relay off") { !c.state.value.yarmiplay.capabilities.fileRelay }
        transport.receive("""{"Yarmiplay":{"capabilities":{"fileRelay":true,"jellyfin":false,"https":true},"jellyfin":{"available":false}}}""")
        eventually("offer after relay back on") { yarmiplaySent().count { "offer" in it } == 3 }

        // A playlist change that drops the file withdraws the offer.
        c.offerFiles(emptyList())
        eventually("withdrawn") { yarmiplaySent().last()["offer"]?.jsonObject?.get("files")?.toString() == "[]" }
    }

    @Test
    fun `everything off ends the session and the connection turns plain`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(session())
        eventually("session") { c.session.value != null }
        transport.receive("""{"Yarmiplay":{"capabilities":{"fileRelay":false,"jellyfin":false,"https":false}}}""")
        eventually("ended") { events.any { it == SyncplayEvent.SessionEnded } }
        assertNull(c.session.value)
        assertEquals(ServerKind.SYNCPLAY, c.state.value.yarmiplay.serverKind)
        val before = yarmiplaySent().size
        c.offerFiles(listOf(offer))
        c.reportUploadFailed("x", "y")
        Thread.sleep(200)
        assertEquals(before, yarmiplaySent().size)
    }

    @Test
    fun `relay lists, uploads and jellyfin answers arrive as state and events`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(session())
        transport.receive(
            """{"Yarmiplay":{"files":[{"id":"5d41","name":"Show - S01E01.mkv","size":1534098432,"duration":1422.5,"quickHash":"3b0c","sources":2,"cachedBytes":268435456,"rate":5242880},{"id":"x","name":"Old.mkv","size":10,"sources":0,"cachedBytes":4}]}}""",
        )
        eventually("files") { c.state.value.yarmiplay.relayFiles.size == 2 }
        val (show, old) = c.state.value.yarmiplay.relayFiles
        assertEquals(RelayFile("5d41", "Show - S01E01.mkv", 1534098432, 1422.5, "3b0c", 2, 268435456, 5242880), show)
        assertTrue(show.readable)
        assertFalse(old.readable)

        transport.receive("""{"Yarmiplay":{"upload":{"id":"a1b2","file":"5d41","size":1534098432,"quickHash":"3b0c","offset":268435456,"length":33554432}}}""")
        transport.receive("""{"Yarmiplay":{"uploadCancel":{"id":"a1b2"}}}""")
        transport.receive("""{"Yarmiplay":{"jellyfinAuthorize":{"code":"123456","ok":false,"error":"Too many attempts"}}}""")
        eventually("events") {
            events.any { it is SyncplayEvent.UploadRequested && it.request == UploadRequest("a1b2", "5d41", 1534098432, "3b0c", 268435456, 33554432) } &&
                events.any { it == SyncplayEvent.UploadCancelled("a1b2") } &&
                events.any { it == SyncplayEvent.JellyfinAuthorized("123456", false, "Too many attempts") }
        }

        c.reportUploadFailed("a1b2", "file not found")
        eventually("upload failed sent") { yarmiplaySent().any { it["uploadFailed"]?.jsonObject?.get("error")?.jsonPrimitive?.content == "file not found" } }
    }

    @Test
    fun `jellyfin sharing is parsed and authorize goes out only while shared`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(
            """{"Yarmiplay":{"capabilities":{"fileRelay":true,"jellyfin":true,"https":true},"jellyfin":{"available":true,"serverId":"b7c1","serverName":"Yarmi's Jellyfin","proxy":true,"addresses":["http://192.168.1.20:8096","https://yarmi.duckdns.org:8920"]},"session":{"protocol":1,"token":"$token"}}}""",
        )
        eventually("jellyfin") { c.state.value.yarmiplay.jellyfin.available }
        assertEquals(
            SharedJellyfin(true, "b7c1", "Yarmi's Jellyfin", true, listOf("http://192.168.1.20:8096", "https://yarmi.duckdns.org:8920")),
            c.state.value.yarmiplay.jellyfin,
        )
        c.authorizeJellyfin("123456")
        eventually("authorize sent") { yarmiplaySent().any { it["jellyfinAuthorize"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "123456" } }
    }

    @Test
    fun `peers with an extension session are marked`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(
            """{"List":{"movie night":{"ana":{"position":0,"file":{},"features":{"yarmiplay":{"protocol":1}}},"bob":{"position":0,"file":{},"features":{"chat":true}}}}}""",
        )
        eventually("list") { c.state.value.users.size == 2 }
        assertEquals(mapOf("ana" to true, "bob" to false), c.state.value.users.associate { it.name to it.yarmiplay })
        transport.receive("""{"Set":{"user":{"cleo":{"room":{"name":"movie night"},"event":{"joined":true,"version":"1.7.4","features":{"yarmiplay":{"protocol":1}}}}}}}""")
        transport.receive("""{"Set":{"user":{"dan":{"room":{"name":"movie night"},"event":{"joined":true,"version":"1.7.4","features":{"chat":true}}}}}}""")
        eventually("joined") { c.state.value.users.size == 4 }
        assertTrue(c.state.value.users.first { it.name == "cleo" }.yarmiplay)
        assertFalse(c.state.value.users.first { it.name == "dan" }.yarmiplay)
    }

    @Test
    fun `a challenge is answered with a signature the server can check`() {
        val c = client()
        transport.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"$serverId","nonce":"q83vEjRWeJA0Vni8mY7cE3A2m9vLz5n0kQ1y8f3dXeM=","access":"approved"}}}""")
        val auth = eventuallyValue("auth") { yarmiplaySent().firstOrNull { "auth" in it } }["auth"]!!.jsonObject
        val spki = Base64.getDecoder().decode(auth["publicKey"]!!.jsonPrimitive.content)
        assertTrue(spki.joinToString("") { "%02x".format(it) }.startsWith(Yarmiplay.P256_SPKI_PREFIX))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
        verifier.update("YarmiplayServerTV-device-auth-v1\n$serverId\nq83vEjRWeJA0Vni8mY7cE3A2m9vLz5n0kQ1y8f3dXeM=".toByteArray())
        assertTrue(verifier.verify(Base64.getDecoder().decode(auth["signature"]!!.jsonPrimitive.content)))
        assertEquals("Living room TV", auth["deviceName"]!!.jsonPrimitive.content)
        assertTrue(auth["requestAccess"]!!.jsonPrimitive.boolean)
        assertEquals(serverId, c.state.value.yarmiplay.serverId)
        assertEquals("approved", c.state.value.yarmiplay.access)
        assertEquals(listOf(serverId), keys.servers)

        val fp = Yarmiplay.fingerprint(spki)
        transport.receive("""{"Yarmiplay":{"status":{"state":"pending","fingerprint":"$fp"}}}""")
        eventually("pending") { c.state.value.yarmiplay.device == DeviceState.Pending(fp) }
        transport.receive("""{"Yarmiplay":{"status":{"state":"approved","fingerprint":"$fp"}}}""")
        transport.receive(hello(marker.replace("\"none\"", "\"approved\"")))
        eventually("logged in") { c.state.value.status == ConnectionStatus.CONNECTED }
        assertEquals(DeviceState.Approved, c.state.value.yarmiplay.device)
        assertTrue(c.state.value.yarmiplay.approvedDevice)
        assertEquals(
            listOf(DeviceState.Pending(fp), DeviceState.Approved),
            events.filterIsInstance<SyncplayEvent.DeviceStateChanged>().map { it.state },
        )
    }

    @Test
    fun `a silent check doesn't ask for access, and a bad challenge stops the client`() {
        client(requestAccess = false)
        transport.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"$serverId","nonce":"bm9uY2U=","access":"approved"}}}""")
        val auth = eventuallyValue("auth") { yarmiplaySent().firstOrNull { "auth" in it } }["auth"]!!.jsonObject
        assertFalse(auth["requestAccess"]!!.jsonPrimitive.boolean)

        transports.clear()
        val c = client()
        transport.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"../../etc","nonce":"bm9uY2U=","access":"approved"}}}""")
        eventually("stopped") { events.any { it is SyncplayEvent.Disconnected && !it.willReconnect } }
        assertTrue(yarmiplaySent().none { "auth" in it })
        assertEquals(ConnectionStatus.DISCONNECTED, c.state.value.status)
    }

    @Test
    fun `waiting for approval outlasts the usual timeout, but not 90 seconds of silence`() {
        val c = client()
        transport.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"$serverId","nonce":"bm9uY2U=","access":"approved"}}}""")
        transport.receive("""{"Yarmiplay":{"status":{"state":"pending","fingerprint":"3F2A-91BC-04DE-7710"}}}""")
        eventually("pending") { c.state.value.yarmiplay.device is DeviceState.Pending }
        val first = transport
        clock.advance(60.0)
        Thread.sleep(400)
        assertFalse(first.closed)
        first.receive("""{"Yarmiplay":{"status":{"state":"pending","fingerprint":"3F2A-91BC-04DE-7710"}}}""")
        Thread.sleep(200)
        clock.advance(80.0)
        Thread.sleep(400)
        assertFalse(first.closed)
        clock.advance(15.0)
        eventually("closed after silence") { first.closed }
    }

    @Test
    fun `no reconnect after the host refuses`() {
        for (state in listOf("denied", "required", "expired")) {
            transports.clear()
            events.clear()
            val c = client()
            transport.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"$serverId","nonce":"bm9uY2U=","access":"approved"}}}""")
            transport.receive("""{"Yarmiplay":{"status":{"state":"$state","fingerprint":"3F2A-91BC-04DE-7710"}}}""")
            transport.receive("""{"Error":{"message":"The host denied this device"}}""")
            eventually("stopped after $state") { events.any { it is SyncplayEvent.Disconnected && !it.willReconnect } }
            assertTrue(c.state.value.yarmiplay.device.refused)
            Thread.sleep(300)
            assertEquals(1, transports.size)
            c.close()
        }
    }

    @Test
    fun `a revoked device stops in the room`() {
        val c = client()
        transport.receive(hello(marker))
        transport.receive(session())
        eventually("session") { c.session.value != null }
        transport.receive("""{"Yarmiplay":{"status":{"state":"revoked","fingerprint":"3F2A-91BC-04DE-7710"}}}""")
        transport.receive("""{"Error":{"message":"The host removed this device"}}""")
        eventually("stopped") { events.any { it is SyncplayEvent.Disconnected && !it.willReconnect } }
        assertEquals(DeviceState.Revoked, c.state.value.yarmiplay.device)
        assertTrue(events.any { it == SyncplayEvent.SessionEnded })
        Thread.sleep(300)
        assertEquals(1, transports.size)
    }

    @Test
    fun `a reconnect checks the key silently`() {
        val c = client()
        transport.receive(hello(marker))
        eventually("connected") { c.state.value.status == ConnectionStatus.CONNECTED }
        transport.close()
        val second = eventuallyValue("reconnect", timeoutMs = 4_000) { transports.getOrNull(1) }
        second.receive("""{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"$serverId","nonce":"bm9uY2U=","access":"approved"}}}""")
        val auth = eventuallyValue("auth") { yarmiplaySent(second).firstOrNull { "auth" in it } }["auth"]!!.jsonObject
        assertFalse(auth["requestAccess"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `quick hash, fingerprint and signed message match the server`() {
        // Vectors from YarmiplayServerTV's scripts/fake_peer.py quick_hash.
        val big = ByteArray(3 * (1 shl 20) + 5) { ((it * 31 + 7) % 251).toByte() }
        val small = ByteArray(1000) { ((it * 31 + 7) % 251).toByte() }
        fun hash(data: ByteArray) = QuickHash.of(data.size.toLong()) { offset, buf -> System.arraycopy(data, offset.toInt(), buf, 0, buf.size) }
        assertEquals("f0395d68e53fb45c9201eff2d0f08e0cb32416d6c3b035e1ecb97d5fd716912a", hash(big))
        assertEquals("21c811b45c3053ff584fa2324b85092d33020d69fab2295e8d1c1595c00533c7", hash(small))

        val fp = Yarmiplay.fingerprint(ByteArray(91) { it.toByte() })
        assertTrue(fp, Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}").matches(fp))
        assertEquals(
            "YarmiplayServerTV-device-auth-v1\n$serverId\nq83v+/=",
            String(Yarmiplay.signedMessage(serverId, "q83v+/="), Charsets.UTF_8),
        )
        assertTrue(Yarmiplay.isValidServerId(serverId))
        assertFalse(Yarmiplay.isValidServerId(serverId.uppercase()))
        assertFalse(Yarmiplay.isValidServerId("abc"))
        assertEquals("""{"session":{"token":"…"}}""", SyncplayClient.redact("""{"session":{"token":"$token"}}"""))
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

/** A connection whose server lines come from the test; everything the client writes is recorded. */
class ScriptedTransport : Transport {
    private val incoming = Channel<String?>(Channel.UNLIMITED)
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false
        private set

    override val isTls: Boolean = false
    override suspend fun connect() {}
    override suspend fun readLine(): String? = if (closed) null else incoming.receive()
    override suspend fun writeLine(line: String) {
        if (!closed) sent += line
    }
    override suspend fun startTls() = error("no TLS")

    fun receive(line: String) {
        incoming.trySend(line)
    }

    fun sentJson(): List<JsonObject> = sent.toList().map { Json.parseToJsonElement(it).jsonObject }

    override fun close() {
        closed = true
        incoming.trySend(null)
    }
}

/** P-256 keys in memory, one per server. */
class TestDeviceAuth : DeviceAuth {
    private val pairs = HashMap<String, KeyPair>()
    val servers: List<String> get() = pairs.keys.toList()
    override val deviceName = "Living room TV"

    private fun pair(serverId: String) = pairs.getOrPut(serverId) {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }

    override fun publicKey(serverId: String): ByteArray = pair(serverId).public.encoded

    override fun sign(serverId: String, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair(serverId).private)
            update(message)
            sign()
        }
}
