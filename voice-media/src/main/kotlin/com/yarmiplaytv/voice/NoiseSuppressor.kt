package com.yarmiplaytv.voice

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Capture-side noise suppression + voice-activity gate.
 *
 * Frame size matches Xiph RNNoise (480 samples @ 48 kHz = 10 ms). Speech is estimated with a
 * spectral floor tracker and Wiener-like gains; frames below the VAD threshold are zeroed so
 * room noise is not encoded/sent. On Android the engine also enables the platform WebRTC
 * [android.media.audiofx.NoiseSuppressor], [AcousticEchoCanceler] and [AutomaticGainControl]
 * on the capture session when available.
 *
 * Attribution: 480-sample framing follows RNNoise (BSD-3, https://gitlab.xiph.org/xiph/rnnoise).
 * This spectral VAD/NS is an original implementation sized for the Opus 20 ms pipeline.
 */
class VoiceNoiseSuppressor {
    companion object {
        /** RNNoise frame length at 48 kHz. */
        const val FRAME = 480
        private const val BINS = 48
    }

    private var pending = ShortArray(FRAME * 4)
    private var pendingLen = 0
    private val noisePow = DoubleArray(BINS) { 1e-6 }
    private var noiseInited = false
    private var speechRun = 0
    private var enabled = true
    /** Last VAD decision (true = speech / hangover). */
    var lastSpeech = true
        private set

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            pendingLen = 0
            lastSpeech = true
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Push any amount of 48 kHz mono PCM; returns processed samples for whole RNNoise frames
     * consumed. When disabled, returns a copy of [input].
     */
    fun process(input: ShortArray): ShortArray {
        if (!enabled) return input.copyOf()
        if (input.isEmpty()) return ShortArray(0)
        ensure(pendingLen + input.size)
        System.arraycopy(input, 0, pending, pendingLen, input.size)
        pendingLen += input.size
        val out = ArrayList<Short>(pendingLen)
        var offset = 0
        while (pendingLen - offset >= FRAME) {
            val frame = pending.copyOfRange(offset, offset + FRAME)
            val processed = processFrame(frame)
            for (s in processed) out += s
            offset += FRAME
        }
        if (offset > 0) {
            val remain = pendingLen - offset
            System.arraycopy(pending, offset, pending, 0, remain)
            pendingLen = remain
        }
        return out.toShortArray()
    }

    fun reset() {
        pendingLen = 0
        noisePow.fill(1e-6)
        noiseInited = false
        speechRun = 0
        lastSpeech = true
    }

    private fun processFrame(frame: ShortArray): ShortArray {
        val windowed = DoubleArray(FRAME) { i ->
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (FRAME - 1))
            frame[i] / 32768.0 * w
        }
        val mag = bandMags(windowed)
        val energy = mag.average()
        if (!noiseInited) {
            for (i in 0 until BINS) noisePow[i] = max(mag[i], 1e-8)
            noiseInited = true
        }
        val noiseMean = noisePow.average().coerceAtLeast(1e-12)
        val snr = energy / noiseMean
        val speech = snr > 2.8 || (speechRun > 0 && snr > 1.5)
        if (speech) {
            speechRun = min(10, speechRun + 1)
            for (i in 0 until BINS) noisePow[i] = 0.998 * noisePow[i] + 0.002 * mag[i]
        } else {
            speechRun = max(0, speechRun - 1)
            for (i in 0 until BINS) noisePow[i] = 0.92 * noisePow[i] + 0.08 * mag[i]
        }
        lastSpeech = speechRun > 0
        if (!lastSpeech) return ShortArray(FRAME)

        val gains = DoubleArray(BINS) { i ->
            val n = noisePow[i]
            val s = mag[i]
            ((s - 1.15 * n) / max(s, 1e-12)).coerceIn(0.08, 1.0)
        }
        val out = ShortArray(FRAME)
        val hop = FRAME / BINS
        for (i in 0 until FRAME) {
            val b = (i / hop).coerceIn(0, BINS - 1)
            out[i] = (frame[i] * gains[b]).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        if (speechRun in 1..2) {
            for (i in 0 until 48) {
                val f = (i + 1) / 48.0
                out[i] = (out[i] * f).toInt().toShort()
            }
        }
        return out
    }

    private fun bandMags(x: DoubleArray): DoubleArray {
        val out = DoubleArray(BINS)
        val hop = FRAME / BINS
        for (b in 0 until BINS) {
            var sum = 0.0
            val start = b * hop
            val end = min(FRAME, start + hop * 2)
            for (i in start until end) sum += x[i] * x[i]
            out[b] = sum / (end - start).coerceAtLeast(1)
        }
        return out
    }

    private fun ensure(need: Int) {
        if (need <= pending.size) return
        var n = pending.size
        while (n < need) n *= 2
        pending = pending.copyOf(n)
    }
}
