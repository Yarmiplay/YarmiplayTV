package com.yarmiplaytv.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Target PCM for Opus VoIP: 48 kHz mono/stereo 16-bit little-endian. */
object VoicePcm {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val STEREO_CHANNELS = 2
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

    /** Downmix interleaved stereo PCM16 samples into mono ((L + R) / 2). */
    fun stereoToMono(stereo: ShortArray, offset: Int = 0, frames: Int = (stereo.size - offset) / 2): ShortArray {
        if (frames <= 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            val idx = offset + i * 2
            val l = stereo[idx].toInt()
            val r = stereo[minOf(idx + 1, stereo.lastIndex)].toInt()
            out[i] = ((l + r) / 2).toShort()
        }
        return out
    }

    /** Accumulates PCM until at least [frameSamples] shorts are available; returns whole frames. */
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
        return sqrt(sum / pcm.size)
    }

    fun rmsDbFs(pcm: ShortArray): Double {
        val level = rms(pcm)
        if (level <= 0.0) return -100.0
        return (20.0 * log10(level / 32768.0)).coerceIn(-100.0, 6.0)
    }

    /**
     * Fixed Discord-capture makeup, +12.04 dB, used only when no AGC stage is active.
     *
     * Desktop Java Sound has no WebRTC or platform AGC, so normal speech arrives around
     * −30 dBFS RMS — about 12 dB below Discord's comfortable level near −18 LUFS.
     * This is a constant scale, not an automatic gain control. Android
     * `AutomaticGainControl` and desktop WebRTC AGC (the 3.0.0 chain) are the single
     * AGC stage: callers pass [discordMakeupGain] `agcStageActive = true` and get unity
     * so the two never stack.
     */
    const val FIXED_CAPTURE_MAKEUP = 4.0f

    fun discordMakeupGain(agcStageActive: Boolean): Float =
        if (agcStageActive) 1f else FIXED_CAPTURE_MAKEUP

    /** Linear PCM16 scale. Gain `1` is a copy. Values outside int16 saturate. */
    fun scale(pcm: ShortArray, gain: Float): ShortArray {
        if (pcm.isEmpty() || gain == 1f) return pcm.copyOf()
        if (gain <= 0f) return ShortArray(pcm.size)
        return ShortArray(pcm.size) { i ->
            (pcm[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /**
     * Deterministic voiced signal (harmonic series, ~15 dB crest) at [rmsDbFs].
     * Used to measure capture level before and after the makeup gain.
     */
    fun speechLike(
        rate: Int = SAMPLE_RATE,
        frames: Int = 50,
        rmsDbFs: Double = -30.0,
        fundamentalHz: Double = 140.0,
    ): ShortArray {
        val n = rate / 50 * frames
        if (n <= 0) return ShortArray(0)
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val syllable = 0.48 + 0.52 * (0.5 - 0.5 * cos(2.0 * PI * 4.0 * t))
            var glottal = 0.0
            for (k in 1..10) {
                val weight = 1.0 / (1.0 + 0.14 * (k - 1))
                glottal += weight * cos(2.0 * PI * (fundamentalHz * k) * t)
            }
            raw[i] = glottal * syllable
        }
        var sumSq = 0.0
        for (v in raw) sumSq += v * v
        val rawRms = sqrt(sumSq / n).coerceAtLeast(1e-9)
        val targetRms = 32768.0 * 10.0.pow(rmsDbFs / 20.0)
        val scale = targetRms / rawRms
        return ShortArray(n) { i ->
            (raw[i] * scale).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt() + 1, Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /**
     * ITU-R BS.1770 K-weighted loudness for 48 kHz mono PCM16, without the absolute
     * gate. Short steady signals are what the level tests compare.
     */
    fun lufs(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return -100.0
        val b10 = 1.53512485958697
        val b11 = -2.69169618940638
        val b12 = 1.19839281085285
        val a11 = -1.69065929318241
        val a12 = 0.73248077421585
        val b20 = 1.0
        val b21 = -2.0
        val b22 = 1.0
        val a21 = -1.99004745483398
        val a22 = 0.99007225036621
        var x11 = 0.0
        var x12 = 0.0
        var y11 = 0.0
        var y12 = 0.0
        var x21 = 0.0
        var x22 = 0.0
        var y21 = 0.0
        var y22 = 0.0
        var sumSq = 0.0
        for (s in pcm) {
            val x0 = s / 32768.0
            val y1 = b10 * x0 + b11 * x11 + b12 * x12 - a11 * y11 - a12 * y12
            x12 = x11
            x11 = x0
            y12 = y11
            y11 = y1
            val y2 = b20 * y1 + b21 * x21 + b22 * x22 - a21 * y21 - a22 * y22
            x22 = x21
            x21 = y1
            y22 = y21
            y21 = y2
            sumSq += y2 * y2
        }
        val meanSq = sumSq / pcm.size
        if (meanSq <= 1e-12) return -100.0
        return (-0.691 + 10.0 * log10(meanSq)).coerceIn(-100.0, 6.0)
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
