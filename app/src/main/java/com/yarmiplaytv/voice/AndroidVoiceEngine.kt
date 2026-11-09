package com.yarmiplaytv.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import com.kaleyra.noise_filter.utils.DispatcherProvider
import com.rikorose.deepfilternet.NativeDeepFilterNet
import com.yarmiplaytv.R
import com.yarmiplaytv.syncplay.VoiceMediaParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android / Android TV capture/playback + `yv1` Opus media (UDP, TLS TCP fallback).
 *
 * Enforces the single-stage voice processing chain via [VoiceProcessingChain]:
 * - Platform `AcousticEchoCanceler`, `AutomaticGainControl`, and `NoiseSuppressor` are governed by
 *   their respective toggles.
 * - When AI noise suppression (DeepFilterNet3 via `libdf.so` JNI) is enabled, the platform
 *   `NoiseSuppressor` is explicitly disabled (`chain.platformNsEnabled == false`) while AEC and AGC
 *   stay governed by their own toggles.
 * - If DeepFilterNet3 fails to load or exceeds the real-time 20 ms frame budget, it automatically
 *   falls back to the platform `NoiseSuppressor` and surfaces an in-app notice via [statusNotice].
 */
class AndroidVoiceEngine(
    context: Context,
    private val isTv: Boolean,
) : VoiceEngine {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chainPlatform = if (isTv) VoiceChainPlatform.ANDROID_TV else VoiceChainPlatform.ANDROID
    private var preferredInputId: String? = null
    private var preferredOutputId: String? = null
    private var player: MediaPlayer? = null
    private val session = AtomicReference<VoiceMediaSession?>(null)
    private val discordEngine = DiscordVoiceEngine(scope)
    @Volatile private var activeDiscord = false
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var frameSamples: Int = 960

    @Volatile private var noiseSuppressionPref = true
    @Volatile private var echoCancellationPref = true
    @Volatile private var autoGainControlPref = true
    @Volatile private var aiNoiseSuppressionPref = false
    @Volatile private var preferredBitrateBps = VoiceMediaSession.DISCORD_BITRATE
    @Volatile private var preferredStereoMode = false

    private val chainLock = Any()
    private var deepFilterNet: DeepFilterNetProcessor? = null
    @Volatile private var dfnLoadFailedReason: String? = null
    @Volatile private var currentChain: VoiceProcessingChain = VoiceProcessingChain.build(
        platform = chainPlatform,
        echoCancellation = true,
        autoGainControl = true,
        noiseSuppression = true,
        aiNoiseSuppression = false,
        deepFilterNetAvailable = true,
        discordLobby = true,
    )

    private var platformNs: NoiseSuppressor? = null
    private var platformAec: AcousticEchoCanceler? = null
    private var platformAgc: AutomaticGainControl? = null
    private var previousAudioMode: Int? = null
    private var raisedCallVolume = false
    private var previousCallVolume: Int? = null
    private var deviceCallback: AudioDeviceCallback? = null
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    init {
        registerDeviceCallback()
    }

    /** Snapshot of the currently active processing chain. */
    fun activeProcessingChain(): VoiceProcessingChain = currentChain

    override fun statusNotice(): String? = currentChain.fallbackNotice

    override suspend fun detectCapture(): VoiceCaptureKind = withContext(Dispatchers.Default) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Phone/tablet: mic feature present → prompt later on Mic tap. TV remotes need permission too.
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) || isTv) {
                return@withContext VoiceCaptureKind.OPEN
            }
            android.util.Log.i(TAG, "detectCapture: no RECORD_AUDIO and no microphone feature")
            return@withContext VoiceCaptureKind.NONE
        }
        val inputs = inputInfos()
        android.util.Log.i(
            TAG,
            "detectCapture inputs=${inputs.map { "${it.id}:${typeName(it.type)}:${it.productName}" }}",
        )
        val remote = inputs.firstOrNull { it.type in REMOTE_MIC_TYPES }
        if (remote != null) {
            android.util.Log.i(TAG, "detectCapture: remote/BLE mic ${labelOf(remote, true)}")
            return@withContext VoiceCaptureKind.OPEN
        }
        val headset = inputs.firstOrNull { it.type in HEADSET_INPUT_TYPES }
        if (headset != null) return@withContext VoiceCaptureKind.OPEN
        val other = inputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        if (other != null) return@withContext VoiceCaptureKind.OPEN
        // Probe whether AudioRecord can open (TV may list no devices yet still capture via remote path).
        if (openCaptureRecord(VoicePcm.frameSamples)?.also { (r, _) -> r.release() } != null) {
            return@withContext VoiceCaptureKind.OPEN
        }
        fallbackCapture()
    }

    private fun fallbackCapture(): VoiceCaptureKind {
        if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return VoiceCaptureKind.OPEN
        }
        android.util.Log.w(TAG, "detectCapture: No microphone available")
        return VoiceCaptureKind.NONE
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnectMedia()
        if (params.isDiscordLobby) {
            val ok = discordEngine.connect(params)
            if (!ok) return false
            activeDiscord = true
        } else {
            activeDiscord = false
        }
        val media = VoiceMediaSession(scope) { _, pcm -> writePlayback(pcm) }
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
            if (aiNoiseSuppressionPref) {
                ensureDeepFilterNetLocked()
            }
        }
        if (activeDiscord && discordEngine.audioConfig().selfDeaf) {
            media.setSelfDeaf(true)
        }
        session.set(media)
        frameSamples = media.frameSamples
        val chain = rebuildProcessingChain(logChain = true)
        // HARD RULE: never stack the spectral suppressor on top of platform NS or DeepFilterNet.
        media.setNoiseSuppression(chain.spectralNsEnabled)
        startPlayback(media.frameSamples, media.activeChannels)
        // Capture starts only while transmitting so a muted/mic-off join does not hold AudioRecord
        // (green privacy indicator) or request the mic FGS type without need.
        return true
    }

    override fun disconnect() {
        disconnectMedia()
    }

    private fun disconnectMedia() {
        if (activeDiscord) {
            discordEngine.disconnect()
            activeDiscord = false
        }
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        stopCapture()
        synchronized(chainLock) {
            runCatching { deepFilterNet?.close() }
            deepFilterNet = null
        }
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        audioTrack = null
        restoreAudioMode()
        restoreDiscordCallStream()
        runCatching { player?.release() }
        player = null
    }

    override fun setTransmitting(on: Boolean) {
        if (activeDiscord) {
            discordEngine.setTransmitting(on)
        }
        val media = session.get()
        media?.setTransmitting(on)
        syncCapture(media)
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onMicPermissionGranted() {
        syncCapture(session.get())
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

    /** Hold [AudioRecord] only while we are transmitting; release it when muted / mic off. */
    private fun syncCapture(media: VoiceMediaSession?) {
        if (media == null || session.get() !== media) {
            stopCapture()
            return
        }
        if (media.isTransmitting()) {
            if (captureJob?.isActive != true) startCapture(media)
        } else {
            stopCapture()
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        releasePlatformEffects()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        if (session.get()?.isTransmitting() != true) restoreAudioMode()
    }

    override fun setInputGain(gain: Float) {
        discordEngine.setInputGain(gain)
        if (activeDiscord) {
            // SDK input is 0–100%. The 200% slider stop is not applied (it clips).
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
        if (on) {
            synchronized(chainLock) {
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
        session.get()?.setPreferredStereo(stereo)
    }

    private fun ensureDeepFilterNetLocked(): DeepFilterNetProcessor? {
        deepFilterNet?.let { if (it.isAvailable) return it }
        if (dfnLoadFailedReason != null) return null
        val processor = DeepFilterNetProcessor(
            backendFactory = { AndroidNativeDeepFilterBackend.open(app) },
            onFallback = { reason ->
                dfnLoadFailedReason = reason
                rebuildProcessingChain(logChain = true)
            },
        )
        if (!processor.isAvailable) {
            dfnLoadFailedReason = processor.failureReason ?: "DeepFilterNet3 model or native library unavailable"
            processor.close()
            return null
        }
        deepFilterNet = processor
        return processor
    }

    private fun rebuildProcessingChain(logChain: Boolean): VoiceProcessingChain {
        val chain = synchronized(chainLock) {
            if (aiNoiseSuppressionPref && session.get() != null && deepFilterNet == null && dfnLoadFailedReason == null) {
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
            val built = VoiceProcessingChain.build(
                platform = chainPlatform,
                discordLobby = discord,
                echoCancellation = echoCancellationPref,
                autoGainControl = autoGainControlPref,
                noiseSuppression = noiseSuppressionPref,
                aiNoiseSuppression = aiNoiseSuppressionPref,
                deepFilterNetAvailable = dfnAvailable,
                deepFilterNetFailureReason = dfnLoadFailedReason ?: dfn?.failureReason,
            )
            currentChain = built
            session.get()?.setNoiseSuppression(built.spectralNsEnabled)
            if (logChain) {
                val line = built.logLine()
                println(line)
                runCatching { android.util.Log.i(TAG, line) }
            }
            built
        }
        applyPlatformEffects(audioRecord, chain)
        return chain
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

    override fun listInputDevices(): List<VoiceAudioDevice> {
        val devices = mutableListOf(VoiceAudioDevice("", "System default", isDefault = true))
        for (info in inputInfos()) {
            if (info.type == AudioDeviceInfo.TYPE_TELEPHONY) continue
            devices += VoiceAudioDevice(info.id.toString(), labelOf(info, input = true))
        }
        return devices
    }

    override fun listOutputDevices(): List<VoiceAudioDevice> {
        val devices = mutableListOf(VoiceAudioDevice("", "System default", isDefault = true))
        for (info in outputInfos()) {
            devices += VoiceAudioDevice(info.id.toString(), labelOf(info, input = false))
        }
        return devices
    }

    override fun setInputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (activeDiscord) discordEngine.setInputDeviceId(next)
        if (preferredInputId == next) return
        preferredInputId = next
        val media = session.get() ?: return
        if (!applyPreferredInput()) {
            // Recreate capture on the new (or default) route when transmitting.
            stopCapture()
            syncCapture(media)
        }
    }

    override fun setOutputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (activeDiscord) discordEngine.setOutputDeviceId(next)
        if (preferredOutputId == next) return
        preferredOutputId = next
        val media = session.get() ?: return
        if (!applyPreferredOutput()) {
            startPlayback(frameSamples, media.activeChannels)
        }
    }

    override fun playSound(kind: VoiceSound, volume: Float) {
        val res = when (kind) {
            VoiceSound.JOIN -> R.raw.voice_join
            VoiceSound.LEAVE -> R.raw.voice_leave
            VoiceSound.MIC_ON -> R.raw.voice_mic_on
            VoiceSound.MIC_OFF -> R.raw.voice_mic_off
        }
        runCatching {
            player?.release()
            player = MediaPlayer.create(app, res)?.also { mp ->
                mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
                mp.setOnCompletionListener { it.release(); if (player === it) player = null }
                mp.start()
            }
        }
    }

    private fun startCapture(media: VoiceMediaSession) {
        stopCapture()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w(TAG, "startCapture: RECORD_AUDIO not granted")
            return
        }
        val chainAtStart = rebuildProcessingChain(logChain = false)
        media.setNoiseSuppression(chainAtStart.spectralNsEnabled)
        enterCommunicationMode()
        val opened = openCaptureRecord(media.frameSamples, discord = activeDiscord) ?: run {
            android.util.Log.e(TAG, "startCapture: no usable AudioRecord (TV remote mic missing?)")
            restoreAudioMode()
            return
        }
        val (record, sourceName) = opened
        if (!preferInputDevice(record) && !preferredInputId.isNullOrEmpty()) {
            preferredInputId = null
            if (activeDiscord) discordEngine.setInputDeviceId(null)
            onDeviceLost?.invoke(true)
        }
        applyPlatformEffects(record, chainAtStart)
        val actualRate = record.sampleRate
        val channels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            record.channelCount
        } else {
            1
        }
        val deviceLabel = preferredInputLabel(record)
        val format = VoiceCaptureFormat(
            sampleRate = actualRate,
            channels = channels.coerceAtLeast(1),
            pcm16 = true,
            audioSource = sourceName,
            deviceLabel = deviceLabel,
        )
        android.util.Log.i(
            TAG,
            "capture open $format opusRate=${VoicePcm.SAMPLE_RATE} frameSamples=${media.frameSamples} " +
                "bytesPerOpusFrame=${VoicePcm.frameSamples * 2} chain=${chainAtStart.logLine()}",
        )
        if (actualRate != VoicePcm.SAMPLE_RATE) {
            android.util.Log.w(
                TAG,
                "AudioRecord rate $actualRate != Opus ${VoicePcm.SAMPLE_RATE}; streaming resample " +
                    "(stateless per-chunk resample was a usual cause of choppy Android→peer audio)",
            )
        }
        audioRecord = record
        record.startRecording()
        // Dedicated urgent-audio thread: read ~20 ms chunks, stateful resample, exact Opus frames.
        val readSamples = maxOf(actualRate / 50, 320) * format.channels
        val readBuf = ShortArray(readSamples)
        val monoScratch = ShortArray(readBuf.size)
        val resampler = if (actualRate == VoicePcm.SAMPLE_RATE) null else StreamingResampler(actualRate)
        val accumulator = VoicePcm.FrameAccumulator(media.frameSamples)
        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isActive && session.get() === media) {
                val n = record.read(readBuf, 0, readBuf.size)
                when {
                    n < 0 -> {
                        android.util.Log.w(TAG, "AudioRecord.read error $n")
                        break
                    }
                    n == 0 -> continue
                    else -> {
                        val mono = toMono(readBuf, n, format.channels, monoScratch)
                        val at48k = resampler?.push(mono) ?: mono
                        for (frame in accumulator.push(at48k)) {
                            val chain = currentChain
                            val dfn = deepFilterNet
                            val outFrame = if (chain.deepFilterNetEnabled && dfn != null && dfn.isAvailable) {
                                val processed = dfn.processFrame20ms(frame, channels = 1)
                                if (!dfn.isAvailable) {
                                    rebuildProcessingChain(logChain = true)
                                }
                                processed
                            } else {
                                frame
                            }
                            media.offerCapturePcm(outFrame)
                        }
                    }
                }
            }
        }
    }

    /**
     * Open an [AudioRecord] that can actually capture.
     * Phones: prefer VOICE_COMMUNICATION so platform AEC/NS/AGC attach; fall back to MIC / VOICE_RECOGNITION.
     * TVs: prefer VOICE_RECOGNITION / DEFAULT so remote / BLE headset mics are reachable.
     */
    private fun openCaptureRecord(frameSamples: Int, discord: Boolean = false): Pair<AudioRecord, String>? {
        val sources = if (isTv) {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            )
        }
        // Prefer 48 kHz. Discord skips 8 kHz unless nothing wider opens — upsampling 8 kHz sounds robotic.
        val rates = if (discord) {
            DiscordSdkAudio.preferredCaptureRates()
        } else {
            intArrayOf(VoicePcm.SAMPLE_RATE, 44_100, 16_000, DiscordSdkAudio.NARROWBAND_FALLBACK_RATE)
        }
        val opened = openCaptureRecordAt(sources, rates, frameSamples)
        if (opened != null || !discord) return opened
        return openCaptureRecordAt(
            sources,
            intArrayOf(DiscordSdkAudio.NARROWBAND_FALLBACK_RATE),
            frameSamples,
        )
    }

    private fun openCaptureRecordAt(
        sources: List<Pair<Int, String>>,
        rates: IntArray,
        frameSamples: Int,
    ): Pair<AudioRecord, String>? {
        for ((source, name) in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) continue
                // ≥4× min + two 20 ms frames so reads don't underrun under load.
                val bufBytes = maxOf(minBuf * 4, frameSamples * 2 * 8)
                val record = runCatching {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes,
                    )
                }.getOrNull() ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                android.util.Log.i(TAG, "AudioRecord ok source=$name requestedRate=$rate actualRate=${record.sampleRate} buf=$bufBytes")
                return record to name
            }
        }
        return null
    }

    private fun toMono(interleaved: ShortArray, sampleCount: Int, channels: Int, scratch: ShortArray): ShortArray {
        if (channels <= 1) {
            return interleaved.copyOf(sampleCount)
        }
        val frames = sampleCount / channels
        val out = if (scratch.size >= frames) scratch else ShortArray(frames)
        var i = 0
        var o = 0
        while (o < frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i + c].toInt()
            out[o] = (sum / channels).toShort()
            i += channels
            o++
        }
        return out.copyOf(frames)
    }

    /**
     * Attaches Android platform `NoiseSuppressor`, `AcousticEchoCanceler`, and `AutomaticGainControl`
     * strictly according to [chain]:
     * - When DeepFilterNet3 is active (`chain.deepFilterNetEnabled == true`), `chain.platformNsEnabled`
     *   is `false`, so the platform `NoiseSuppressor` is explicitly disabled/released while AEC and AGC
     *   remain governed by their own toggles.
     */
    private fun applyPlatformEffects(record: AudioRecord?, chain: VoiceProcessingChain = currentChain) {
        releasePlatformEffects()
        if (record == null) return
        val ns = chain.platformNsEnabled
        val aec = chain.platformAecEnabled
        val agc = chain.platformAgcEnabled
        if (!ns && !aec && !agc) return
        val sessionId = record.audioSessionId
        if (sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE) return
        if (ns && NoiseSuppressor.isAvailable()) {
            platformNs = runCatching { NoiseSuppressor.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (aec && AcousticEchoCanceler.isAvailable()) {
            platformAec = runCatching { AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (agc && AutomaticGainControl.isAvailable()) {
            platformAgc = runCatching { AutomaticGainControl.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        android.util.Log.i(
            TAG,
            "platform effects ns=${platformNs != null} aec=${platformAec != null} agc=${platformAgc != null} (${chain.logLine()})",
        )
    }

    private fun releasePlatformEffects() {
        runCatching { platformNs?.enabled = false; platformNs?.release() }
        runCatching { platformAec?.enabled = false; platformAec?.release() }
        runCatching { platformAgc?.enabled = false; platformAgc?.release() }
        platformNs = null
        platformAec = null
        platformAgc = null
    }

    private fun enterCommunicationMode() {
        if (isTv) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (previousAudioMode == null) previousAudioMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    private fun restoreAudioMode() {
        val prev = previousAudioMode ?: return
        previousAudioMode = null
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.mode = prev }
    }

    /**
     * Voice-call stream at 0 makes USAGE_VOICE_COMMUNICATION silent. Raise it only in that case,
     * and never while a phone call owns the stream. STREAM_MUSIC (the video slider) is not touched.
     */
    private fun ensureDiscordCallStreamAudible() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (am.mode == AudioManager.MODE_IN_CALL) return
        val current = runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull() ?: return
        if (current > 0) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        if (runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0) }.isFailure) return
        previousCallVolume = current
        raisedCallVolume = true
    }

    private fun restoreDiscordCallStream() {
        if (!raisedCallVolume) return
        raisedCallVolume = false
        val previous = previousCallVolume ?: return
        previousCallVolume = null
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (am.mode == AudioManager.MODE_IN_CALL) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previous, 0) }
    }

    private fun preferredInputLabel(record: AudioRecord): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "default"
        val preferred = runCatching { record.preferredDevice }.getOrNull()
        if (preferred != null) return labelOf(preferred, input = true)
        val id = preferredInputId?.toIntOrNull()
        val info = id?.let { inputInfos().firstOrNull { d -> d.id == it } }
        return info?.let { labelOf(it, input = true) } ?: "system default"
    }

    private fun startPlayback(frameSamples: Int, channels: Int = 1) {
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        audioTrack = null
        val sampleRate = 48_000
        val outMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            outMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        // Relayed voice stays on the media stream (TV speakers, and phones where the call stream is 0).
        // Discord lobby voice on a phone uses the voice-call stream so the video volume slider,
        // which sets STREAM_MUSIC, does not scale voice. TV keeps media: the call stream is often silent.
        val shareVideo = !activeDiscord || DiscordSdkAudio.playbackSharesVideoStream(isTv)
        if (!shareVideo) ensureDiscordCallStreamAudible()
        val chCount = if (channels >= 2) 2 else 1
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (shareVideo) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(outMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // ~200 ms (10 × 20 ms frames) so paced jitter-buffer output does not underrun.
            .setBufferSizeInBytes(maxOf(minBuf * 4, frameSamples * chCount * 2 * 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (!preferOutputDevice(track) && !preferredOutputId.isNullOrEmpty()) {
            preferredOutputId = null
            if (activeDiscord) discordEngine.setOutputDeviceId(null)
            onDeviceLost?.invoke(false)
        }
        audioTrack = track
        track.play()
    }

    private fun writePlayback(pcm: ShortArray) {
        val track = audioTrack ?: return
        runCatching { track.write(pcm, 0, pcm.size) }
    }

    private fun applyPreferredInput(): Boolean {
        val record = audioRecord ?: return false
        return preferInputDevice(record)
    }

    private fun applyPreferredOutput(): Boolean {
        val track = audioTrack ?: return false
        return preferOutputDevice(track)
    }

    private fun preferInputDevice(record: AudioRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return preferredInputId.isNullOrEmpty()
        val id = preferredInputId?.toIntOrNull()
        if (id != null) {
            val device = inputInfos().firstOrNull { it.id == id } ?: return false
            return runCatching { record.preferredDevice = device; true }.getOrDefault(false)
        }
        // TV relayed voice auto-selects a remote mic. Discord "system default" stays the OS default.
        if (isTv && !activeDiscord) {
            val remote = inputInfos().firstOrNull { it.type in REMOTE_MIC_TYPES }
                ?: inputInfos().firstOrNull { it.type in HEADSET_INPUT_TYPES }
            if (remote != null) {
                android.util.Log.i(TAG, "preferInputDevice: auto remote ${labelOf(remote, true)}")
                return runCatching { record.preferredDevice = remote; true }.getOrDefault(false)
            }
        }
        runCatching { record.preferredDevice = null }
        return true
    }

    private fun preferOutputDevice(track: AudioTrack): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return preferredOutputId.isNullOrEmpty()
        val id = preferredOutputId?.toIntOrNull()
        if (id == null) {
            runCatching { track.preferredDevice = null }
            return true
        }
        val device = outputInfos().firstOrNull { it.id == id } ?: return false
        return runCatching { track.preferredDevice = device; true }.getOrDefault(false)
    }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = Unit

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val removedIds = removedDevices.map { it.id.toString() }.toSet()
                val media = session.get()
                if (preferredInputId != null && preferredInputId in removedIds) {
                    preferredInputId = null
                    if (activeDiscord) discordEngine.setInputDeviceId(null)
                    onDeviceLost?.invoke(true)
                    if (media != null) {
                        stopCapture()
                        syncCapture(media)
                    }
                }
                if (preferredOutputId != null && preferredOutputId in removedIds) {
                    preferredOutputId = null
                    if (activeDiscord) discordEngine.setOutputDeviceId(null)
                    onDeviceLost?.invoke(false)
                    if (media != null) startPlayback(frameSamples, media.activeChannels)
                }
            }
        }
        deviceCallback = callback
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun inputInfos(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val am = app.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    private fun outputInfos(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val am = app.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    private fun labelOf(info: AudioDeviceInfo, input: Boolean): String {
        val typeLabel = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            else -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote"
                else -> if (input) "Microphone" else "Speaker"
            }
        }
        val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals(typeLabel, ignoreCase = true) }
        } else {
            null
        }
        return if (product != null) "$typeLabel ($product)" else typeLabel
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            else -> "type$type"
        }
    }

    companion object {
        private const val TAG = "AndroidVoice"

        private val HEADSET_INPUT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        /** TV remote / assistant mics often appear as BLE headset, remote submix, or HDMI. */
        private val REMOTE_MIC_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
            }
            add(AudioDeviceInfo.TYPE_HDMI)
            add(AudioDeviceInfo.TYPE_HDMI_ARC)
            add(AudioDeviceInfo.TYPE_DOCK)
        }
    }
}

