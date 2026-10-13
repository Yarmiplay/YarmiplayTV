package com.yarmiplaytv.tracking.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns decoded sound of any rate and channel count into the mono [outRate] Hz samples the [Fingerprinter] takes:
 * channels are averaged, a low-pass filter takes away what the new rate can't hold, and samples are read between the
 * filtered ones. Feed it in pieces with [push]; what it makes goes to [sink] as it is made.
 */
class Resampler(
    private val inRate: Int,
    private val channels: Int,
    private val sink: (ShortArray, Int) -> Unit,
    private val outRate: Int = Fingerprinter.SAMPLE_RATE,
) {
    private val step = inRate.toDouble() / outRate
    private val taps: DoubleArray = lowPass(minOf(inRate, outRate) * 0.45 / inRate)
    private val half = taps.size / 2
    /** Mono input not yet used up; [base] is the stream index of its first sample. */
    private var buffer = DoubleArray(maxOf(4096, taps.size * 4))
    private var length = 0
    private var base = 0L
    /** Where in the input, in input samples, the next output sample is. */
    private var position = 0.0
    private val out = ShortArray(OUT_CHUNK)
    private var outCount = 0

    /** Adds [frames] frames of interleaved 16-bit [pcm]. */
    fun push(pcm: ShortArray, frames: Int) = pushMono(frames) { f ->
        var sum = 0
        for (c in 0 until channels) sum += pcm[f * channels + c]
        sum.toDouble() / channels
    }

    /** Adds [frames] frames of interleaved float [pcm] (-1..1). */
    fun push(pcm: FloatArray, frames: Int) = pushMono(frames) { f ->
        var sum = 0.0
        for (c in 0 until channels) sum += pcm[f * channels + c]
        sum / channels * Short.MAX_VALUE
    }

    /** Sends what is still waiting; the input's last [half] samples are read as if silence followed. */
    fun flush() {
        pushMono(half) { 0.0 }
        if (outCount > 0) sink(out, outCount)
        outCount = 0
    }

    private inline fun pushMono(frames: Int, sample: (Int) -> Double) {
        if (length + frames > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + frames))
        for (f in 0 until frames) buffer[length + f] = sample(f)
        length += frames
        produce()
    }

    private fun produce() {
        val end = base + length
        while (true) {
            val at = floor(position).toLong()
            if (at + 1 + half >= end) break
            val frac = position - at
            val a = filtered(at)
            val b = filtered(at + 1)
            out[outCount++] = (a + (b - a) * frac).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            if (outCount == out.size) {
                sink(out, outCount)
                outCount = 0
            }
            position += step
        }
        // Keep what the filter still needs around the next output sample.
        val keepFrom = floor(position).toLong() - half
        val drop = (keepFrom - base).coerceIn(0, length.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, length - drop)
            length -= drop
            base += drop
        }
    }

    /** The low-passed input at stream index [n]; before the start counts as silence. */
    private fun filtered(n: Long): Double {
        var sum = 0.0
        for (k in taps.indices) {
            val i = n - half + k - base
            if (i >= 0) sum += taps[k] * buffer[i.toInt()]
        }
        return sum
    }

    companion object {
        private const val OUT_CHUNK = 4096
        private const val TAPS = 63

        /** A windowed-sinc low-pass filter passing up to [cutoff] (as a share of the input rate). */
        private fun lowPass(cutoff: Double): DoubleArray {
            val m = TAPS - 1
            val taps = DoubleArray(TAPS) { i ->
                val x = i - m / 2.0
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                sinc * (0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m))
            }
            val sum = taps.sum()
            for (i in taps.indices) taps[i] /= sum
            return taps
        }
    }
}
