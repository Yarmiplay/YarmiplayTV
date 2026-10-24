package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.DeviceAuth
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.PlayerAdapter
import com.yarmiplaytv.syncplay.SyncplayClient
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.SyncplayEvent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 2.7.0 against pre-voice servers: YarmiplayServerTV 1.9.x (no capabilities.voice) and stock Syncplay.
 *
 * Env:
 * - `YARMIPLAY_196_SERVER=host:port` — YarmiplayServerTV ≤1.9.6 (extension session, no voice)
 * - `VANILLA_SYNCPLAY_SERVER=host:port` — stock Syncplay (no Yarmiplay)
 *
 * Also binds a honeypot on UDP/TCP 9000 and fails if anything connects.
 */
class BackwardCompatE2ETest {
    private val yarmi196 = System.getenv("YARMIPLAY_196_SERVER").orEmpty()
    private val vanilla = System.getenv("VANILLA_SYNCPLAY_SERVER").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<SyncplayClient>()
    private var udpHoneypot: DatagramSocket? = null
    private var tcpHoneypot: ServerSocket? = null
    private val udpHits = AtomicInteger(0)
    private val tcpHits = AtomicInteger(0)
    private var honeypotThreads = listOf<Thread>()

    @Before
    fun setUp() {
        assumeTrue(
            "set YARMIPLAY_196_SERVER and/or VANILLA_SYNCPLAY_SERVER",
            yarmi196.isNotBlank() || vanilla.isNotBlank(),
        )
        startHoneypot9000()
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
        scope.cancel()
        honeypotThreads.forEach { it.interrupt() }
        runCatching { udpHoneypot?.close() }
        runCatching { tcpHoneypot?.close() }
    }

    @Test
    fun `YarmiplayServerTV 1_9_6 has no voice and sync chat playlist still work`() = runBlocking {
        assumeTrue("YARMIPLAY_196_SERVER not set", yarmi196.isNotBlank())
        val room = "compat-196-" + System.nanoTime()
        val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
        val alice = connect(yarmi196, "alice-196", room, deviceAuth = MemDeviceAuth("alice-pc"), events)
        val bob = connect(yarmi196, "bob-196", room, deviceAuth = MemDeviceAuth("bob-pc"), events)
        waitConnected(alice, bob)

        // Pre-voice server: extension session OK, no voice capability / descriptor.
        assertTrue("1.9.6 should open a Yarmiplay session", alice.state.value.yarmiplay.session)
        assertFalse("1.9.6 must not advertise voice", alice.state.value.yarmiplay.capabilities.voice)
        assertTrue("voice descriptor omitted", alice.state.value.yarmiplay.voice == null)
        assertFalse("voiceAvailable must be false", alice.state.value.yarmiplay.voiceAvailable)
        assertFalse(bob.state.value.yarmiplay.voiceAvailable)

        // Calling joinVoice must not produce a seat or media path.
        alice.joinVoice(1)
        delay(1_500)
        assertTrue(
            "no VoiceJoined / VoiceClosed / VoicePeers on 1.9.6",
            events.none {
                it is SyncplayEvent.VoiceJoined ||
                    it is SyncplayEvent.VoiceClosed ||
                    it is SyncplayEvent.VoicePeersChanged ||
                    it is SyncplayEvent.VoiceActivity
            },
        )
        assertFalse(alice.state.value.yarmiplay.voiceAvailable)

        // Sync + playlist + chat (same expectations as 2.6.3 ServerIntegrationTest).
        val file = FileInfo("Compat Episode.mkv", 600.0, 12_345_678)
        alice.fileLoaded(file)
        alice.addToPlaylist(file.name)
        bob.fileLoaded(file)
        eventually("bob sees playlist") { bob.state.value.playlist == listOf(file.name) }
        alice.sendChat("compat hello from 2.7.0")
        eventually("bob got chat") {
            events.any { it is SyncplayEvent.Chat && it.username == "alice-196" && it.message.contains("compat hello") }
        }
        bob.changeRoom("other-$room")
        eventually("bob changed room") { bob.state.value.room == "other-$room" }
        assertTrue("alice stays in original room", alice.state.value.room == room)

        // Jellyfin capability may be present or not depending on host config; must not error.
        val caps = alice.state.value.yarmiplay.capabilities
        println("YARMI_196_CAPS fileRelay=${caps.fileRelay} jellyfin=${caps.jellyfin} voice=${caps.voice}")
        assertFalse("voice off on 1.9.6", caps.voice)

        delay(500)
        assertEquals("no UDP hits on 9000", 0, udpHits.get())
        assertEquals("no TCP hits on 9000", 0, tcpHits.get())
        println("YARMI_196_PORT9000 udp=${udpHits.get()} tcp=${tcpHits.get()}")
    }

