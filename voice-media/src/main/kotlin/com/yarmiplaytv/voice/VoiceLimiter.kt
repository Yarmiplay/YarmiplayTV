package com.yarmiplaytv.voice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Float participant summer and single final look-ahead soft limiter (~-1.0 dBFS ceiling).
 *
 * - Sums all active participants in 32-bit float without dividing by participant count, so a single
 *   talker plays at full loudness while overlapping talkers combine naturally.
 * - Applies a single look-ahead envelope gain stage (2 ms look-ahead, 10 ms hold, 80 ms release so
 *   gain never pumps across speech pitch periods) followed by a C¹-continuous tanh soft knee capped
 *   at [CEILING_DBFS] (-1.0 dBFS ≈ 29204 in PCM16).
 */
class VoiceLimiter(
    private val sampleRate: Int = VoicePcm.SAMPLE_RATE,
    val lookAheadSamples: Int = (sampleRate * 2) / 1000, // 2 ms (96 samples @ 48 kHz)
    val ceilingDbFs: Double = CEILING_DBFS,
) {
    val ceilingLinear: Float = 10.0.pow(ceilingDbFs / 20.0).toFloat()
    val ceilingPcm16: Int = (32767.0 * ceilingLinear).roundToInt()
    private val kneeLinear: Float = ceilingLinear * 0.86f
    private val attackTargetCeiling: Float = ceilingLinear * 0.94f
    private val attackCoeff: Float =
        1.0f - exp(-2.5 / lookAheadSamples.coerceAtLeast(1).toDouble()).toFloat()
    private val releaseCoeff: Float =
        1.0f - exp(-1.0 / (sampleRate * 0.080).coerceAtLeast(1.0)).toFloat()
    private val holdSamplesMax: Int = (sampleRate * 10) / 1000 // 10 ms pitch-period hold

    private var envGain: Float = 1.0f
    private var holdCounter: Int = 0

    fun reset() {
        envGain = 1.0f
        holdCounter = 0
    }

    /**
     * Sum [tracks] (each paired with its linear gain) in float without dividing by count,
     * then apply this single look-ahead soft limiter and convert to PCM16.
     */
    fun mixAndLimit(tracks: List<Pair<ShortArray, Float>>, frameSamples: Int = VoicePcm.frameSamples): ShortArray {
        val mix = FloatArray(frameSamples)
        for ((pcm, gain) in tracks) {
            if (gain <= 0f || pcm.isEmpty()) continue
            val n = min(frameSamples, pcm.size)
            val scale = gain / 32768.0f
            for (i in 0 until n) {
                mix[i] += pcm[i] * scale
            }
        }
        return limitToPcm16(mix, frameSamples)
    }

    /**
     * Accumulate one participant's [pcm] into [floatMix] with linear [gain] (no 1/N division).
     */
    fun accumulateFloat(floatMix: FloatArray, pcm: ShortArray, gain: Float) {
        if (gain <= 0f || pcm.isEmpty()) return
        val n = min(floatMix.size, pcm.size)
        val scale = gain / 32768.0f
        for (i in 0 until n) {
            floatMix[i] += pcm[i] * scale
        }
    }

    /**
     * Apply the look-ahead soft limiter in-place on [floatMix] (normalized `-1..1` per talker)
     * and write the limited PCM16 samples into [out].
     */
    fun limitToPcm16(floatMix: FloatArray, count: Int = floatMix.size, out: ShortArray = ShortArray(count)): ShortArray {
        val n = min(count, min(floatMix.size, out.size))
        for (i in 0 until n) {
            // Find peak in the look-ahead window [i .. i + lookAheadSamples].
            val end = min(n - 1, i + lookAheadSamples)
            var aheadPeak = 0f
            for (j in i..end) {
                val a = abs(floatMix[j])
                if (a > aheadPeak) aheadPeak = a
            }
            val neededGain = if (aheadPeak > attackTargetCeiling) {
                attackTargetCeiling / aheadPeak
            } else {
                1.0f
            }

            if (neededGain < envGain) {
                envGain += (neededGain - envGain) * attackCoeff
                // Guard against un-ramped first-sample transient exceeding the soft knee input range.
                val instAbs = abs(floatMix[i])
                if (instAbs * envGain > ceilingLinear) {
                    envGain = ceilingLinear / instAbs
                }
                holdCounter = holdSamplesMax
            } else if (holdCounter > 0) {
                holdCounter--
            } else {
                envGain += (neededGain - envGain) * releaseCoeff
            }

            val gained = floatMix[i] * envGain
            val limited = softClip(gained)
            val pcm = (limited * 32767.0f).roundToInt().coerceIn(-ceilingPcm16, ceilingPcm16)
            out[i] = pcm.toShort()
        }
        return out
    }

    private fun softClip(x: Float): Float {
        val ax = abs(x)
        if (ax <= kneeLinear) return x
        val span = (ceilingLinear - kneeLinear).coerceAtLeast(1e-5f)
        val excess = (ax - kneeLinear) / span
        val compressed = kneeLinear + span * tanh(excess.toDouble()).toFloat()
        val clamped = min(compressed, ceilingLinear)
        return if (x >= 0f) clamped else -clamped
    }

    companion object {
        const val CEILING_DBFS: Double = -1.0
    }
}
