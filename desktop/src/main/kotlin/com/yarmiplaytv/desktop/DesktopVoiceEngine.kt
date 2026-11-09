package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.DeepFilterNetProcessor
import com.yarmiplaytv.voice.DesktopWebRtcApm
import com.yarmiplaytv.voice.DiscordAudioConfig
import com.yarmiplaytv.voice.DiscordVoiceEngine
import com.yarmiplaytv.voice.JvmVoiceDevices
import com.yarmiplaytv.voice.VoiceAudioDevice
import com.yarmiplaytv.voice.VoiceCaptureKind
import com.yarmiplaytv.voice.VoiceChainPlatform
import com.yarmiplaytv.voice.VoiceEngine
import com.yarmiplaytv.voice.VoiceMediaSession
import com.yarmiplaytv.voice.VoicePcm
import com.yarmiplaytv.voice.VoiceProcessingChain
import com.yarmiplaytv.voice.VoiceSound
import com.yarmiplaytv.voice.participantSenderId
import com.yarmiplaytv.voice.senderParticipantId
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Desktop (Windows, macOS, Linux) capture + `yv1` Opus media (UDP, TLS TCP fallback).
 *
 * Integrates:
 * - [DesktopWebRtcApm] (`webrtc-java` AudioProcessing) for AEC3 (using playback mix as reverse
 *   reference), adaptive digital AGC, and WebRTC NS.
 * - [DeepFilterNetProcessor] (DeepFilterNet3 via ONNX Runtime) as a mutually exclusive AI noise
 *   suppression option.
 * - Configurable Opus bitrate up to 128 kbps and optional stereo mode on the Discord lobby path.
 * - Enforces the single-stage processing rule via [VoiceProcessingChain].
 */
