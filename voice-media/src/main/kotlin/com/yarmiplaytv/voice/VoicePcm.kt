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
     * Band-limited polyphase Kaiser-windowed sinc resampler from [inRate] Hz mono PCM16 → [outRate] Hz.
     * Used when hardware capture/playback rate differs from the 48 kHz Opus clock; avoids linear or
     * nearest interpolation so passband stays flat and stopband/aliasing is rejected by >80 dB.
     */
    fun resampleMono(input: ShortArray, inRate: Int, outRate: Int = SAMPLE_RATE): ShortArray {
        if (inRate <= 0 || outRate <= 0) return ShortArray(0)
        if (inRate == outRate) return input.copyOf()
        if (input.isEmpty()) return ShortArray(0)
        return StreamingResampler(inRate, outRate).push(input)
    }

    /** Interleave one mono frame into stereo (L = R) for a stereo Opus encoder. */
    fun monoToStereo(pcm: ShortArray, offset: Int = 0, frameSamples: Int = this.frameSamples): ShortArray {
        val n = frameSamples.coerceAtMost(pcm.size - offset).coerceAtLeast(0)
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val s = pcm[offset + i]
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
        return out
    }

    /** Average interleaved PCM16 channels down to mono. One channel is copied. */
    fun interleavedPcm16ToMono(input: ShortArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        if (ch == 1) return input.copyOf()
        val frames = input.size / ch
        val out = ShortArray(frames)
        var i = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) sum += input[i + c].toInt()
            out[f] = (sum / ch).toShort()
            i += ch
        }
        return out
    }

    /** Average interleaved float channels (range about −1..1) down to mono PCM16. */
    fun interleavedFloatToMono(input: FloatArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = input.size / ch
        val out = ShortArray(frames)
        var i = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += input[i + c]
            val mono = sum / ch
            out[f] = (mono * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            i += ch
        }
        return out
    }

    /** Expand mono PCM16 into interleaved float channels in −1..1. */
    fun monoToInterleavedFloat(mono: ShortArray, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val out = FloatArray(mono.size * ch)
        for (i in mono.indices) {
            val v = mono[i] / 32768f
            for (c in 0 until ch) out[i * ch + c] = v
        }
        return out
    }

    /** Expand mono PCM16 into interleaved PCM16 channels. */
    fun monoToInterleavedPcm16(mono: ShortArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        if (ch == 1) return mono.copyOf()
        val out = ShortArray(mono.size * ch)
        for (i in mono.indices) {
            val s = mono[i]
            for (c in 0 until ch) out[i * ch + c] = s
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
