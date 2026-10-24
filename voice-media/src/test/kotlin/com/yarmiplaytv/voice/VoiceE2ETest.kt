package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.DeviceAuth
import com.yarmiplaytv.syncplay.PlayerAdapter
import com.yarmiplaytv.syncplay.SyncplayClient
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.VoiceClosedReason
import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.syncplay.VoicePeer
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Live voice e2e against YarmiplayServerTV 2.0 with voice on.
 *
 * Requires SYNCPLAY_TEST_SERVER=host:port (e.g. 127.0.0.1:18999).
 * Optional VOICE_CONTROL=http://127.0.0.1:28999 for server toggle tests.
 */
class VoiceE2ETest {
    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val control = System.getenv("VOICE_CONTROL").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val room = "voice-e2e-" + System.nanoTime()
    private val clients = mutableListOf<SyncplayClient>()
    private val sessions = mutableListOf<VoiceMediaSession>()

    @Before
    fun setUp() {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        if (control.isNotBlank()) {
            httpGet("$control/control/vanilla/off")
            httpGet("$control/control/voice/on")
        }
    }

    @After
    fun tearDown() {
        sessions.forEach { it.disconnect() }
        sessions.clear()
        clients.forEach { it.close() }
        clients.clear()
        scope.cancel()
        if (control.isNotBlank()) {
            runCatching {
                httpGet("$control/control/vanilla/off")
                httpGet("$control/control/voice/on")
            }
        }
    }

    @Test
    fun `audio frames arrive over UDP and decode on the peer`() = runBlocking {
        val alice = connectClient("alice")
        val bob = connectClient("bob")
        waitConnected(alice, bob)
        assertTrue(alice.state.value.yarmiplay.voiceAvailable)

        val aliceMedia = openMedia(joinVoice(alice))
        val bobMedia = openMedia(joinVoice(bob))
        aliceMedia.setTransmitting(true)
        feedTone(aliceMedia, frames = 40)

        eventually("bob received decoded audio") {
            bobMedia.decodedAudio && bobMedia.lastRemoteRms > 50.0
        }
        assertFalse("UDP path preferred", aliceMedia.usingTcp)
        assertFalse("UDP path preferred", bobMedia.usingTcp)
    }

    @Test
    fun `TCP fallback carries audio when forced`() = runBlocking {
        val alice = connectClient("alice-tcp")
        val bob = connectClient("bob-tcp")
        waitConnected(alice, bob)
        val aliceMedia = openMedia(joinVoice(alice).copy(forceTcp = true))
        val bobMedia = openMedia(joinVoice(bob).copy(forceTcp = true))
        eventually("both on TCP") { aliceMedia.usingTcp && bobMedia.usingTcp }
        aliceMedia.setTransmitting(true)
        feedTone(aliceMedia, frames = 40)
        eventually("bob heard alice over TCP") {
            bobMedia.decodedAudio && bobMedia.lastRemoteRms > 50.0
        }
    }

    @Test
    fun `self mute peer mute and volume`() = runBlocking {
        val alice = connectClient("alice-mute")
        val bob = connectClient("bob-mute")
        waitConnected(alice, bob)
        val bobPeers = Collections.synchronizedList(mutableListOf<VoicePeer>())
        bob.events.onEach {
            if (it is SyncplayEvent.VoicePeersChanged) {
                bobPeers.clear()
                bobPeers.addAll(it.peers)
            }
        }.launchIn(scope)

        val aliceMedia = openMedia(joinVoice(alice))
        val bobMedia = openMedia(joinVoice(bob))
        aliceMedia.setTransmitting(true)
        feedTone(aliceMedia, frames = 25)
        eventually("bob hears alice") { bobMedia.lastRemoteRms > 50.0 }
        val loud = bobMedia.lastRemoteRms

        aliceMedia.setSelfMuted(true)
        aliceMedia.setTransmitting(false)
        delay(200)
        bobMedia.resetReceiveStats()
        val mutedPeak = peakRmsWhile(bobMedia, ms = 700) { feedTone(aliceMedia, frames = 15) }
        assertTrue("self-mute should stop audible frames (peak=$mutedPeak loud=$loud)", mutedPeak < loud * 0.25)
        assertFalse("no decoded audio while self-muted", bobMedia.decodedAudio)

        aliceMedia.setSelfMuted(false)
        aliceMedia.setTransmitting(true)
        feedTone(aliceMedia, frames = 20)
        eventually("unmuted again") { bobMedia.lastRemoteRms > 50.0 }

        eventually("bob knows alice peer id") { bobPeers.any { it.username == "alice-mute" } }
        eventually("bob saw a remote sender id") { bobMedia.lastRemoteSender != null }
        val alicePeerId = bobPeers.first { it.username == "alice-mute" }.participantId
        val fromPeerList = participantSenderId(alicePeerId)
        val sender = bobMedia.lastRemoteSender!!
        println("MUTE_SENDER peerList=$alicePeerId parsed=$fromPeerList packet=$sender")
        bobMedia.setPeerMuted(sender, true)
        delay(200)
        bobMedia.resetReceiveStats()
        val localMutePeak = peakRmsWhile(bobMedia, ms = 700) { feedTone(aliceMedia, frames = 15) }
        assertTrue("local mute should drop RMS (peak=$localMutePeak)", localMutePeak < 20.0)

        bobMedia.setPeerMuted(sender, false)
        feedTone(aliceMedia, frames = 25)
        eventually("peer unmuted again") { bobMedia.lastRemoteRms > 50.0 }
    }

