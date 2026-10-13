package com.yarmiplaytv.tracking.detect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class FingerprintTest {
    private val hop = Fingerprinter.HOP_SECONDS

    private fun meanBitErrors(a: IntArray, b: IntArray): Double {
        val n = minOf(a.size, b.size)
        return (0 until n).sumOf { Integer.bitCount(a[it] xor b[it]) }.toDouble() / n
    }

    @Test
    fun sameSoundSameNumbers() {
        val pcm = Synth.music(1, 20.0)
        val fp = Synth.fingerprint(pcm)
        assertTrue(fp.size > 130)
        assertArrayEquals(fp, Synth.fingerprint(pcm))
    }

    @Test
    fun pieceSizeDoesNotMatter() {
        val pcm = Synth.music(2, 15.0)
        assertArrayEquals(Synth.fingerprint(pcm, chunk = pcm.size), Synth.fingerprint(pcm, chunk = 333))
    }

    @Test
    fun noiseChangesFewBitsAndOtherMusicMany() {
        val pcm = Synth.music(3, 30.0)
        val fp = Synth.fingerprint(pcm)
        val noisy = Synth.fingerprint(Synth.mix(pcm, Synth.noise(9, pcm.size, 0.008)))
        val other = Synth.fingerprint(Synth.music(4, 30.0))
        val near = meanBitErrors(fp, noisy)
        val far = meanBitErrors(fp, other)
        assertTrue("noise: $near bits", near <= 4.0)
        assertTrue("other music: $far bits", far >= 12.0)
    }

    @Test
    fun silenceIsOneValue() {
        val fp = Synth.fingerprint(Synth.silence(10.0))
        assertTrue(fp.isNotEmpty())
        assertTrue(fp.all { it == Fingerprinter.SILENCE })
    }

    @Test
    fun findsTheSharedSongWhereverItIs() {
        val theme = Synth.music(100, 90.0)
        val a = Synth.concat(Synth.music(1, 60.0), theme, Synth.music(2, 40.0))
        val b = Synth.concat(Synth.music(3, 20.0), theme, Synth.music(4, 70.0))
        val runs = FingerprintMatcher.runs(
            Synth.fingerprint(a), Synth.fingerprint(Synth.mix(b, Synth.noise(5, b.size, 0.005))),
            minPoints = (15 / hop).roundToInt(), maxPoints = (120 / hop).roundToInt(),
        )
        assertTrue(runs.isNotEmpty())
        val run = runs.first()
        val aStart = FingerprintMatcher.startSeconds(run.aStart)
        val aEnd = FingerprintMatcher.endSeconds(run.aEnd)
        val bStart = FingerprintMatcher.startSeconds(run.bStart)
        val bEnd = FingerprintMatcher.endSeconds(run.bEnd)
        assertEquals("a $aStart-$aEnd b $bStart-$bEnd", 60.0, aStart, 0.5)
        assertEquals("a end $aEnd", 150.0, aEnd, 0.5)
        assertEquals("b start $bStart", 20.0, bStart, 0.5)
        assertEquals("b end $bEnd", 110.0, bEnd, 0.5)
    }

    @Test
    fun aShortSharedBitIsNoOpening() {
        val jingle = Synth.music(100, 8.0)
        val a = Synth.concat(Synth.music(1, 30.0), jingle, Synth.music(2, 30.0))
        val b = Synth.concat(Synth.music(3, 50.0), jingle, Synth.music(4, 30.0))
        val runs = FingerprintMatcher.runs(Synth.fingerprint(a), Synth.fingerprint(b), (15 / hop).roundToInt(), (120 / hop).roundToInt())
        assertTrue(runs.toString(), runs.isEmpty())
    }

    @Test
    fun differentEpisodesShareNothing() {
        val runs = FingerprintMatcher.runs(
            Synth.fingerprint(Synth.music(1, 120.0)), Synth.fingerprint(Synth.music(2, 120.0)), 1, 10_000,
        )
        assertTrue(runs.toString(), runs.none { it.length * hop > 3 })
    }

    @Test
    fun bridgesALineSpokenOverTheSong() {
        val theme = Synth.music(100, 90.0)
        val spoken = theme.copyOf()
        // Two seconds of something else over the song, halfway through.
        val at = 45 * Synth.RATE
        Synth.music(77, 2.0, gain = 0.6).copyInto(spoken, at)
        val a = Synth.concat(Synth.music(1, 30.0), theme, Synth.music(2, 30.0))
        val b = Synth.concat(Synth.music(3, 30.0), spoken, Synth.music(4, 30.0))
        val run = FingerprintMatcher.runs(Synth.fingerprint(a), Synth.fingerprint(b), (15 / hop).roundToInt(), (120 / hop).roundToInt()).firstOrNull()
        assertNotNull(run)
        assertTrue("run of ${run!!.length * hop} s", abs(run.length * hop - 90) < 3)
    }

    @Test
    fun resamplerKeepsTheSound() {
        // 48 kHz stereo made into 11025 Hz mono fingerprints like music made at 11025 Hz.
        val direct = Synth.music(5, 20.0)
        val high = Synth.music(5, 20.0, rate = 48_000)
        val stereo = ShortArray(high.size * 2) { high[it / 2] }
        val out = ArrayList<Short>()
        val resampler = Resampler(48_000, 2, { pcm, n -> for (i in 0 until n) out += pcm[i] })
        var i = 0
        while (i < high.size) {
            val frames = minOf(1000, high.size - i)
            resampler.push(stereo.copyOfRange(i * 2, (i + frames) * 2), frames)
            i += frames
        }
        resampler.flush()
        assertEquals(direct.size.toDouble(), out.size.toDouble(), 30.0)
        val errors = meanBitErrors(Synth.fingerprint(direct), Synth.fingerprint(out.toShortArray()))
        assertTrue("resampled: $errors bits", errors <= 4.0)
    }
}