class DesktopVoiceEngine : VoiceEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clip: Clip? = null
    private val session = AtomicReference<VoiceMediaSession?>(null)
    private val discordEngine = DiscordVoiceEngine(scope)
    @Volatile private var activeDiscord = false
    private var captureThread: Thread? = null
    private var captureLine: TargetDataLine? = null
    private var playbackLine: SourceDataLine? = null
    private var inputDeviceId: String? = null
    private var outputDeviceId: String? = null
    private var playbackJob: Job? = null
    private var watchJob: Job? = null
    private var frameSamples: Int = 960

    @Volatile private var noiseSuppressionPref: Boolean = true
    @Volatile private var echoCancellationPref: Boolean = true
    @Volatile private var autoGainControlPref: Boolean = true
    @Volatile private var aiNoiseSuppressionPref: Boolean = false
    @Volatile private var preferredBitrateBps: Int = VoiceMediaSession.DISCORD_BITRATE
    @Volatile private var preferredStereoMode: Boolean = false

    private val chainLock = Any()
    private var webRtcApm: DesktopWebRtcApm? = null
    private var deepFilterNet: DeepFilterNetProcessor? = null
    @Volatile private var dfnLoadFailedReason: String? = null
    @Volatile private var currentChain: VoiceProcessingChain = VoiceProcessingChain.build(
        platform = VoiceChainPlatform.DESKTOP,
        echoCancellation = true,
        autoGainControl = true,
        noiseSuppression = true,
        aiNoiseSuppression = false,
        deepFilterNetAvailable = true,
        discordLobby = true,
    )

    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    /** Snapshot of the currently active processing chain (used by diagnostics and unit tests). */
    fun activeProcessingChain(): VoiceProcessingChain = currentChain

    override fun statusNotice(): String? = currentChain.fallbackNotice

    override suspend fun detectCapture(): VoiceCaptureKind {
        return if (JvmVoiceDevices.listInputs().any { it.id.isNotEmpty() } ||
            runCatching {
                val line = JvmVoiceDevices.openCapture(null, 960)
                line?.close()
                line != null
            }.getOrDefault(false)
        ) {
            VoiceCaptureKind.OPEN
        } else {
            VoiceCaptureKind.NONE
        }
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnect()
        if (params.isDiscordLobby) {
            val ok = discordEngine.connect(params)
            if (!ok) return false
            activeDiscord = true
        } else {
            activeDiscord = false
        }
        val media = VoiceMediaSession(scope)
        media.setPreferredBitrate(preferredBitrateBps)
        media.setPreferredStereo(preferredStereoMode)
        if (!media.connect(params)) {
            if (activeDiscord) {
                discordEngine.disconnect()
                activeDiscord = false
            }
            return false
        }
        synchronized(chainLock) {
            if (activeDiscord) {
                webRtcApm = DesktopWebRtcApm(
                    echoCancellation = echoCancellationPref,
                    autoGainControl = autoGainControlPref,
                    noiseSuppression = noiseSuppressionPref && !aiNoiseSuppressionPref,
                )
                if (aiNoiseSuppressionPref) {
                    ensureDeepFilterNetLocked()
                }
            }
        }
        if (activeDiscord && discordEngine.audioConfig().selfDeaf) media.setSelfDeaf(true)
        session.set(media)
        frameSamples = media.frameSamples
        val chain = rebuildProcessingChain(logChain = true)
        // HARD RULE: never run the legacy spectral suppressor on the Discord lobby path or alongside WebRTC/DFN.
        media.setNoiseSuppression(chain.spectralNsEnabled)
        openPlayback()
        // Capture only while transmitting so mute/mic-off releases the input device.
        startMixPump(media)
        startDeviceWatch()
        return true
    }

    override fun disconnect() {
        if (activeDiscord) {
            discordEngine.disconnect()
            activeDiscord = false
        }
        watchJob?.cancel()
        watchJob = null
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        stopCapture()
        closePlaybackLine()
        synchronized(chainLock) {
            runCatching { webRtcApm?.close() }
            webRtcApm = null
            runCatching { deepFilterNet?.close() }
            deepFilterNet = null
        }
        runCatching { clip?.close() }
        clip = null
    }

    override fun setTransmitting(on: Boolean) {
        if (activeDiscord) {
            discordEngine.setTransmitting(on)
        }
        val media = session.get()
        media?.setTransmitting(on)
        syncCapture(media)
    }

    override fun setSelfMuted(muted: Boolean) {
        if (activeDiscord) {
            discordEngine.setSelfMuted(muted)
        }
        val media = session.get()
        media?.setSelfMuted(muted)
        // Mute stops capture here; unmute waits for [setTransmitting] so the UI isn't blocked twice.
        if (muted) syncCapture(media)
    }

    override fun setSelfDeaf(deaf: Boolean) {
        if (!activeDiscord) return
        discordEngine.setSelfDeaf(deaf)
        val media = session.get() ?: return
        media.setSelfDeaf(deaf)
        syncCapture(media)
    }

    override fun setVoiceActivityMode(activity: Boolean) {
        if (activeDiscord) discordEngine.setVoiceActivityMode(activity)
    }

    /** Audio state recorded for the Discord lobby. Empty outside a lobby only if never configured. */
    internal fun discordAudioConfig(): DiscordAudioConfig = discordEngine.audioConfig()

    override fun captureLevel(): Float {
        val s = session.get()
        if (s != null) {
            val level = s.captureLevel
            if (level > 0f) return level
        }
        return if (activeDiscord) discordEngine.captureLevel() else 0f
    }

    override fun audibleParticipantIds(): Set<String> {
        val mediaSenders = session.get()?.audibleSenderIds()?.mapTo(linkedSetOf()) { senderParticipantId(it) } ?: emptySet()
        val discordSenders = if (activeDiscord) discordEngine.audibleParticipantIds() else emptySet()
        return mediaSenders + discordSenders
    }

    override fun capturingSpeech(): Boolean =
        session.get()?.capturingSpeech() == true || (activeDiscord && discordEngine.capturingSpeech())

    private fun syncCapture(media: VoiceMediaSession?) {
        if (media == null || session.get() !== media) {
            stopCapture()
            return
        }
        if (media.isTransmitting()) {
            if (captureThread?.isAlive != true) startCapture(media)
        } else {
            stopCapture()
        }
    }

    private fun stopCapture() {
        val t = captureThread
        captureThread = null
        t?.interrupt()
        closeCaptureLine()
        // Join so a rapid unmute doesn't open a second TargetDataLine while Windows still holds the first.
        if (t != null && t.isAlive) {
            runCatching { t.join(750) }
        }
    }

    override fun setInputGain(gain: Float) {
        discordEngine.setInputGain(gain)
        if (activeDiscord) {
            session.get()?.setInputGain(discordEngine.inputAmplitude())
        } else {
            session.get()?.setInputGain(gain)
        }
    }

    override fun setOutputGain(gain: Float) {
        discordEngine.setOutputGain(gain)
        if (activeDiscord) {
            session.get()?.setOutputGain(discordEngine.outputAmplitude())
        } else {
            session.get()?.setOutputGain(gain)
        }
    }

    override fun setNoiseSuppression(on: Boolean) {
        noiseSuppressionPref = on
        discordEngine.setNoiseSuppression(on)
        rebuildProcessingChain(logChain = false)
    }

    override fun setEchoCancellation(on: Boolean) {
        echoCancellationPref = on
        discordEngine.setEchoCancellation(on)
        rebuildProcessingChain(logChain = false)
    }

    override fun setAutoGainControl(on: Boolean) {
        autoGainControlPref = on
        discordEngine.setAutoGainControl(on)
        rebuildProcessingChain(logChain = false)
    }

    override fun setAiNoiseSuppression(on: Boolean) {
        aiNoiseSuppressionPref = on
        discordEngine.setAiNoiseSuppression(on)
        if (on && activeDiscord) {
            synchronized(chainLock) {
                // Clear previous failure so re-toggling retries initialization once.
                if (deepFilterNet == null) dfnLoadFailedReason = null
                if (session.get() != null) {
                    ensureDeepFilterNetLocked()
                }
            }
        }
        rebuildProcessingChain(logChain = false)
    }

    override fun setPreferredBitrate(bitrateBps: Int) {
        val clamped = bitrateBps.coerceIn(VoiceMediaSession.MIN_VOICE_BITRATE, VoiceMediaSession.MAX_VOICE_BITRATE)
        preferredBitrateBps = clamped
        discordEngine.setPreferredBitrate(clamped)
        session.get()?.setPreferredBitrate(clamped)
    }

    override fun setPreferredStereo(stereo: Boolean) {
        preferredStereoMode = stereo
        discordEngine.setPreferredStereo(stereo)
        val media = session.get()
        val before = media?.activeChannels
        media?.setPreferredStereo(stereo)
        if (media != null && before != null && media.activeChannels != before) {
            openPlayback()
            stopCapture()
            syncCapture(media)
        }
    }

    private fun ensureDeepFilterNetLocked(): DeepFilterNetProcessor? {
        deepFilterNet?.let { if (it.isAvailable) return it }
        if (dfnLoadFailedReason != null) return null
        val processor = DeepFilterNetProcessor(
            onFallback = { reason ->
                dfnLoadFailedReason = reason
                rebuildProcessingChain(logChain = true)
            },
        )
        if (!processor.isAvailable) {
            dfnLoadFailedReason = processor.failureReason ?: "DeepFilterNet3 model or runtime unavailable"
            processor.close()
            return null
        }
        deepFilterNet = processor
        return processor
    }

    private fun rebuildProcessingChain(logChain: Boolean): VoiceProcessingChain {
        synchronized(chainLock) {
            if (aiNoiseSuppressionPref && activeDiscord && session.get() != null &&
                deepFilterNet == null && dfnLoadFailedReason == null
            ) {
                ensureDeepFilterNetLocked()
            }
            val dfn = deepFilterNet
            val dfnAvailable = if (aiNoiseSuppressionPref) {
                if (session.get() != null) {
                    dfn != null && dfn.isAvailable && dfnLoadFailedReason == null
                } else {
                    dfnLoadFailedReason == null
                }
            } else {
                true
            }
            val discord = if (session.get() != null) activeDiscord else true
            val chain = VoiceProcessingChain.build(
                platform = VoiceChainPlatform.DESKTOP,
                discordLobby = discord,
                echoCancellation = echoCancellationPref,
                autoGainControl = autoGainControlPref,
                noiseSuppression = noiseSuppressionPref,
                aiNoiseSuppression = aiNoiseSuppressionPref,
                webrtcApmAvailable = webRtcApm?.isAvailable ?: true,
                deepFilterNetAvailable = dfnAvailable,
                deepFilterNetFailureReason = dfnLoadFailedReason ?: dfn?.failureReason,
            )
            currentChain = chain
            webRtcApm?.configure(
                echoCancellation = chain.webrtcAecEnabled,
                autoGainControl = chain.webrtcAgcEnabled,
                noiseSuppression = chain.webrtcNsEnabled,
            )
            session.get()?.setNoiseSuppression(chain.spectralNsEnabled)
            if (logChain) {
                println(chain.logLine())
            }
            return chain
        }
    }

    override fun setPeerMuted(participantId: String, muted: Boolean) {
        if (activeDiscord) discordEngine.setPeerMuted(participantId, muted)
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerMuted(id, muted)
    }

    override fun setPeerVolume(participantId: String, volume: Float) {
        if (activeDiscord) discordEngine.setPeerVolume(participantId, volume)
        val id = participantSenderId(participantId) ?: return
        val applied = if (activeDiscord) discordEngine.peerAmplitude(participantId) else volume
        session.get()?.setPeerVolume(id, applied)
    }

    override fun listInputDevices(): List<VoiceAudioDevice> =
        JvmVoiceDevices.listInputs().map { VoiceAudioDevice(it.id, it.label, it.id.isEmpty()) }

    override fun listOutputDevices(): List<VoiceAudioDevice> =
        JvmVoiceDevices.listOutputs().map { VoiceAudioDevice(it.id, it.label, it.id.isEmpty()) }

    override fun setInputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (activeDiscord) discordEngine.setInputDeviceId(next)
        if (inputDeviceId == next) return
        inputDeviceId = next
        val media = session.get() ?: return
        stopCapture()
        syncCapture(media)
    }

    override fun setOutputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (activeDiscord) discordEngine.setOutputDeviceId(next)
        if (outputDeviceId == next) return
        outputDeviceId = next
        if (session.get() != null) openPlayback()
    }

    override fun playSound(kind: VoiceSound, volume: Float) {
        val name = when (kind) {
            VoiceSound.JOIN -> "voice_join.wav"
            VoiceSound.LEAVE -> "voice_leave.wav"
            VoiceSound.MIC_ON -> "voice_mic_on.wav"
            VoiceSound.MIC_OFF -> "voice_mic_off.wav"
        }
        // Packaged at the jar root by desktop processResources (copied from shared composeResources/files).
        val stream = listOf(
            "/$name",
            "/composeResources/com.yarmiplaytv.shared.generated.resources/files/$name",
            "/files/$name",
        ).firstNotNullOfOrNull { path -> javaClass.getResourceAsStream(path) } ?: run {
            println("DesktopVoice missing sound resource: $name")
            return
        }
        runCatching {
            clip?.close()
            val audio = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
            clip = AudioSystem.getClip().also { c ->
                c.open(audio)
                runCatching {
                    val ctrl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    val vol = volume.coerceIn(0.05f, 1f)
                    ctrl.value = (Math.log10(vol.toDouble()) * 20.0).toFloat().coerceIn(ctrl.minimum, ctrl.maximum)
                }
                c.start()
            }
        }.onFailure { println("DesktopVoice playSound $name failed: ${it.message}") }
    }

    private fun openPlayback() {
        closePlaybackLine()
        val wantChannels = session.get()?.activeChannels ?: 1
        var id = outputDeviceId
        var line = JvmVoiceDevices.openPlayback(id, frameSamples, wantChannels)
        if (line == null && !id.isNullOrEmpty()) {
            outputDeviceId = null
            if (activeDiscord) discordEngine.setOutputDeviceId(null)
            onDeviceLost?.invoke(false)
            id = null
            line = JvmVoiceDevices.openPlayback(null, frameSamples, wantChannels)
        }
        if (line == null) {
            println("DesktopVoice playback open failed device=${id ?: "system default"} (need 48 kHz PCM)")
        }
        playbackLine = line
    }

    private fun startMixPump(media: VoiceMediaSession) {
        var loggedFormat = false
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() === media) {
                val line = playbackLine
                val lineChannels = line?.format?.channels?.coerceIn(1, 2) ?: media.activeChannels
                val outSamples = media.frameSamples * lineChannels
                val out = ShortArray(outSamples)
                val n = media.pollMixedPcm(out)
                // Feed the playback mix into WebRTC APM as the far-end AEC reference.
                if (n > 0) {
                    webRtcApm?.processReverseFrame20ms(out, lineChannels)
                }
                if (n > 0 && line != null) {
                    val bytes = ByteArray(n * 2)
                    if (!loggedFormat) {
                        loggedFormat = true
                        println(
                            "DesktopVoice playback format=PCM16LE rate=48000 ch=$lineChannels " +
                                "frameSamples=${media.frameSamples} bytesPerFrame=${n * 2} " +
                                "firstRemoteRms=${media.lastRemoteRms}",
                        )
                    }
                    for (i in 0 until n) {
                        val s = out[i].toInt()
                        bytes[i * 2] = (s and 0xff).toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    runCatching { line.write(bytes, 0, n * 2) }
                } else {
                    val silenceBytes = ByteArray(minOf(outSamples * 2, 480))
                    runCatching { line?.write(silenceBytes, 0, silenceBytes.size) }
                    Thread.sleep(5)
                }
            }
        }
    }

    private fun startCapture(media: VoiceMediaSession) {
        stopCapture()
        val preferredId = inputDeviceId
        val wantChannels = media.activeChannels
        // Open the line on this worker thread so mute/unmute never blocks the UI on Java Sound.
        captureThread = thread(name = "voice-capture", isDaemon = true) {
            var id = preferredId
            var line = JvmVoiceDevices.openCapture(id, media.frameSamples, wantChannels)
            if (line == null && !id.isNullOrEmpty()) {
                inputDeviceId = null
                if (activeDiscord) discordEngine.setInputDeviceId(null)
                onDeviceLost?.invoke(true)
                id = null
                line = JvmVoiceDevices.openCapture(null, media.frameSamples, wantChannels)
            }
            if (line == null) {
                println("DesktopVoice capture open failed device=${id ?: "system default"} (need 48 kHz PCM)")
                return@thread
            }
            if (session.get() !== media || Thread.currentThread().isInterrupted) {
                runCatching { line.stop(); line.close() }
                return@thread
            }
            captureLine = line
            val lineChannels = line.format.channels.coerceIn(1, 2)
            val totalSamples = media.frameSamples * lineChannels
            val frameBytes = totalSamples * 2
            val buf = ByteArray(frameBytes)
            val pcm = ShortArray(totalSamples)
            println(
                "DesktopVoice capture format=PCM16LE rate=48000 ch=$lineChannels " +
                    "frameSamples=${media.frameSamples} bytesPerFrame=$frameBytes device=${id ?: "system default"}",
            )
            try {
                while (!Thread.currentThread().isInterrupted && session.get() === media) {
                    var read = 0
                    while (read < frameBytes) {
                        val n = line.read(buf, read, frameBytes - read)
                        if (n < 0) return@thread
                        read += n
                    }
                    for (i in pcm.indices) {
                        val lo = buf[i * 2].toInt() and 0xff
                        val hi = buf[i * 2 + 1].toInt()
                        pcm[i] = ((hi shl 8) or lo).toShort()
                    }
                    val chain = currentChain
                    // Stage 1 & 2 (& WebRTC NS when DeepFilterNet is off): WebRTC AudioProcessing
                    val afterApm = webRtcApm?.processCaptureFrame20ms(pcm, channels = lineChannels, streamDelayMs = 20) ?: pcm
                    // Stage 3 (when AI NS is on): DeepFilterNet3 (processes 48 kHz 20 ms frames)
                    val dfn = deepFilterNet
                    val outPcm = if (chain.deepFilterNetEnabled && dfn != null && dfn.isAvailable) {
                        val processed = dfn.processFrame20ms(afterApm, channels = lineChannels)
                        if (!dfn.isAvailable) {
                            rebuildProcessingChain(logChain = true)
                        }
                        processed
                    } else {
                        afterApm
                    }
                    kotlinx.coroutines.runBlocking { media.offerCapturePcm(outPcm, pcmChannels = lineChannels) }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { line.stop(); line.close() }
                if (captureLine === line) captureLine = null
            }
        }
    }

    private fun startDeviceWatch() {
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() != null) {
                delay(1_500)
                val media = session.get() ?: break
                if (!JvmVoiceDevices.isAvailable(inputDeviceId, target = true)) {
                    inputDeviceId = null
                    if (activeDiscord) discordEngine.setInputDeviceId(null)
                    onDeviceLost?.invoke(true)
                    stopCapture()
                    syncCapture(media)
                }
                if (!JvmVoiceDevices.isAvailable(outputDeviceId, target = false)) {
                    outputDeviceId = null
                    if (activeDiscord) discordEngine.setOutputDeviceId(null)
                    onDeviceLost?.invoke(false)
                    openPlayback()
                }
            }
        }
    }

    private fun closeCaptureLine() {
        runCatching { captureLine?.stop(); captureLine?.close() }
        captureLine = null
    }

    private fun closePlaybackLine() {
        runCatching { playbackLine?.stop(); playbackLine?.close() }
        playbackLine = null
    }
}