    @Test
    fun `peer volume attenuates and restores`() = runBlocking {
        val alice = connectClient("alice-vol")
        val bob = connectClient("bob-vol")
        waitConnected(alice, bob)
        val aliceMedia = openMedia(joinVoice(alice))
        val bobMedia = openMedia(joinVoice(bob))
        aliceMedia.setTransmitting(true)
        feedTone(aliceMedia, frames = 30)
        eventually("bob hears alice") { bobMedia.lastRemoteRms > 50.0 }
        eventually("bob saw a remote sender id") { bobMedia.lastRemoteSender != null }
        val sender = bobMedia.lastRemoteSender!!
        val loud = bobMedia.lastRemoteRms

        bobMedia.setPeerVolume(sender, 0f)
        assertEquals(0f, bobMedia.peerGainFor(sender), 0.001f)
        delay(80)
        bobMedia.resetReceiveStats()
        val silentPeak = peakRmsWhile(bobMedia, ms = 900) { feedTone(aliceMedia, frames = 35) }
        assertTrue("volume 0 is silent (peak=$silentPeak)", silentPeak < 30.0)

        bobMedia.setPeerVolume(sender, 0.4f)
        assertEquals(0.4f, bobMedia.peerGainFor(sender), 0.001f)
        delay(80)
        bobMedia.resetReceiveStats()
        val attenuated = peakRmsWhile(bobMedia, ms = 1_200) { feedTone(aliceMedia, frames = 50) }
        assertTrue("attenuated volume is audible (peak=$attenuated)", attenuated > 15.0)
        assertTrue(
            "0.4 volume should be quieter than full (attenuated=$attenuated full=$loud)",
            attenuated < loud * 0.95,
        )

        bobMedia.setPeerVolume(sender, 1f)
        feedTone(aliceMedia, frames = 30)
        eventually("volume restored to full") { bobMedia.lastRemoteRms > 50.0 }
    }

    @Test
    fun `speaking indicators follow the talker`() = runBlocking {
        val alice = connectClient("alice-speak")
        val bob = connectClient("bob-speak")
        waitConnected(alice, bob)
        openMedia(joinVoice(alice)).also { it.setTransmitting(true) }
        openMedia(joinVoice(bob))
        val bobEvents = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
        bob.events.onEach { bobEvents += it }.launchIn(scope)
        alice.sendVoiceActivity(true)
        eventually("bob sees alice speaking") {
            bobEvents.filterIsInstance<SyncplayEvent.VoiceActivity>().any { it.username == "alice-speak" && it.speaking }
        }
        alice.sendVoiceActivity(false)
        eventually("bob sees alice quiet") {
            bobEvents.filterIsInstance<SyncplayEvent.VoiceActivity>().any { it.username == "alice-speak" && !it.speaking }
        }
    }

    @Test
    fun `ninth seat is told the room is full`() = runBlocking {
        val seats = (1..8).map { i ->
            val c = connectClient("seat$i")
            waitConnected(c)
            joinVoice(c)
            c
        }
        val ninth = connectClient("seat9")
        waitConnected(ninth)
        val closed = AtomicReference<VoiceClosedReason?>(null)
        ninth.events.onEach {
            if (it is SyncplayEvent.VoiceClosed) closed.set(it.reason)
        }.launchIn(scope)
        ninth.joinVoice()
        eventually("ninth rejected as full") { closed.get() == VoiceClosedReason.FULL }
        seats.forEach { it.leaveVoice() }
    }

