package com.yarmiplaytv.voice

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Target PCM for Opus VoIP: 48 kHz mono 16-bit little-endian. */
object VoicePcm {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    val frameSamples: Int get() = SAMPLE_RATE / (1000 / FRAME_MS) // 960

    /**
     * Linear-interpolation resampler from [inRate] Hz mono PCM16 → [outRate] Hz.
     * Used when Android [AudioRecord.getSampleRate] differs from the Opus clock (common: 44.1 kHz
     * or 16 kHz VOICE_COMMUNICATION hardware vs 48 kHz Opus) — encoding the wrong rate as 48 kHz
     * produces garbled/pitch-shifted audio on every peer.
     */
    fun resampleMono(input: ShortArray, inRate: Int, outRate: Int = SAMPLE_RATE): ShortArray {
        if (inRate <= 0 || outRate <= 0) return ShortArray(0)
        if (inRate == outRate) return input.copyOf()
        if (input.isEmpty()) return ShortArray(0)
        val outLen = maxOf(1, (input.size.toLong() * outRate / inRate).toInt())
        val out = ShortArray(outLen)
        val step = inRate.toDouble() / outRate
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt().coerceIn(0, input.lastIndex)
            val frac = pos - idx
            val a = input[idx].toInt()
            val b = input[minOf(idx + 1, input.lastIndex)].toInt()
            out[i] = (a + (b - a) * frac).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pos += step
        }
        return out
    }

    /** Accumulates mono PCM until at least [frameSamples] are available; returns whole frames. */
    class FrameAccumulator(private val frameSamples: Int = VoicePcm.frameSamples) {
        private var buf = ShortArray(frameSamples * 2)
        private var filled = 0

        fun push(samples: ShortArray): List<ShortArray> {
            if (samples.isEmpty()) return emptyList()
            ensure(filled + samples.size)
            System.arraycopy(samples, 0, buf, filled, samples.size)
            filled += samples.size
            val frames = ArrayList<ShortArray>()
            var offset = 0
            while (filled - offset >= frameSamples) {
                frames += buf.copyOfRange(offset, offset + frameSamples)
                offset += frameSamples
            }
            if (offset > 0) {
                val remain = filled - offset
                System.arraycopy(buf, offset, buf, 0, remain)
                filled = remain
            }
            return frames
        }

        fun clear() {
            filled = 0
        }

        private fun ensure(need: Int) {
            if (need <= buf.size) return
            var n = buf.size
            while (n < need) n *= 2
            buf = buf.copyOf(n)
        }
    }

    /** Unit-test helper: [hz] sine at [rate] for [frames] Opus frames (20 ms each). */
    fun sine(
        hz: Double = 440.0,
        rate: Int = SAMPLE_RATE,
        frames: Int = 50,
        amplitude: Double = 0.4,
    ): ShortArray {
        val n = rate / 50 * frames
        val out = ShortArray(n)
        val peak = (Short.MAX_VALUE * amplitude).roundToInt()
        for (i in 0 until n) {
            out[i] = (sin(2.0 * PI * hz * i / rate) * peak)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    /** Dominant frequency estimate via zero-crossing (enough for a 440 Hz sine check). */
    fun estimateHz(pcm: ShortArray, rate: Int = SAMPLE_RATE): Double {
        if (pcm.size < 4) return 0.0
        var crossings = 0
        for (i in 1 until pcm.size) {
            if ((pcm[i - 1] < 0 && pcm[i] >= 0) || (pcm[i - 1] >= 0 && pcm[i] < 0)) crossings++
        }
        val seconds = pcm.size.toDouble() / rate
        return (crossings / 2.0) / seconds
    }

    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(sum / pcm.size)
    }
}

/** Capture-side format actually opened (may differ from the Opus clock). */
data class VoiceCaptureFormat(
    val sampleRate: Int,
    val channels: Int,
    val pcm16: Boolean,
    val audioSource: String,
    val deviceLabel: String,
) {
    val bytesPerFrame48k: Int get() = VoicePcm.frameSamples * channels * 2
    override fun toString(): String =
        "rate=$sampleRate ch=$channels pcm16=$pcm16 source=$audioSource device=$deviceLabel"
}
