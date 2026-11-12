package com.yarmiplaytv.voice

import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceOnsetMeasurementTest {

    /**
     * Generates a test signal consisting of:
     * - silenceMs of silence (or low background noise floor at ~ -60 dBFS)
     * - onsetMs of soft consonant (e.g. 4 kHz hiss / burst at ~ -30 dBFS)
     * - vowelMs of voiced tone (e.g. 250 Hz harmonic tone at ~ -16 dBFS)
     */
    private fun generateWordSignal(
        silenceMs: Int = 300,
        onsetMs: Int = 40,
        vowelMs: Int = 200,
    ): ShortArray {
        val totalMs = silenceMs + onsetMs + vowelMs
        val totalSamples = totalMs * 48 // 48 samples per ms at 48 kHz
        val pcm = ShortArray(totalSamples)

        val silenceSamples = silenceMs * 48
        val onsetSamples = onsetMs * 48

        // Silence / background noise floor: quiet noise (< 10 amplitude ~ -70 dBFS)
        for (i in 0 until silenceSamples) {
            pcm[i] = (((i * 1103515245 + 12345) and 0x7FFF) % 7 - 3).toShort()
        }

        // Soft consonant onset (~ -28 dBFS: amplitude ~ 1300)
        for (i in 0 until onsetSamples) {
            val idx = silenceSamples + i
            val noise = (((idx * 1103515245 + 12345) and 0x7FFF) % 2500 - 1250)
            val highFreq = (sin(2.0 * Math.PI * 3500.0 * idx / 48_000) * 800).toInt()
            pcm[idx] = (noise + highFreq).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Voiced vowel (140 Hz pitch harmonic stack with syllable rate AM ~ -16 dBFS)
        val vowelSamples = vowelMs * 48
        val weights = doubleArrayOf(1.0, 0.55, 0.35, 0.2, 0.1)
        for (i in 0 until vowelSamples) {
            val idx = silenceSamples + onsetSamples + i
            val t = i / 48_000.0
            val am = 0.7 + 0.3 * sin(2.0 * Math.PI * 5.0 * t)
            var y = 0.0
            for (h in weights.indices) {
                y += weights[h] * sin(2.0 * Math.PI * 140.0 * (h + 1) * t)
            }
            y = (y / 2.2) * 0.45 * am
            pcm[idx] = (y * 32767.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return pcm
    }

    private fun rms(samples: ShortArray, offset: Int, length: Int): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + length) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / length)
    }

    @Test
    fun measureDeepFilterNetWordOnset() {
        val processor = DeepFilterNetProcessor()
        if (!processor.isAvailable) {
            println("DeepFilterNet unavailable in this environment, skipping measurement")
            return
        }

        val testSignal = generateWordSignal(silenceMs = 300, onsetMs = 40, vowelMs = 200)
        val processed = ShortArray(testSignal.size)

        // Process in 20 ms (960 sample) frames
        var offset = 0
        var fIndex = 0
        while (offset + 960 <= testSignal.size) {
            val frame = testSignal.copyOfRange(offset, offset + 960)
            val out = processor.processFrame20ms(frame, channels = 1)
            val tMs = fIndex * 20
            if (tMs in 280..420) {
                println("FRAME t=${tMs}ms inRMS=%.2f outRMS=%.2f LSNR=%.2f".format(VoicePcm.rms(frame), VoicePcm.rms(out), processor.lastLocalSnrDb))
            }
            System.arraycopy(out, 0, processed, offset, 960)
            offset += 960
            fIndex++
        }

        val silenceOffset = 0
        val onsetOffset = 300 * 48
        val vowelOffset = 340 * 48

        val inSilenceRms = rms(testSignal, silenceOffset, 300 * 48)
        val outSilenceRms = rms(processed, silenceOffset, 300 * 48)

        val inOnsetRms1 = rms(testSignal, onsetOffset, 20 * 48)
        val outOnsetRms1 = rms(processed, onsetOffset, 20 * 48)

        val inOnsetRms2 = rms(testSignal, onsetOffset + 20 * 48, 20 * 48)
        val outOnsetRms2 = rms(processed, onsetOffset + 20 * 48, 20 * 48)

        val inVowelRms1 = rms(testSignal, vowelOffset, 40 * 48)
        val outVowelRms1 = rms(processed, vowelOffset, 40 * 48)

        val inVowelRms2 = rms(testSignal, vowelOffset + 40 * 48, 40 * 48)
        val outVowelRms2 = rms(processed, vowelOffset + 40 * 48, 40 * 48)

        val inVowelRms3 = rms(testSignal, vowelOffset + 80 * 48, 40 * 48)
        val outVowelRms3 = rms(processed, vowelOffset + 80 * 48, 40 * 48)

        println("=== DeepFilterNet Word Onset Measurement ===")
        println("Silence (0-300ms): inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inSilenceRms, outSilenceRms, outSilenceRms / inSilenceRms))
        println("Onset 0-20ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms1, outOnsetRms1, outOnsetRms1 / inOnsetRms1))
        println("Onset 20-40ms:    inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms2, outOnsetRms2, outOnsetRms2 / inOnsetRms2))
        println("Vowel 0-40ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inVowelRms1, outVowelRms1, outVowelRms1 / inVowelRms1))
        println("Vowel 40-80ms:    inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inVowelRms2, outVowelRms2, outVowelRms2 / inVowelRms2))
        println("Vowel 80-120ms:   inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inVowelRms3, outVowelRms3, outVowelRms3 / inVowelRms3))

        processor.close()
    }

    @Test
    fun measureWebRtcApmWordOnset() {
        val apm = DesktopWebRtcApm(echoCancellation = true, autoGainControl = true, noiseSuppression = true)
        if (!apm.isAvailable) {
            println("DesktopWebRtcApm unavailable in this environment, skipping measurement")
            return
        }

        val testSignal = generateWordSignal(silenceMs = 300, onsetMs = 40, vowelMs = 200)
        val processed = ShortArray(testSignal.size)

        var offset = 0
        while (offset + 960 <= testSignal.size) {
            val frame = testSignal.copyOfRange(offset, offset + 960)
            val out = apm.processCaptureFrame20ms(frame, channels = 1)
            System.arraycopy(out, 0, processed, offset, 960)
            offset += 960
        }

        val onsetOffset = 300 * 48
        val vowelOffset = 340 * 48

        val inOnsetRms1 = rms(testSignal, onsetOffset, 20 * 48)
        val outOnsetRms1 = rms(processed, onsetOffset, 20 * 48)

        val inOnsetRms2 = rms(testSignal, onsetOffset + 20 * 48, 20 * 48)
        val outOnsetRms2 = rms(processed, onsetOffset + 20 * 48, 20 * 48)

        val inVowelRms = rms(testSignal, vowelOffset, 40 * 48)
        val outVowelRms = rms(processed, vowelOffset, 40 * 48)

        println("=== WebRTC APM Word Onset Measurement ===")
        println("Onset 0-20ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms1, outOnsetRms1, outOnsetRms1 / inOnsetRms1))
        println("Onset 20-40ms:    inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms2, outOnsetRms2, outOnsetRms2 / inOnsetRms2))
        println("Vowel 0-40ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inVowelRms, outVowelRms, outVowelRms / inVowelRms))

        apm.close()
    }

    @Test
    fun measureVoiceNoiseSuppressorWordOnset() {
        val ns = VoiceNoiseSuppressor()
        val testSignal = generateWordSignal(silenceMs = 300, onsetMs = 40, vowelMs = 200)
        val processed = ns.process(testSignal)

        val onsetOffset = 300 * 48
        val vowelOffset = 340 * 48

        val inOnsetRms1 = rms(testSignal, onsetOffset, 20 * 48)
        val outOnsetRms1 = rms(processed, onsetOffset, 20 * 48)

        val inOnsetRms2 = rms(testSignal, onsetOffset + 20 * 48, 20 * 48)
        val outOnsetRms2 = rms(processed, onsetOffset + 20 * 48, 20 * 48)

        val inVowelRms = rms(testSignal, vowelOffset, 40 * 48)
        val outVowelRms = rms(processed, vowelOffset, 40 * 48)

        println("=== VoiceNoiseSuppressor Word Onset Measurement ===")
        println("Onset 0-20ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms1, outOnsetRms1, outOnsetRms1 / inOnsetRms1))
        println("Onset 20-40ms:    inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inOnsetRms2, outOnsetRms2, outOnsetRms2 / inOnsetRms2))
        println("Vowel 0-40ms:     inRMS=%.2f outRMS=%.2f ratio=%.3f".format(inVowelRms, outVowelRms, outVowelRms / inVowelRms))
    }
}