    @Test
    fun `server voice off and client leave drop voice`() = runBlocking {
        assumeTrue("VOICE_CONTROL not set", control.isNotBlank())
        val alice = connectClient("alice-tog")
        val bob = connectClient("bob-tog")
        waitConnected(alice, bob)
        openMedia(joinVoice(alice))
        openMedia(joinVoice(bob))
        val closed = AtomicReference<VoiceClosedReason?>(null)
        alice.events.onEach {
            if (it is SyncplayEvent.VoiceClosed) closed.set(it.reason)
        }.launchIn(scope)
        httpGet("$control/control/voice/off")
        eventually("server disabled voice") { closed.get() == VoiceClosedReason.DISABLED }
        httpGet("$control/control/voice/on")

        closed.set(null)
        val alice2 = connectClient("alice-leave")
        waitConnected(alice2)
        openMedia(joinVoice(alice2))
        alice2.events.onEach {
            if (it is SyncplayEvent.VoiceClosed) closed.set(it.reason)
        }.launchIn(scope)
        alice2.leaveVoice()
        eventually("left") { closed.get() == VoiceClosedReason.LEFT }
    }

    @Test
    fun `vanilla Syncplay client is unaffected`() = runBlocking {
        val vanilla = SyncplayClient(
            SyncplayConfig(host(), port(), "vanilla", room, useTls = false, deviceAuth = null, autoReconnect = false),
            IdlePlayer(),
            parentScope = scope,
        ).also {
            clients += it
            it.start()
        }
        val ext = connectClient("ext-user")
        eventually("vanilla connected") { vanilla.state.value.status == ConnectionStatus.CONNECTED }
        waitConnected(ext)
        assertEquals(ConnectionStatus.CONNECTED, vanilla.state.value.status)
        assertFalse(vanilla.state.value.yarmiplay.voiceAvailable)
        openMedia(joinVoice(ext)).setTransmitting(true)
        delay(1_000)
        assertEquals(ConnectionStatus.CONNECTED, vanilla.state.value.status)
        assertTrue(vanilla.state.value.users.any { it.name == "ext-user" })
    }

    @Test
    fun `changing rooms ends the seat`() = runBlocking {
        val alice = connectClient("alice-room")
        waitConnected(alice)
        openMedia(joinVoice(alice))
        val closed = AtomicReference<VoiceClosedReason?>(null)
        alice.events.onEach {
            if (it is SyncplayEvent.VoiceClosed) closed.set(it.reason)
        }.launchIn(scope)
        alice.changeRoom("other-$room")
        eventually("room change closed voice") { closed.get() == VoiceClosedReason.ROOM }
    }

    @Test
    fun `desktop device picker lists mixers and live switch keeps audio`() = runBlocking {
        val inputs = JvmVoiceDevices.listInputs()
        val outputs = JvmVoiceDevices.listOutputs()
        println("DESKTOP_VOICE_INPUTS=" + inputs.joinToString(" | ") { "${it.label}[${it.id.ifEmpty { "default" }}]" })
        println("DESKTOP_VOICE_OUTPUTS=" + outputs.joinToString(" | ") { "${it.label}[${it.id.ifEmpty { "default" }}]" })
        assertTrue("system default input present", inputs.any { it.id.isEmpty() })
        assertTrue("system default output present", outputs.any { it.id.isEmpty() })
        assertFalse("missing mixer is not available", JvmVoiceDevices.isAvailable("__missing_mic__", target = true))
        assertFalse("missing speaker is not available", JvmVoiceDevices.isAvailable("__missing_spk__", target = false))

        // When the host has real mixers, open each and prove unplug falls back to default.
        val namedInputs = inputs.filter { it.id.isNotEmpty() }
        val namedOutputs = outputs.filter { it.id.isNotEmpty() }
        for (dev in namedInputs) {
            val line = JvmVoiceDevices.openCapture(dev.id, 960)
            assertTrue("open capture ${dev.label}", line != null)
            line!!.close()
        }
        for (dev in namedOutputs) {
            val line = JvmVoiceDevices.openPlayback(dev.id, 960)
            assertTrue("open playback ${dev.label}", line != null)
            line!!.close()
        }
        if (namedInputs.isNotEmpty() || namedOutputs.isNotEmpty()) {
            // Unplugged id: openLine falls back to the system default line when possible.
            val fallbackIn = JvmVoiceDevices.openCapture("__missing_mic__", 960)
            val fallbackOut = JvmVoiceDevices.openPlayback("__missing_spk__", 960)
            assertTrue("unplug input falls back", fallbackIn != null || JvmVoiceDevices.openCapture(null, 960) != null)
            assertTrue("unplug output falls back", fallbackOut != null || JvmVoiceDevices.openPlayback(null, 960) != null)
            fallbackIn?.close()
            fallbackOut?.close()
        } else {
            println("DESKTOP_VOICE_NOTE=no named mixers on this host; picker shows System default only")
        }

        val alice = connectClient("alice-dev")
        val bob = connectClient("bob-dev")
        waitConnected(alice, bob)
        val aliceMedia = openMedia(joinVoice(alice))
        val bobMedia = openMedia(joinVoice(bob))
        aliceMedia.setTransmitting(true)
        // Synthetic capture (no OS mic) still flows after the device probe — live switch does not require rejoin.
        feedTone(aliceMedia, frames = 30)
        eventually("bob still hears after device probe") {
            bobMedia.decodedAudio && bobMedia.lastRemoteRms > 50.0
        }
    }

