package com.yarmiplaytv.voice

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stateful polyphase Kaiser-windowed sinc band-limited resampler (SpeexDSP / libsamplerate grade).
 *
 * - Never uses linear or nearest-neighbor interpolation.
 * - Upsampling (e.g. 44.1 kHz or 16 kHz → 48 kHz): anti-imaging low-pass at 0.916 × input Nyquist.
 * - Downsampling (e.g. 96 kHz or 48 kHz → 44.1 kHz / 16 kHz): anti-aliasing low-pass scaled to
 *   0.916 × output Nyquist with widened kernel support so frequencies above Nyquist are attenuated
 *   by >80 dB before decimation.
 * - Preserves filter history and fractional read phase across [push] calls so chunked streaming
 *   produces the exact same samples as one-shot resampling without boundary clicks.
 */
class StreamingResampler(
    val inRate: Int,
    val outRate: Int = VoicePcm.SAMPLE_RATE,
) {
    private val kernel: PolyphaseSincKernel? =
        if (inRate > 0 && outRate > 0 && inRate != outRate) PolyphaseSincKernel.forRates(inRate, outRate) else null

    val filterTaps: Int get() = kernel?.taps ?: 1
    val cutoffHz: Double get() = kernel?.cutoffHz ?: (minOf(inRate, outRate) * 0.5)

    private var hold: FloatArray = FloatArray((kernel?.taps ?: 1) - 1)
    private var frac: Double = 0.0

    fun push(input: ShortArray): ShortArray {
        if (inRate <= 0 || outRate <= 0 || input.isEmpty()) return ShortArray(0)
        val k = kernel ?: return input.copyOf()

        val tapCount = k.taps
        val padLen = tapCount - 1
        if (hold.size < padLen) {
            val grown = FloatArray(padLen)
            System.arraycopy(hold, 0, grown, padLen - hold.size, hold.size)
            hold = grown
        }

        val combined = FloatArray(hold.size + input.size)
        System.arraycopy(hold, 0, combined, 0, hold.size)
        for (i in input.indices) {
            combined[hold.size + i] = input[i].toFloat()
        }

        val step = inRate.toDouble() / outRate.toDouble()
        val limit = (combined.size - padLen).toDouble()
        if (limit <= frac) {
            hold = combined
            return ShortArray(0)
        }

        val outCount = ceil((limit - frac) / step).toInt().coerceAtLeast(0)
        val out = ShortArray(outCount)
        var pos = frac
        var outIdx = 0
        while (pos < limit && outIdx < outCount) {
            val base = pos.toInt()
            val subFrac = pos - base
            val sample = k.convolve(combined, base, subFrac)
            out[outIdx++] = sample.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pos += step
        }

        val consumed = pos.toInt().coerceIn(0, combined.size)
        hold = combined.copyOfRange(consumed, combined.size)
        frac = pos - consumed
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    fun reset() {
        val padLen = (kernel?.taps ?: 1) - 1
        hold = FloatArray(padLen)
        frac = 0.0
    }

    internal class PolyphaseSincKernel private constructor(
        val inRate: Int,
        val outRate: Int,
        val taps: Int,
        val cutoffHz: Double,
        /** Flattened `(PHASES + 1) * taps` table for cache-friendly dot products + sub-phase lerp. */
        private val table: FloatArray,
    ) {
        fun convolve(buf: FloatArray, base: Int, frac: Double): Float {
            val phasePos = (frac.coerceIn(0.0, 0.9999999) * PHASES)
            val p = phasePos.toInt().coerceIn(0, PHASES - 1)
            val alpha = (phasePos - p).toFloat()
            val row0 = p * taps
            val row1 = (p + 1) * taps
            var sum0 = 0f
            var sum1 = 0f
            for (i in 0 until taps) {
                val x = buf[base + i]
                sum0 += x * table[row0 + i]
                sum1 += x * table[row1 + i]
            }
            return sum0 + (sum1 - sum0) * alpha
        }

        companion object {
            private const val BASE_WINGS = 24 // 48 taps when upsampling
            private const val PHASES = 512
            private const val KAISER_BETA = 8.6
            private const val CUTOFF_PROPORTION = 0.916 // 0.458 of Nyquist
            private val cache = ConcurrentHashMap<Long, PolyphaseSincKernel>()

            fun forRates(inRate: Int, outRate: Int): PolyphaseSincKernel {
                val g = gcd(inRate, outRate).coerceAtLeast(1)
                val key = ((inRate / g).toLong() shl 32) or ((outRate / g).toLong() and 0xffffffffL)
                val cached = cache[key]
                if (cached != null && cached.inRate == inRate && cached.outRate == outRate) return cached
                return build(inRate, outRate).also {
                    if (cache.size > 16) cache.clear()
                    cache[key] = it
                }
            }

            private fun build(inRate: Int, outRate: Int): PolyphaseSincKernel {
                val ratio = outRate.toDouble() / inRate.toDouble()
                // Scale wings when downsampling so the anti-aliasing transition band stays sharp.
                val scale = if (ratio < 1.0) (1.0 / ratio).coerceAtMost(8.0) else 1.0
                val wings = ceil(BASE_WINGS * scale).toInt().coerceAtLeast(BASE_WINGS)
                val taps = wings * 2
                // Normalized cutoff relative to inRate (0.5 = input Nyquist).
                val fc = if (ratio < 1.0) {
                    0.5 * CUTOFF_PROPORTION * ratio
                } else {
                    0.5 * CUTOFF_PROPORTION
                }
                val cutoffHz = fc * inRate
                val invI0Beta = 1.0 / besselI0(KAISER_BETA)
                val table = FloatArray((PHASES + 1) * taps)
                val centerOffset = wings - 1

                for (p in 0..PHASES) {
                    val f = p.toDouble() / PHASES.toDouble()
                    var rowSum = 0.0
                    val rowStart = p * taps
                    for (i in 0 until taps) {
                        // Distance in input samples from the interpolated point (centerOffset + f).
                        val delta = (i - centerOffset) - f
                        val norm = (delta / wings).coerceIn(-1.0, 1.0)
                        val w = besselI0(KAISER_BETA * sqrt((1.0 - norm * norm).coerceAtLeast(0.0))) * invI0Beta
                        val x = 2.0 * fc * delta
                        val s = if (kotlin.math.abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                        val coeff = 2.0 * fc * s * w
                        table[rowStart + i] = coeff.toFloat()
                        rowSum += coeff
                    }
                    if (kotlin.math.abs(rowSum) > 1e-9) {
                        val invSum = (1.0 / rowSum).toFloat()
                        for (i in 0 until taps) {
                            table[rowStart + i] *= invSum
                        }
                    }
                }
                return PolyphaseSincKernel(inRate, outRate, taps, cutoffHz, table)
            }

            private fun besselI0(x: Double): Double {
                var sum = 1.0
                var term = 1.0
                val halfX = x * 0.5
                for (k in 1..25) {
                    val r = halfX / k
                    term *= r * r
                    sum += term
                    if (term < sum * 1e-12) break
                }
                return sum
            }

            private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        }
    }
}
