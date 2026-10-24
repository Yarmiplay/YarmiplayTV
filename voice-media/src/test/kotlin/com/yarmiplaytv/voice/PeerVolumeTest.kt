package com.yarmiplaytv.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

/** Local encode→decode checks that peer volume scales RMS without a server. */
class PeerVolumeTest {
    @Test
    fun `peer volume scales decoded rms`() {
        val opus = OpusCodec(32_000, fec = true, dtx = false)
        val n = opus.frameSamples
        var phase = 0.0
        val step = 2 * Math.PI * 440.0 / 48_000.0
        fun tone(): ShortArray = ShortArray(n) {
            val s = (sin(phase) * 12_000).toInt().toShort()
            phase += step
            s
        }
        repeat(5) { opus.decode(opus.encode(tone())!!) }

        val frame = opus.encode(tone())!!
        val full = opus.decode(frame)!!
        fun rms(pcm: ShortArray): Double {
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s.toDouble()
            return sqrt(sum / pcm.size)
        }
        val fullRms = rms(full)
        val attenuated = ShortArray(full.size) { i -> (full[i] * 0.4f).toInt().toShort() }
        val attRms = rms(attenuated)
        assertTrue("full rms=$fullRms", fullRms > 50.0)
        assertTrue("attenuated rms=$attRms", attRms > 15.0)
        assertTrue("0.4 quieter", attRms < fullRms * 0.9)
    }
}
