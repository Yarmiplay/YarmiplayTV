package com.yarmiplaytv.voice

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig

/**
 * Desktop WebRTC AudioProcessing (APM) wrapper providing acoustic echo cancellation (AEC3),
 * automatic gain control (AGC1/AGC2 digital adaptive + limiter), and WebRTC noise suppression.
 *
 * Uses the desktop playback mix ([processReverseFrame20ms]) as the far-end AEC reference stream
 * and processes 20 ms capture frames ([processCaptureFrame20ms]) in two 10 ms (480-sample/ch) chunks
 * at 48 kHz.
 *
 * HARD RULE: one AEC (AEC3), one AGC (AGC2 adaptive digital; AGC1 and its limiter stay off), and
 * at most one noise suppressor. AGC2's capture limiter cannot be switched off separately in
 * webrtc-java; the playback mix uses [VoiceLimiter] once. When AI noise suppression
 * (DeepFilterNet) is active, [noiseSuppression] MUST be passed as `false`.
 */
class DesktopWebRtcApm(
    echoCancellation: Boolean = true,
    autoGainControl: Boolean = true,
    noiseSuppression: Boolean = true,
    private val sampleRate: Int = VoicePcm.SAMPLE_RATE,
    private val defaultStreamDelayMs: Int = DEFAULT_STREAM_DELAY_MS,
) : AutoCloseable {
    private val lock = Any()
    private var apm: AudioProcessing? = null
    private var closed = false

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var echoCancellationEnabled: Boolean = echoCancellation
        private set

    @Volatile
    var autoGainControlEnabled: Boolean = autoGainControl
        private set

    @Volatile
    var noiseSuppressionEnabled: Boolean = noiseSuppression
        private set

    @Volatile
    var reverseFramesProcessed: Long = 0L
        private set

    @Volatile
    var captureFramesProcessed: Long = 0L
        private set

    private var appliedAec: Boolean? = null
    private var appliedAgc: Boolean? = null
    private var appliedNs: Boolean? = null

    private val monoConfig = AudioProcessingStreamConfig(sampleRate, 1)
    private val stereoConfig = AudioProcessingStreamConfig(sampleRate, 2)
    private val subFrameSamplesPerChannel = sampleRate / 100 // 480 at 48 kHz (10 ms)
    private val monoSubBufIn = ByteArray(subFrameSamplesPerChannel * 2)
    private val monoSubBufOut = ByteArray(subFrameSamplesPerChannel * 2)
    private val stereoSubBufIn = ByteArray(subFrameSamplesPerChannel * 4)
    private val stereoSubBufOut = ByteArray(subFrameSamplesPerChannel * 4)

    init {
        synchronized(lock) {
            try {
                val instance = AudioProcessing()
                apm = instance
                isAvailable = true
                applyConfigLocked(instance, echoCancellation, autoGainControl, noiseSuppression)
            } catch (_: Throwable) {
                apm = null
                isAvailable = false
            }
        }
    }

    fun configure(
        echoCancellation: Boolean,
        autoGainControl: Boolean,
        noiseSuppression: Boolean,
    ) {
        synchronized(lock) {
            echoCancellationEnabled = echoCancellation
            autoGainControlEnabled = autoGainControl
            noiseSuppressionEnabled = noiseSuppression
            val instance = apm ?: return
            if (closed) return
            if (appliedAec == echoCancellation && appliedAgc == autoGainControl && appliedNs == noiseSuppression) {
                return
            }
            applyConfigLocked(instance, echoCancellation, autoGainControl, noiseSuppression)
        }
    }

    private fun applyConfigLocked(
        instance: AudioProcessing,
        aec: Boolean,
        agc: Boolean,
        ns: Boolean,
    ) {
        try {
            val cfg = AudioProcessingConfig()
            cfg.highPassFilter.enabled = aec || ns
            cfg.highPassFilter.applyInFullBand = true

            cfg.echoCanceller.enabled = aec
            cfg.echoCanceller.enforceHighPassFiltering = true

            // AGC1 (legacy) stays off, including its limiter. AGC2 adaptive digital is the single
            // capture AGC. The playback mix limiter is VoiceLimiter, not a second AGC.
            cfg.gainController.enabled = false
            cfg.gainController.enableLimiter = false
            cfg.gainController.analogGainController.enabled = false

            cfg.gainControllerDigital.enabled = agc
            cfg.gainControllerDigital.fixedDigital.gainDb = 0f
            cfg.gainControllerDigital.adaptiveDigital.enabled = agc
            if (agc) {
                cfg.gainControllerDigital.adaptiveDigital.headroomDb = 5f
                cfg.gainControllerDigital.adaptiveDigital.maxGainDb = 30f
                cfg.gainControllerDigital.adaptiveDigital.initialGainDb = 8f
                cfg.gainControllerDigital.adaptiveDigital.maxGainChangeDbPerSecond = 3f
                cfg.gainControllerDigital.adaptiveDigital.maxOutputNoiseLevelDbfs = -50f
            }

            cfg.noiseSuppression.enabled = ns
            // Moderate is WebRTC's own default. High (~18 dB) carves formants and is the
            // metallic sound on the mic-test loopback and on a clean desktop call.
            cfg.noiseSuppression.level = NOISE_SUPPRESSION_LEVEL

            instance.applyConfig(cfg)
            appliedAec = aec
            appliedAgc = agc
            appliedNs = ns
        } catch (_: Throwable) {
            isAvailable = false
        }
    }

    /**
     * Feeds a 20 ms playback frame (960 samples mono or 1920 samples interleaved stereo at 48 kHz)
     * into WebRTC APM's reverse stream so AEC3 has the exact speaker reference signal.
     */
    fun processReverseFrame20ms(pcm: ShortArray, channels: Int = 1) {
        if (!isAvailable) return
        val ch = channels.coerceIn(1, 2)
        val subSamples = subFrameSamplesPerChannel * ch
        if (pcm.size < subSamples * 2) return
        synchronized(lock) {
            val instance = apm ?: return
            if (closed) return
            val streamCfg = if (ch == 2) stereoConfig else monoConfig
            val inBuf = if (ch == 2) stereoSubBufIn else monoSubBufIn
            val outBuf = if (ch == 2) stereoSubBufOut else monoSubBufOut
            try {
                for (sub in 0 until 2) {
                    shortsToLeBytes(pcm, sub * subSamples, subSamples, inBuf)
                    instance.processReverseStream(inBuf, streamCfg, streamCfg, outBuf)
                }
                reverseFramesProcessed++
            } catch (_: Throwable) {
                // Keep playback running if native reverse stream errors.
            }
        }
    }

    /**
     * Processes a 20 ms microphone capture frame (960 samples mono or 1920 samples interleaved stereo
     * at 48 kHz) through WebRTC APM according to the active AEC/AGC/NS toggles.
     */
    fun processCaptureFrame20ms(
        pcm: ShortArray,
        channels: Int = 1,
        streamDelayMs: Int = defaultStreamDelayMs,
    ): ShortArray {
        if (!isAvailable) return pcm
        if (!echoCancellationEnabled && !autoGainControlEnabled && !noiseSuppressionEnabled) {
            return pcm
        }
        val ch = channels.coerceIn(1, 2)
        val subSamples = subFrameSamplesPerChannel * ch
        if (pcm.size < subSamples * 2) return pcm
        synchronized(lock) {
            val instance = apm ?: return pcm
            if (closed) return pcm
            val streamCfg = if (ch == 2) stereoConfig else monoConfig
            val inBuf = if (ch == 2) stereoSubBufIn else monoSubBufIn
            val outBuf = if (ch == 2) stereoSubBufOut else monoSubBufOut
            val out = pcm.copyOf()
            return try {
                for (sub in 0 until 2) {
                    shortsToLeBytes(pcm, sub * subSamples, subSamples, inBuf)
                    if (echoCancellationEnabled) {
                        instance.setStreamDelayMs(streamDelayMs.coerceIn(0, 500))
                    }
                    val rc = instance.processStream(inBuf, streamCfg, streamCfg, outBuf)
                    if (rc == 0) {
                        leBytesToShorts(outBuf, out, sub * subSamples, subSamples)
                    }
                }
                captureFramesProcessed++
                out
            } catch (_: Throwable) {
                pcm
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            isAvailable = false
            val instance = apm
            apm = null
            try {
                instance?.dispose()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val DEFAULT_STREAM_DELAY_MS = 40

        /** Single noise-suppression strength. One NS stage; this is not stacked with DeepFilterNet. */
        val NOISE_SUPPRESSION_LEVEL: AudioProcessingConfig.NoiseSuppression.Level =
            AudioProcessingConfig.NoiseSuppression.Level.MODERATE

        private fun shortsToLeBytes(src: ShortArray, srcOffset: Int, count: Int, dst: ByteArray) {
            for (i in 0 until count) {
                val s = src[srcOffset + i].toInt()
                dst[i * 2] = (s and 0xFF).toByte()
                dst[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
        }

        private fun leBytesToShorts(src: ByteArray, dst: ShortArray, dstOffset: Int, count: Int) {
            for (i in 0 until count) {
                val lo = src[i * 2].toInt() and 0xFF
                val hi = src[i * 2 + 1].toInt()
                dst[dstOffset + i] = ((hi shl 8) or lo).toShort()
            }
        }
    }
}