    @Test
    fun `vanilla Syncplay has no Yarmiplay voice and sync still works`() = runBlocking {
        assumeTrue("VANILLA_SYNCPLAY_SERVER not set", vanilla.isNotBlank())
        val room = "compat-van-" + System.nanoTime()
        val events = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
        val alice = connect(vanilla, "alice-van", room, deviceAuth = null, events)
        val bob = connect(vanilla, "bob-van", room, deviceAuth = null, events)
        waitConnected(alice, bob)

        assertFalse("vanilla has no yarmiplay session", alice.state.value.yarmiplay.session)
        assertFalse(alice.state.value.yarmiplay.capabilities.voice)
        assertTrue(alice.state.value.yarmiplay.voice == null)
        assertFalse(alice.state.value.yarmiplay.voiceAvailable)

        alice.joinVoice(1)
        delay(1_500)
        assertTrue(
            "no voice events on vanilla",
            events.none {
                it is SyncplayEvent.VoiceJoined ||
                    it is SyncplayEvent.VoiceClosed ||
                    it is SyncplayEvent.VoicePeersChanged
            },
        )

        val file = FileInfo("Vanilla Episode.mkv", 600.0, 99_999)
        alice.fileLoaded(file)
        alice.addToPlaylist(file.name)
        bob.fileLoaded(file)
        eventually("vanilla playlist") { bob.state.value.playlist.contains(file.name) || alice.state.value.playlist.contains(file.name) }
        alice.sendChat("vanilla chat from 2.7.0")
        eventually("vanilla chat") {
            events.any { it is SyncplayEvent.Chat && it.message.contains("vanilla chat") }
        }

        delay(500)
        assertEquals("no UDP hits on 9000 (vanilla)", 0, udpHits.get())
        assertEquals("no TCP hits on 9000 (vanilla)", 0, tcpHits.get())
        println("VANILLA_PORT9000 udp=${udpHits.get()} tcp=${tcpHits.get()}")
    }

    private fun startHoneypot9000() {
        // Prefer binding 9000; if something else owns it, skip the honeypot (still assert via protocol).
        val udp = runCatching { DatagramSocket(9000, InetAddress.getByName("0.0.0.0")) }.getOrNull()
        val tcp = runCatching { ServerSocket(9000) }.getOrNull()
        if (udp == null && tcp == null) {
            println("PORT9000_HONEYPOT=skipped (port in use)")
            return
        }
        udpHoneypot = udp
        tcpHoneypot = tcp
        val threads = mutableListOf<Thread>()
        if (udp != null) {
            threads += thread(name = "honeypot-udp-9000", isDaemon = true) {
                val buf = ByteArray(2048)
                while (!Thread.currentThread().isInterrupted && !udp.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        udp.receive(p)
                        udpHits.incrementAndGet()
                        println("PORT9000_UDP_HIT from ${p.address}:${p.port} bytes=${p.length}")
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
        if (tcp != null) {
            threads += thread(name = "honeypot-tcp-9000", isDaemon = true) {
                while (!Thread.currentThread().isInterrupted && !tcp.isClosed) {
                    try {
                        val s: Socket = tcp.accept()
                        tcpHits.incrementAndGet()
                        println("PORT9000_TCP_HIT from ${s.inetAddress}")
                        s.close()
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
        honeypotThreads = threads
        println("PORT9000_HONEYPOT udp=${udp != null} tcp=${tcp != null}")
    }

    private fun connect(
        server: String,
        name: String,
        room: String,
        deviceAuth: DeviceAuth?,
        events: MutableList<SyncplayEvent>,
    ): SyncplayClient {
        val host = server.substringBefore(':')
        val port = server.substringAfter(':').toInt()
        val c = SyncplayClient(
            SyncplayConfig(
                host = host,
                port = port,
                username = name,
                room = room,
                useTls = false,
                deviceAuth = deviceAuth,
                appVersion = "2.7.0",
                requestAccess = deviceAuth != null,
                autoReconnect = false,
            ),
            IdlePlayer(),
            parentScope = scope,
            log = { println("[$name] $it") },
        )
        c.events.onEach { events += it }.launchIn(scope)
        clients += c
        c.start()
        return c
    }

    private suspend fun waitConnected(vararg cs: SyncplayClient) {
        eventually("connected") {
            cs.all { it.state.value.status == ConnectionStatus.CONNECTED }
        }
    }

    private suspend fun eventually(label: String, timeoutMs: Long = 15_000, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pred()) return
            delay(50)
        }
        throw AssertionError("timed out waiting for $label")
    }

    private class IdlePlayer : PlayerAdapter {
        override val isFileLoaded = true
        override val duration = 600.0
        override val position = 0.0
        override val isPaused = true
        override fun setPaused(paused: Boolean) = Unit
        override fun seek(position: Double) = Unit
        override fun setSpeed(speed: Double) = Unit
    }

    private class MemDeviceAuth(override val deviceName: String) : DeviceAuth {
        private val pairs = HashMap<String, KeyPair>()
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
}
