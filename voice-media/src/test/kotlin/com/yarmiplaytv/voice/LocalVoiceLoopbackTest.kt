package com.yarmiplaytv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-process Alice→Bob loopback: PCM → Opus → yv1 seal → open → Opus → PCM.
 * Covers the encode/packet/decode path shared by Android, desktop, and web (WebCodecs aside).
 * Live UDP/TCP transport remains in [VoiceE2ETest] when SYNCPLAY_TEST_SERVER is set.
 */
class LocalVoiceLoopbackTest {
    @Test
    fun `audio frames from alice are audible after bob decrypts and decodes`() {
        val key = ByteArray(32) { it.toByte() }
        val token = 0x0123456789abcdefL
        val alice = OpusCodec(32_000, fec = true, dtx = false)
        val bob = OpusCodec(32_000, fec = true, dtx = false)
        val n = alice.frameSamples
        var phase = 0.0
        val step = 2 * Math.PI * 440.0 / 48_000.0
        fun tone(): ShortArray = ShortArray(n) {
            val s = (sin(phase) * 14_000).toInt().toShort()
            phase += step
            s
        }
        // Warm codecs (same as a short talk burst).
        repeat(3) {
            val warm = alice.encode(tone())!!
            bob.decode(warm)
        }

        var peakRms = 0.0
        var frames = 0
        repeat(25) {
            val pcm = tone()
            val opus = alice.encode(pcm)
            assertNotNull(opus)
            val packet = Yv1Packet.seal(
                key = key,
                type = Yv1Packet.TYPE_AUDIO,
                token = token,
                sender = 17L,
                sequence = frames,
                timestamp = frames * n,
                plain = opus!!,
            )
            assertNotNull(packet)
            val opened = Yv1Packet.open(key, packet!!)
            assertNotNull(opened)
            assertEquals(Yv1Packet.TYPE_AUDIO, opened!!.type)
            assertEquals(17L, opened.sender)
            val decoded = bob.decode(opened.plain)
            assertNotNull(decoded)
            peakRms = maxOf(peakRms, rms(decoded!!))
            frames++
        }
        assertTrue("bob should hear alice tone (peakRms=$peakRms)", peakRms > 50.0)
    }

    @Test
    fun `self mute and transmit flags gate sending`() {
        val session = VoiceMediaSession(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        session.setTransmitting(true)
        assertTrue(session.isTransmitting())
        session.setSelfMuted(true)
        assertTrue(session.isSelfMuted())
        assertTrue(!session.isTransmitting())
        session.setSelfMuted(false)
        session.setTransmitting(true)
        assertTrue(session.isTransmitting())
        session.setTransmitting(false)
        assertTrue(!session.isTransmitting())
        session.disconnect()
    }

    private fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / pcm.size.coerceAtLeast(1))
    }
}
