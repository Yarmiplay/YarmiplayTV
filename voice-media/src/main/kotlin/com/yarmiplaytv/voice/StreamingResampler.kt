package com.yarmiplaytv.voice

import kotlin.math.roundToInt

/**
 * Stateful mono linear resampler. Keeps the fractional read cursor across [push] calls so
 * chunk boundaries stay continuous (a per-chunk reset clicks and sounds choppy).
 */
class StreamingResampler(
    private val inRate: Int,
    private val outRate: Int = VoicePcm.SAMPLE_RATE,
) {
    /** Samples held from the previous push (for interpolation across the boundary). */
    private var hold = ShortArray(0)
    private var frac = 0.0

    fun push(input: ShortArray): ShortArray {
        if (inRate <= 0 || outRate <= 0 || input.isEmpty()) return ShortArray(0)
        if (inRate == outRate) return input.copyOf()

        val combined = ShortArray(hold.size + input.size)
        if (hold.isNotEmpty()) System.arraycopy(hold, 0, combined, 0, hold.size)
        System.arraycopy(input, 0, combined, hold.size, input.size)

        val step = inRate.toDouble() / outRate
        // How many output samples we can produce while leaving ≥1 input sample ahead for lerp.
        val maxPos = (combined.size - 1).toDouble()
        val out = ArrayList<Short>((combined.size.toLong() * outRate / inRate).toInt().coerceAtLeast(1) + 2)
        var pos = frac
        while (pos < maxPos) {
            val idx = pos.toInt()
            val f = pos - idx
            val a = combined[idx].toInt()
            val b = combined[idx + 1].toInt()
            out += (a + (b - a) * f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pos += step
        }
        // Keep unread tail + fractional offset into the next chunk.
        val keepFrom = pos.toInt().coerceIn(0, combined.size)
        hold = combined.copyOfRange(keepFrom, combined.size)
        frac = pos - keepFrom
        return out.toShortArray()
    }

    fun reset() {
        hold = ShortArray(0)
        frac = 0.0
    }
}