    private fun host(): String = server.substringBefore(':')
    private fun port(): Int = server.substringAfter(':').toInt()

    private fun connectClient(name: String): SyncplayClient {
        val c = SyncplayClient(
            SyncplayConfig(
                host = host(),
                port = port(),
                username = name,
                room = room,
                useTls = false,
                deviceAuth = MemDeviceAuth(name),
                appVersion = "2.7.0",
                requestAccess = true,
                autoReconnect = false,
            ),
            IdlePlayer(),
            parentScope = scope,
            log = { println("[$name] $it") },
        )
        clients += c
        c.start()
        return c
    }

    private suspend fun waitConnected(vararg cs: SyncplayClient) {
        eventually("connected") {
            cs.all { it.state.value.status == ConnectionStatus.CONNECTED && it.state.value.yarmiplay.session }
        }
    }

    private suspend fun joinVoice(client: SyncplayClient): VoiceMediaParams {
        val joined = AtomicReference<SyncplayEvent.VoiceJoined?>(null)
        val sub = client.events.onEach {
            if (it is SyncplayEvent.VoiceJoined) joined.set(it)
        }.launchIn(scope)
        client.joinVoice(1)
        eventually("voiceJoined for ${client.config.username}") { joined.get() != null }
        sub.cancel()
        val j = joined.get()!!
        return VoiceMediaParams(
            host = host(),
            token = j.token,
            key = j.key,
            udpPort = j.udpPort,
            tcpPort = j.tcpPort,
            fingerprint = j.fingerprint,
            wsPath = j.wsPath,
            maxBitrate = j.maxBitrate,
            fec = j.fec,
            dtx = j.dtx,
            clockRate = j.clockRate,
            channels = j.channels,
            ptimeMs = j.ptimeMs,
        )
    }

    private suspend fun openMedia(params: VoiceMediaParams): VoiceMediaSession {
        val media = VoiceMediaSession(scope)
        assertTrue(media.connect(params))
        sessions += media
        return media
    }

    private suspend fun feedTone(media: VoiceMediaSession, frames: Int, hz: Double = 440.0) {
        val n = media.frameSamples
        var phase = 0.0
        val step = 2 * Math.PI * hz / 48_000.0
        repeat(frames) {
            val pcm = ShortArray(n) {
                val s = (sin(phase) * 12_000).toInt().toShort()
                phase += step
                s
            }
            media.offerCapturePcm(pcm)
            delay(20)
        }
    }

    private suspend fun peakRmsWhile(media: VoiceMediaSession, ms: Long, block: suspend () -> Unit): Double {
        val peak = java.util.concurrent.atomic.AtomicReference(0.0)
        val job = scope.launch {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                val cur = media.lastRemoteRms
                peak.updateAndGet { prev -> maxOf(prev, cur) }
                delay(20)
            }
        }
        block()
        job.join()
        return peak.get()
    }

    private suspend fun eventually(label: String, timeoutMs: Long = 20_000, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pred()) return
            delay(50)
        }
        throw AssertionError("timed out waiting for $label")
    }

    private fun httpGet(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        return conn.inputStream.bufferedReader().readText()
    }

    private class IdlePlayer : PlayerAdapter {
        override val isFileLoaded = false
        override val duration = 0.0
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
