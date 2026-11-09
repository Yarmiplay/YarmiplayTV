package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.DeepFilterNetProcessor
import com.yarmiplaytv.voice.DesktopWebRtcApm
import com.yarmiplaytv.voice.JvmVoiceDevices
import com.yarmiplaytv.voice.StreamingResampler
import com.yarmiplaytv.voice.VoiceChainPlatform
import com.yarmiplaytv.voice.VoiceProcessingChain
import com.yarmiplaytv.voice.VoiceAudioDevice
import com.yarmiplaytv.voice.VoiceCaptureKind
import com.yarmiplaytv.voice.VoiceEngine
import com.yarmiplaytv.voice.VoiceMediaSession
import com.yarmiplaytv.voice.VoicePcm
import com.yarmiplaytv.voice.DiscordAudioConfig
import com.yarmiplaytv.voice.DiscordVoiceEngine
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
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop/macOS capture + `yv1` Opus media (UDP, TLS TCP fallback).
 * Lists real Java Sound mixers and switches mic/speaker live without rejoining.
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
    private var wasapiRender: WasapiRender? = null
    private var wasapiCapture: WasapiCapture? = null
    @Volatile private var preferWasapiPlayback = false
    @Volatile private var wasapiPlaybackFailed = false
    private var inputDeviceId: String? = null
    private var outputDeviceId: String? = null
    private var playbackJob: Job? = null
    private var watchJob: Job? = null
    private var frameSamples: Int = 960
    @Volatile private var inputGain: Float = 1f
    @Volatile private var outputGain: Float = 1f
    @Volatile private var noiseSuppressionPref = true
    @Volatile private var echoCancellationPref = true
    @Volatile private var autoGainControlPref = true
    @Volatile private var aiNoiseSuppressionPref = false
    @Volatile private var preferredBitrateBps = VoiceMediaSession.DISCORD_BITRATE
    @Volatile private var preferredStereoMode = false
    private val chainLock = Any()
    private var webRtcApm: DesktopWebRtcApm? = null
    private var deepFilterNet: DeepFilterNetProcessor? = null
    @Volatile private var dfnLoadFailedReason: String? = null
    @Volatile private var currentChain: VoiceProcessingChain = VoiceProcessingChain.build(
        platform = VoiceChainPlatform.DESKTOP,
        discordLobby = true,
    )
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

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
        if (!media.connect(params)) {
            if (activeDiscord) {
                discordEngine.disconnect()
                activeDiscord = false
            }
            return false
        }
        session.set(media)
        frameSamples = media.frameSamples
        // Lobby path: WebRTC AGC2 is the one gain stage, so the fixed +12 dB makeup stays off.
        // Relay path: no WebRTC AGC, spectral NS only, makeup remains.
        val chain = rebuildProcessingChain(logChain = true)
        media.setNoiseSuppression(chain.spectralNsEnabled)
        media.setAgcStageActive(chain.webrtcAgcEnabled)
        media.setPreferredBitrate(preferredBitrateBps)
        media.setPreferredStereo(preferredStereoMode)
        if (activeDiscord) {
            discordEngine.setPreferredBitrate(preferredBitrateBps)
            discordEngine.setPreferredStereo(preferredStereoMode)
        }
        media.setInputGain(if (activeDiscord) inputGain.coerceIn(0f, 2f) else inputGain)
        media.setOutputGain(if (activeDiscord) discordEngine.outputAmplitude() else outputGain)
        if (activeDiscord && discordEngine.audioConfig().selfDeaf) media.setSelfDeaf(true)
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
        closeProcessors()
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

    override fun setMicTest(active: Boolean) {
        if (activeDiscord) {
            discordEngine.setMicTest(active)
        }
        if (active) {
            val existing = session.get()
            if (existing != null) {
                existing.setMicTest(true)
                val chain = rebuildProcessingChain(logChain = false)
                existing.setAgcStageActive(chain.webrtcAgcEnabled)
                syncCapture(existing)
                return
            }
            val media = VoiceMediaSession(scope)
            media.startStandaloneMicTest()
            media.setInputGain(inputGain)
            media.setOutputGain(outputGain)
            session.set(media)
            frameSamples = media.frameSamples
            val chain = rebuildProcessingChain(logChain = false)
            media.setAgcStageActive(chain.webrtcAgcEnabled)
            openPlayback()
            startMixPump(media)
            startDeviceWatch()
            syncCapture(media)
        } else {
            val existing = session.get() ?: return
            if (existing.isStandaloneLoopback) {
                disconnect()
            } else {
                existing.setMicTest(false)
                syncCapture(existing)
            }
        }
    }

    override fun isMicTestActive(): Boolean =
        session.get()?.isMicTestActive == true || (activeDiscord && discordEngine.isMicTestActive())

    override fun micTestLevel(): Float {
        val s = session.get()
        if (s != null && s.isMicTestActive) return s.micTestLevel
        return if (activeDiscord) discordEngine.micTestLevel() else 0f
    }

    private fun syncCapture(media: VoiceMediaSession?) {
        if (media == null || session.get() !== media) {
            stopCapture()
            return
        }
        if (media.shouldCaptureAudio()) {
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
        inputGain = gain
        if (activeDiscord) {
            discordEngine.setInputGain(gain)
            // The SDK record stays 0–100%. The digital slider still reaches 200%;
            // the one playback limiter absorbs the boost.
            session.get()?.setInputGain(gain.coerceIn(0f, 2f))
        } else {
            session.get()?.setInputGain(gain)
        }
    }

    override fun setOutputGain(gain: Float) {
        outputGain = gain
        if (activeDiscord) {
            discordEngine.setOutputGain(gain)
            session.get()?.setOutputGain(discordEngine.outputAmplitude())
        } else {
            session.get()?.setOutputGain(gain)
        }
    }

    override fun setNoiseSuppression(on: Boolean) {
        noiseSuppressionPref = on
        discordEngine.setNoiseSuppression(on)
        val chain = rebuildProcessingChain(logChain = false)
        session.get()?.setAgcStageActive(chain.webrtcAgcEnabled)
    }

    override fun setEchoCancellation(on: Boolean) {
        echoCancellationPref = on
        discordEngine.setEchoCancellation(on)
        val chain = rebuildProcessingChain(logChain = false)
        session.get()?.setAgcStageActive(chain.webrtcAgcEnabled)
    }

    override fun setAutoGainControl(on: Boolean) {
        autoGainControlPref = on
        discordEngine.setAutoGainControl(on)
        val chain = rebuildProcessingChain(logChain = false)
        session.get()?.setAgcStageActive(chain.webrtcAgcEnabled)
    }

    override fun setAiNoiseSuppression(on: Boolean) {
        aiNoiseSuppressionPref = on
        discordEngine.setAiNoiseSuppression(on)
        if (on && activeDiscord) {
            synchronized(chainLock) {
                if (deepFilterNet == null) dfnLoadFailedReason = null
                if (session.get() != null) ensureDeepFilterNetLocked()
            }
        }
        val chain = rebuildProcessingChain(logChain = false)
        session.get()?.setAgcStageActive(chain.webrtcAgcEnabled)
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
        val media = session.get() ?: return
        val before = media.activeChannels
        media.setPreferredStereo(stereo)
        if (media.activeChannels != before) {
            openPlayback()
            playbackJob?.cancel()
            startMixPump(media)
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

    private fun ensureWebRtcApm(): DesktopWebRtcApm? {
        webRtcApm?.let { if (it.isAvailable) return it }
        val created = DesktopWebRtcApm(
            echoCancellation = echoCancellationPref,
            autoGainControl = autoGainControlPref,
            noiseSuppression = noiseSuppressionPref && !aiNoiseSuppressionPref,
        )
        if (!created.isAvailable) {
            created.close()
            webRtcApm = null
            return null
        }
        webRtcApm = created
        return created
    }

    private fun rebuildProcessingChain(logChain: Boolean): VoiceProcessingChain {
        synchronized(chainLock) {
            val discord = if (session.get() != null) activeDiscord else true
            if (discord) ensureWebRtcApm()
            if (aiNoiseSuppressionPref && discord && session.get() != null &&
                deepFilterNet == null && dfnLoadFailedReason == null
            ) {
                ensureDeepFilterNetLocked()
            }
            val dfn = deepFilterNet
            val dfnAvailable = if (aiNoiseSuppressionPref) {
                if (session.get() != null) dfn != null && dfn.isAvailable && dfnLoadFailedReason == null
                else dfnLoadFailedReason == null
            } else {
                true
            }
            val chain = VoiceProcessingChain.build(
                platform = VoiceChainPlatform.DESKTOP,
                discordLobby = discord,
                echoCancellation = echoCancellationPref,
                autoGainControl = autoGainControlPref,
                noiseSuppression = noiseSuppressionPref,
                aiNoiseSuppression = aiNoiseSuppressionPref,
                webrtcApmAvailable = webRtcApm?.isAvailable ?: !discord,
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
            if (logChain) println(chain.logLine())
            return chain
        }
    }

    private fun closeProcessors() {
        synchronized(chainLock) {
            runCatching { webRtcApm?.close() }
            webRtcApm = null
            runCatching { deepFilterNet?.close() }
            deepFilterNet = null
            dfnLoadFailedReason = null
        }
    }

    /** One AEC, one AGC, and one noise stage. Spectral NS never runs on the lobby path. */
    private fun processCapture(frame: ShortArray): ShortArray {
        val chain = currentChain
        var pcm = frame
        val apm = webRtcApm
        if (apm != null && apm.isAvailable &&
            (chain.webrtcAecEnabled || chain.webrtcAgcEnabled || chain.webrtcNsEnabled)
        ) {
            pcm = apm.processCaptureFrame20ms(pcm, channels = 1)
        }
        if (chain.deepFilterNetEnabled) {
            val dfn = deepFilterNet
            if (dfn != null && dfn.isAvailable) pcm = dfn.processFrame20ms(pcm, channels = 1)
        }
        return pcm
    }

    private fun feedReverse(pcm: ShortArray, channels: Int) {
        val apm = webRtcApm ?: return
        if (!currentChain.webrtcAecEnabled || !apm.isAvailable || pcm.isEmpty()) return
        apm.processReverseFrame20ms(pcm, channels)
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
        wasapiPlaybackFailed = false
        // Default device on Windows: open WASAPI on the playback thread (same COM apartment).
        // A chosen device, or any non-Windows host, stays on Java Sound (CoreAudio / PipeWire / DirectSound).
        val stereo = (session.get()?.activeChannels ?: 1) > 1
        // WASAPI shared render in this build is mono. Stereo stays on Java Sound.
        preferWasapiPlayback = !stereo && outputDeviceId.isNullOrEmpty() && isWindows
        if (preferWasapiPlayback) return
        openJavaSoundPlayback()
    }

    private fun openJavaSoundPlayback() {
        var id = outputDeviceId
        val channels = (session.get()?.activeChannels ?: 1).coerceIn(1, 2)
        var line = JvmVoiceDevices.openPlayback(id, frameSamples, channels)
        if (line == null && !id.isNullOrEmpty()) {
            outputDeviceId = null
            if (activeDiscord) discordEngine.setOutputDeviceId(null)
            onDeviceLost?.invoke(false)
            id = null
            line = JvmVoiceDevices.openPlayback(null, frameSamples, channels)
        }
        if (line == null) {
            println("DesktopVoice playback open failed device=${id ?: "system default"} backend=${javaSoundBackend()}")
        } else {
            println("DesktopVoice playback backend=${javaSoundBackend()} rate=${line.format.sampleRate}")
        }
        playbackLine = line
    }

    private fun javaSoundBackend(): String {
        val os = System.getProperty("os.name").orEmpty()
        return when {
            os.startsWith("Mac") -> "CoreAudio"
            os.startsWith("Windows") -> "DirectSound"
            else -> "PipeWire/PulseAudio/ALSA"
        }
    }

    private fun startMixPump(media: VoiceMediaSession) {
        var out = ShortArray(media.frameSamples)
        var bytes = ByteArray(out.size * 2)
        var loggedFormat = false
        var resamplerRate = VoicePcm.SAMPLE_RATE
        var resampler: StreamingResampler? = null
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() === media) {
                val channels = media.activeChannels.coerceIn(1, 2)
                val need = media.frameSamples * channels
                if (out.size != need) out = ShortArray(need)
                if (preferWasapiPlayback && wasapiRender == null && !wasapiPlaybackFailed) {
                    wasapiRender = WasapiAudio.openRender()
                    if (wasapiRender == null) {
                        wasapiPlaybackFailed = true
                        preferWasapiPlayback = false
                        println("DesktopVoice playback backend=JavaSound (WASAPI unavailable)")
                        openJavaSoundPlayback()
                    } else {
                        println("DesktopVoice playback backend=WASAPI shared rate=${wasapiRender?.sampleRate}")
                    }
                }
                val n = media.pollMixedPcm(out)
                if (n <= 0) {
                    delay(3)
                    continue
                }
                val inputPcm = if (n == out.size) out else out.copyOf(n)
                feedReverse(inputPcm, channels)
                val render = wasapiRender
                if (channels == 1 && render != null) {
                    runCatching { render.writeMono48k(inputPcm) }
                    continue
                }
                val line = playbackLine
                if (n > 0 && line != null) {
                    val lineRate = line.format.sampleRate.roundToInt().takeIf { it > 0 } ?: VoicePcm.SAMPLE_RATE
                    if (lineRate != resamplerRate) {
                        resamplerRate = lineRate
                        resampler = if (lineRate == VoicePcm.SAMPLE_RATE) {
                            null
                        } else {
                            StreamingResampler(VoicePcm.SAMPLE_RATE, lineRate)
                        }
                    }
                    val playPcm = if (channels > 1) inputPcm else (resampler?.push(inputPcm) ?: inputPcm)
                    if (playPcm.isNotEmpty()) {
                        if (!loggedFormat) {
                            loggedFormat = true
                            println(
                                "DesktopVoice playback format=PCM16LE rate=$lineRate ch=$channels " +
                                    "frameSamples=${playPcm.size} bytesPerFrame=${playPcm.size * 2} " +
                                    "firstRemoteRms=${media.lastRemoteRms}",
                            )
                        }
                        if (bytes.size < playPcm.size * 2) {
                            bytes = ByteArray(playPcm.size * 2)
                        }
                        for (i in playPcm.indices) {
                            val s = playPcm[i].toInt()
                            bytes[i * 2] = (s and 0xff).toByte()
                            bytes[i * 2 + 1] = (s shr 8).toByte()
                        }
                        runCatching { line.write(bytes, 0, playPcm.size * 2) }
                    }
                } else {
                    delay(3)
                }
            }
        }
    }

    private fun startCapture(media: VoiceMediaSession) {
        stopCapture()
        val preferredId = inputDeviceId
        // Open the line on this worker thread so mute/unmute never blocks the UI on Java Sound.
        captureThread = thread(name = "voice-capture", isDaemon = true) {
            runCatching { Thread.currentThread().priority = Thread.MAX_PRIORITY }
            var id = preferredId
            if (id.isNullOrEmpty() && isWindows) {
                val cap = WasapiAudio.openCapture()
                if (cap != null) {
                    wasapiCapture = cap
                    println("DesktopVoice capture backend=WASAPI shared")
                    val accumulator = VoicePcm.FrameAccumulator(media.frameSamples)
                    try {
                        while (!Thread.currentThread().isInterrupted && session.get() === media) {
                            val pcm48 = cap.readMono48k()
                            if (pcm48.isEmpty()) {
                                Thread.sleep(4)
                                continue
                            }
                            for (frame in accumulator.push(pcm48)) {
                                kotlinx.coroutines.runBlocking { media.offerCapturePcm(processCapture(frame)) }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        cap.close()
                        if (wasapiCapture === cap) wasapiCapture = null
                    }
                    return@thread
                }
                println("DesktopVoice capture backend=${javaSoundBackend()} (WASAPI unavailable)")
            }
            var line = JvmVoiceDevices.openCapture(id, media.frameSamples)
            if (line == null && !id.isNullOrEmpty()) {
                inputDeviceId = null
                if (activeDiscord) discordEngine.setInputDeviceId(null)
                onDeviceLost?.invoke(true)
                id = null
                line = JvmVoiceDevices.openCapture(null, media.frameSamples)
            }
            if (line == null) {
                println("DesktopVoice capture open failed device=${id ?: "system default"} (need mono PCM)")
                return@thread
            }
            if (session.get() !== media || Thread.currentThread().isInterrupted) {
                runCatching { line.stop(); line.close() }
                return@thread
            }
            captureLine = line
            val actualRate = line.format.sampleRate.roundToInt().takeIf { it > 0 } ?: VoicePcm.SAMPLE_RATE
            val readSamples = if (actualRate == VoicePcm.SAMPLE_RATE) {
                media.frameSamples
            } else {
                maxOf(actualRate / 50, 160)
            }
            val frameBytes = readSamples * 2
            val buf = ByteArray(frameBytes)
            val pcm = ShortArray(readSamples)
            val resampler = if (actualRate == VoicePcm.SAMPLE_RATE) {
                null
            } else {
                StreamingResampler(actualRate, VoicePcm.SAMPLE_RATE)
            }
            val accumulator = if (resampler != null) VoicePcm.FrameAccumulator(media.frameSamples) else null
            println(
                "DesktopVoice capture format=PCM16LE rate=$actualRate opusRate=${VoicePcm.SAMPLE_RATE} ch=1 " +
                    "readSamples=$readSamples bytesPerRead=$frameBytes device=${id ?: "system default"}",
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
                    if (resampler == null) {
                        kotlinx.coroutines.runBlocking { media.offerCapturePcm(processCapture(pcm)) }
                    } else {
                        val resampled = resampler.push(pcm)
                        for (frame in accumulator!!.push(resampled)) {
                            kotlinx.coroutines.runBlocking { media.offerCapturePcm(processCapture(frame)) }
                        }
                    }
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
        wasapiCapture?.close()
        wasapiCapture = null
        runCatching { captureLine?.stop(); captureLine?.close() }
        captureLine = null
    }

    private fun closePlaybackLine() {
        wasapiRender?.close()
        wasapiRender = null
        preferWasapiPlayback = false
        runCatching { playbackLine?.stop(); playbackLine?.close() }
        playbackLine = null
    }
}
