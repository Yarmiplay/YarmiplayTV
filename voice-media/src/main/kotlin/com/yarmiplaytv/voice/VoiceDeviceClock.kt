package com.yarmiplaytv.voice

/**
 * Which sample rate to open on a capture or playback device, and how big a low-latency
 * buffer to ask for.
 *
 * Opening the hardware at its native rate means the platform mixer does not resample, so the
 * app's one [StreamingResampler] is the only rate conversion. A device already at 48 kHz is
 * left alone (zero app resamples).
 */
object VoiceDeviceClock {
    /** Rates the voice engines know how to open. Anything else still in range is accepted too. */
    val KNOWN_RATES: IntArray = intArrayOf(48_000, 44_100, 96_000, 88_200, 32_000, 24_000, 22_050, 16_000)

    /**
     * Rate to request from the device. [nativeRate] is the HAL / mix rate (Android
     * `PROPERTY_OUTPUT_SAMPLE_RATE`, WASAPI mix format, CoreAudio nominal rate).
     * Rates under 16 kHz stay on the Opus clock so playback is not crushed to narrowband.
     */
    fun openRate(nativeRate: Int, opusRate: Int = VoicePcm.SAMPLE_RATE): Int {
        if (nativeRate == opusRate) return opusRate
        if (nativeRate in 16_000..192_000) return nativeRate
        return opusRate
    }

    /** App-side resamples when the device is opened at [openedRate]. Never more than one. */
    fun appResampleStages(openedRate: Int, opusRate: Int = VoicePcm.SAMPLE_RATE): Int =
        if (openedRate == opusRate) 0 else 1

    /**
     * Byte size for a mono PCM16 stream. Prefers two 20 ms frames, or two device bursts when
     * that is larger, and never asks for more than four frames above the driver minimum.
     * [minBytes] is the platform minimum (`AudioTrack.getMinBufferSize` / line minimum).
     */
    fun lowLatencyBufferBytes(minBytes: Int, frameBytes: Int, burstFrames: Int = 0): Int {
        val frame = frameBytes.coerceAtLeast(2)
        val twoFrames = frame * 2
        val fourFrames = frame * 4
        val twoBursts = if (burstFrames > 0) (burstFrames * 2 * 2).coerceAtMost(fourFrames) else 0
        val wanted = maxOf(twoFrames, twoBursts)
        return maxOf(minBytes, wanted)
    }
}
