package com.yarmiplaytv.tracking.detect

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Made-up music for the tests: a different chord every [chordSeconds], the same for the same [seed]. */
object Synth {
    const val RATE = Fingerprinter.SAMPLE_RATE

    fun music(seed: Int, seconds: Double, rate: Int = RATE, chordSeconds: Double = 0.45, gain: Double = 0.25): ShortArray =
        musicAt(seed, 0, (seconds * rate).toInt(), rate, chordSeconds, gain)

    /** [count] samples of [seed]'s music from sample [from] on: the same samples wherever they are asked from. */
    fun musicAt(seed: Int, from: Long, count: Int, rate: Int = RATE, chordSeconds: Double = 0.45, gain: Double = 0.25): ShortArray {
        val samples = ShortArray(count)
        val perChord = (chordSeconds * rate).toLong()
        var chord = -1L
        var freqs = DoubleArray(0)
        for (k in 0 until count) {
            val i = from + k
            val c = i / perChord
            if (c != chord) {
                chord = c
                val random = Random(seed * 1_000_003L + c * 7919L)
                val root = 40 + random.nextInt(24)
                val notes = intArrayOf(root, root + 3 + random.nextInt(2), root + 7, root + 12 - random.nextInt(3) * 2)
                freqs = DoubleArray(notes.size) { 440.0 * 2.0.pow((notes[it] - 69) / 12.0) }
            }
            val inChord = i - chord * perChord
            val t = inChord.toDouble() / rate
            val envelope = minOf(1.0, inChord / (0.02 * rate)) * (1.0 - 0.5 * inChord / perChord)
            var v = 0.0
            for (f in freqs) v += sin(2 * PI * f * t) + 0.4 * sin(4 * PI * f * t) + 0.2 * sin(6 * PI * f * t)
            samples[k] = (v / freqs.size * envelope * gain * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    fun noise(seed: Int, count: Int, gain: Double): ShortArray {
        val random = Random(seed)
        return ShortArray(count) { ((random.nextDouble() * 2 - 1) * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    fun mix(a: ShortArray, b: ShortArray): ShortArray =
        ShortArray(a.size) { (a[it] + (if (it < b.size) b[it] else 0)).coerceIn(-32768, 32767).toShort() }

    fun concat(vararg parts: ShortArray): ShortArray {
        val all = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(all, at)
            at += p.size
        }
        return all
    }

    fun silence(seconds: Double, rate: Int = RATE): ShortArray = ShortArray((seconds * rate).toInt())

    fun fingerprint(pcm: ShortArray, chunk: Int = 4000): IntArray {
        val f = Fingerprinter()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(chunk, pcm.size - i)
            f.consume(pcm.copyOfRange(i, i + n), n)
            i += n
        }
        return f.finish()
    }
}
