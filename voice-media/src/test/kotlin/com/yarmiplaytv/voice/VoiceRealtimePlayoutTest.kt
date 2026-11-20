package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import com.yarmiplaytv.syncplay.VoiceMediaParams
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-time, end-to-end: Alice talks (a steady 220 Hz voice-like tone) at 20 ms pace; the
 * "network" behaves like voice on a TCP connection — packets are held and released in bursts
 * every 120 ms with extra jitter — and Bob's speaker pulls [VoiceMediaSession.renderPlayback]
 * at its own 20 ms clock in the default stereo layout.
 *
 * A robotic receive path shows up as frames that are partly or fully silent (holes / chopping),
 * a drifting pitch, or left/right mismatch. None of those may happen once playout has started.
 */
class VoiceRealtimePlayoutTest {
    @Test
    fun burstyTcpDeliveryPlaysBackSmoothlyInStereo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val alicePipe = Pipe()
        val bobPipe = BurstyPipe(burstMs = 120, jitterMs = 30, seed = 5L)
        alicePipe.peer = bobPipe
        val alice = VoiceMediaSession(scope)
        val bob = VoiceMediaSession(scope)
        val frames = ArrayList<ShortArray>()
        try {
            assertTrue(alice.connect(params(alicePipe)))
            bob.setPreferredStereo(true)
            assertTrue(bob.connect(params(bobPipe)))
            assertEquals(2, bob.activeChannels)
            alice.setNoiseSuppression(false)
            alice.setAgcStageActive(true)
            alice.setTransmitting(true)

            val talkFrames = 150 // 3 s
            val tone = voiced(talkFrames + 10, hz = 220.0)
            val speaker = Executors.newSingleThreadScheduledExecutor()
            val mic = Executors.newSingleThreadScheduledExecutor()
            var micFrame = 0
            mic.scheduleAtFixedRate({
                if (micFrame < talkFrames) {
                    runBlocking { alice.offerCapturePcm(tone.copyOfRange(micFrame * 960, (micFrame + 1) * 960)) }
                    micFrame++
                }
            }, 0, 20, TimeUnit.MILLISECONDS)
            speaker.scheduleAtFixedRate({
                val out = ShortArray(960 * 2)
                bob.renderPlayback(out)
                synchronized(frames) { frames += out }
            }, 0, 20, TimeUnit.MILLISECONDS)
            Thread.sleep(talkFrames * 20L + 600)
            mic.shutdownNow()
            speaker.shutdownNow()
            bobPipe.close()
        } finally {
            alice.disconnect()
            bob.disconnect()
            scope.cancel()
        }

        val played = synchronized(frames) { frames.toList() }
        val first = played.indexOfFirst { VoicePcm.rms(it) > 500 }
        assertTrue("no audio reached the speaker", first >= 0)
        // Judge the middle of the utterance: 2 s after playout settled.
        val window = played.subList(first + 5, (first + 105).coerceAtMost(played.size))
        assertTrue("utterance too short (${window.size} frames)", window.size >= 90)
        val rms = window.map { stereo ->
            for (i in 0 until 960) assertEquals("left/right differ", stereo[i * 2], stereo[i * 2 + 1])
            VoicePcm.rms(VoicePcm.stereoToMono(stereo))
        }
        val median = rms.sorted()[rms.size / 2]
        val holes = rms.withIndex().filter { it.value < median * 0.35 }
        assertTrue("silent/chopped frames during speech: ${holes.map { "${it.index}:${it.value.toInt()}" }} median=${median.toInt()}", holes.isEmpty())
        // Each half of every frame must carry audio: catches half-filled frames (10 ms on / 10 ms off).
        for ((i, stereo) in window.withIndex()) {
            val mono = VoicePcm.stereoToMono(stereo)
            val a = VoicePcm.rms(mono.copyOfRange(0, 480))
            val b = VoicePcm.rms(mono.copyOfRange(480, 960))
            assertTrue("frame $i is half silent (a=${a.toInt()} b=${b.toInt()})", a > median * 0.25 && b > median * 0.25)
        }
        val hz = VoicePcm.estimateHz(VoicePcm.stereoToMono(window[window.size / 2]))
        assertTrue("pitch drifted to $hz Hz", abs(hz - 220.0) < 25.0)
    }

    private fun voiced(frames: Int, hz: Double): ShortArray {
        val n = 960 * frames
        return ShortArray(n) { i ->
            val t = i / 48_000.0
            var s = 0.0
            for (k in 1..4) s += (1.0 / k) * sin(2.0 * PI * hz * k * t)
            (s / 2.1 * 0.35 * 32_767.0).toInt().coerceIn(-32_767, 32_767).toShort()
        }
    }

    private fun params(mux: SyncplayVoiceMux) = VoiceMediaParams(
        host = "yarmiplay.tv",
        token = "0123456789abcdef",
        key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        provider = "discord-lobby",
        appId = "1210325027023753307",
        lobbyId = "987654321012345678",
        lobbySecret = "topsecrettoken",
        syncplayMux = mux,
        syncplayMediaOnly = true,
    )

    private open class Pipe : SyncplayVoiceMux {
        var peer: Pipe? = null
        protected var handler: ((ByteArray) -> Unit)? = null
        override fun send(packet: ByteArray) {
            peer?.deliver(packet)
        }
        open fun deliver(packet: ByteArray) {
            handler?.invoke(packet)
        }
        override fun setOnPacket(handler: ((ByteArray) -> Unit)?) {
            this.handler = handler
        }
    }

    /** Holds packets and releases them in bursts (a TCP stall), each delayed by extra jitter. */
    private class BurstyPipe(burstMs: Long, private val jitterMs: Int, seed: Long) : Pipe() {
        private val rng = Random(seed)
        private val pending = ArrayList<ByteArray>()
        private val net = Executors.newSingleThreadScheduledExecutor()

        init {
            net.scheduleAtFixedRate({
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                val extra = rng.nextInt(jitterMs + 1).toLong()
                net.schedule({ for (p in batch) handler?.invoke(p) }, extra, TimeUnit.MILLISECONDS)
            }, burstMs, burstMs, TimeUnit.MILLISECONDS)
        }

        override fun deliver(packet: ByteArray) {
            synchronized(pending) { pending += packet }
        }

        fun close() = net.shutdownNow()
    }
}