/**
 * Android / Android TV `libDF` JNI backend wrapping [NativeDeepFilterNet] (`libdf.so` +
 * `R.raw.deep_filter_mobile_model`).
 *
 * Uses [Dispatchers.Unconfined] so `NativeDeepFilterNet` loads the model and initializes its native
 * handle synchronously inside `open(...)`, allowing immediate verification via `setAttenuationLimit`.
 */
internal class AndroidNativeDeepFilterBackend private constructor(
    private val nativeDfn: NativeDeepFilterNet,
) : DeepFilterBackend {
    override val name: String = "deepfilternet3-android-jni"

    private val directHopBuf: ByteBuffer =
        ByteBuffer.allocateDirect(DeepFilterNetProcessor.HOP_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)

    override fun processHop480(pcm: ShortArray, offset: Int): Float {
        if (pcm.size - offset < DeepFilterNetProcessor.HOP_SAMPLES) return -15f
        synchronized(directHopBuf) {
            directHopBuf.clear()
            directHopBuf.asShortBuffer().put(pcm, offset, DeepFilterNetProcessor.HOP_SAMPLES)
            val snr = nativeDfn.processFrame(directHopBuf)
            if (snr <= -999f) throw IllegalStateException("NativeDeepFilterNet uninitialized")
            directHopBuf.rewind()
            directHopBuf.asShortBuffer().get(pcm, offset, DeepFilterNetProcessor.HOP_SAMPLES)
            return snr
        }
    }

    override fun setAttenuationLimitDb(db: Float) {
        runCatching { nativeDfn.setAttenuationLimit(db.coerceIn(0f, 100f)) }
    }

    override fun setPostFilterBeta(beta: Float) {
        runCatching { nativeDfn.setPostFilterBeta(beta.coerceAtLeast(0f)) }
    }

    override fun close() {
        runCatching { nativeDfn.release() }
    }

    companion object {
        private val synchronousDispatchers = object : DispatcherProvider {
            override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
        }

        fun open(context: Context): AndroidNativeDeepFilterBackend? {
            return runCatching {
                val dfn = NativeDeepFilterNet(
                    context.applicationContext,
                    DeepFilterNetProcessor.DEFAULT_ATTENUATION_LIMIT_DB,
                    synchronousDispatchers,
                )
                if (!dfn.setAttenuationLimit(DeepFilterNetProcessor.DEFAULT_ATTENUATION_LIMIT_DB)) {
                    runCatching { dfn.release() }
                    return null
                }
                dfn.setPostFilterBeta(DeepFilterNetProcessor.DEFAULT_POST_FILTER_BETA)
                AndroidNativeDeepFilterBackend(dfn)
            }.onFailure {
                runCatching {
                    android.util.Log.w("AndroidVoice", "DeepFilterNet3 native load failed: ${it.message}")
                }
            }.getOrNull()
        }
    }
}
