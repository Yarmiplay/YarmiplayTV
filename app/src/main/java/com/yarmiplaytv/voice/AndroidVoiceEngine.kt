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
import com.yarmiplaytv.R
import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Android capture/playback + `yv1` Opus media (UDP, TLS TCP fallback).
 * Exposes built-in / wired / Bluetooth / USB routes in settings; defaults to the system device.
 * Unplug falls back to the system default without leaving voice.
 */
class AndroidVoiceEngine(
    context: Context,
    private val isTv: Boolean,
) : VoiceEngine {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    private var noiseSuppression = true
    @Volatile private var inputGain: Float = 1f
    @Volatile private var outputGain: Float = 1f
    @Volatile private var playbackResamplerRate: Int = VoicePcm.SAMPLE_RATE
    @Volatile private var playbackResampler: StreamingResampler? = null
    private var platformNs: NoiseSuppressor? = null
    private var platformAec: AcousticEchoCanceler? = null
    private var platformAgc: AutomaticGainControl? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var communicationDeviceSet = false
    private var raisedCallVolume = false
    private var previousCallVolume: Int? = null
    private var deviceCallback: AudioDeviceCallback? = null
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    init {
        registerDeviceCallback()
    }

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
        if (!media.connect(params)) {
            if (activeDiscord) {
                discordEngine.disconnect()
                activeDiscord = false
            }
            return false
        }
        if (activeDiscord) {
            // Platform WebRTC NS/AEC/AGC is the SDK's one processor. The spectral gate stays off.
            media.setNoiseSuppression(false)
            if (discordEngine.audioConfig().selfDeaf) media.setSelfDeaf(true)
        } else {
            media.setNoiseSuppression(noiseSuppression)
        }
        // Makeup stays on until platform AGC actually attaches in [applyPlatformEffects].
        media.setAgcStageActive(false)
        media.setInputGain(if (activeDiscord) inputGain.coerceIn(0f, 2f) else inputGain)
        media.setOutputGain(if (activeDiscord) discordEngine.outputAmplitude() else outputGain)
        session.set(media)
        frameSamples = media.frameSamples
        startPlayback(media.frameSamples)
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

    override fun setMicTest(active: Boolean) {
        if (activeDiscord) {
            discordEngine.setMicTest(active)
        }
        if (active) {
            val existing = session.get()
            if (existing != null) {
                existing.setMicTest(true)
                syncCapture(existing)
                return
            }
            val media = VoiceMediaSession(scope) { _, pcm -> writePlayback(pcm) }
            media.startStandaloneMicTest()
            media.setNoiseSuppression(noiseSuppression)
            media.setInputGain(inputGain)
            media.setOutputGain(outputGain)
            session.set(media)
            frameSamples = media.frameSamples
            startPlayback(media.frameSamples)
            syncCapture(media)
        } else {
            val existing = session.get() ?: return
            if (existing.isStandaloneLoopback) {
                disconnectMedia()
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

    /** Hold [AudioRecord] only while we are transmitting or running a mic test; release it when muted / mic off. */
    private fun syncCapture(media: VoiceMediaSession?) {
        if (media == null || session.get() !== media) {
            stopCapture()
            return
        }
        if (media.shouldCaptureAudio()) {
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
        // Discord playback keeps communication mode and the loudspeaker while the
        // call is up. Restoring here would drop the other person back to the earpiece
        // the moment the local mic closes. Full disconnect restores the previous mode.
        if (!activeDiscord && session.get()?.shouldCaptureAudio() != true) restoreAudioMode()
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
        noiseSuppression = on
        if (activeDiscord) {
            discordEngine.setNoiseSuppression(on)
            session.get()?.setNoiseSuppression(false)
            applyPlatformEffects(audioRecord)
            return
        }
        applyPlatformEffects(audioRecord)
        // Enforce single noise-suppression stage: only enable software NS if platform WebRTC NS is not active.
        session.get()?.setNoiseSuppression(on && platformNs == null)
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
        if (session.get() == null) return
        if (!applyPreferredOutput()) {
            startPlayback(frameSamples)
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
        media.setNoiseSuppression(if (activeDiscord) false else noiseSuppression)
        enterCommunicationMode()
        val opened = openCaptureRecord(media.frameSamples, discord = activeDiscord) ?: run {
            android.util.Log.e(TAG, "startCapture: no usable AudioRecord (TV remote mic missing?)")
            if (!activeDiscord) restoreAudioMode()
            return
        }
        val (record, sourceName) = opened
        if (!preferInputDevice(record) && !preferredInputId.isNullOrEmpty()) {
            preferredInputId = null
            if (activeDiscord) discordEngine.setInputDeviceId(null)
            onDeviceLost?.invoke(true)
        }
        applyPlatformEffects(record)
        // Enforce single NS stage: if platform WebRTC NoiseSuppressor attached, disable software spectral NS.
        media.setNoiseSuppression(!activeDiscord && noiseSuppression && platformNs == null)
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
                "bytesPerOpusFrame=${VoicePcm.frameSamples * 2} ns=$noiseSuppression",
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
        val readSamples = maxOf(actualRate / 50, 160) * format.channels
        val readBuf = ShortArray(readSamples)
        val monoScratch = ShortArray(readBuf.size)
        val resampler = if (actualRate == VoicePcm.SAMPLE_RATE) null else StreamingResampler(actualRate, VoicePcm.SAMPLE_RATE)
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
                            media.offerCapturePcm(frame)
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
        // Native rate first so the HAL does not resample, then 48 kHz. Discord still skips 8 kHz
        // unless nothing wider opens — upsampling 8 kHz sounds robotic.
        val rates = captureRates(discord)
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
                val frameBytesAtRate = maxOf(rate / 50, 160) * 2
                val lowBuf = VoiceDeviceClock.lowLatencyBufferBytes(minBuf, frameBytesAtRate, nativeBurstFrames())
                val fallbackBuf = maxOf(minBuf * 2, frameBytesAtRate * 4, frameSamples * 2 * 4)
                // Smallest buffer the HAL accepts is the AAudio fast-capture path (API 26+).
                // A rejected size falls back to a 4-frame buffer on a new AudioRecord.
                val candidateBuffers = intArrayOf(lowBuf, fallbackBuf).distinct()
                for (bufBytes in candidateBuffers) {
                    val record = runCatching {
                        val format = AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                        AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(bufBytes)
                            .build()
                    }.getOrNull() ?: continue
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        record.release()
                        continue
                    }
                    android.util.Log.i(TAG, "AudioRecord ok source=$name requestedRate=$rate actualRate=${record.sampleRate} buf=$bufBytes")
                    return record to name
                }
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

    private fun applyPlatformEffects(record: AudioRecord?) {
        releasePlatformEffects()
        if (record == null) return
        val sdk = if (activeDiscord) discordEngine.audioConfig() else null
        // Relayed path: the one noise-suppression setting gates all three effects, as before.
        // Discord path: each WebRTC processor follows the SDK (NS is the user toggle; AEC and AGC
        // default on and stay on when NS is turned off).
        val ns = sdk?.noiseSuppression ?: noiseSuppression
        val aec = sdk?.echoCancellation ?: noiseSuppression
        val agc = sdk?.automaticGainControl ?: noiseSuppression
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
        // Platform AGC is the one auto-gain stage. Fixed capture makeup stays off while it is attached.
        session.get()?.setAgcStageActive(platformAgc != null)
        android.util.Log.i(
            TAG,
            "platform effects ns=${platformNs != null} aec=${platformAec != null} agc=${platformAgc != null}",
        )
    }

    private fun releasePlatformEffects() {
        runCatching { platformNs?.release() }
        runCatching { platformAec?.release() }
        runCatching { platformAgc?.release() }
        platformNs = null
        platformAec = null
        platformAgc = null
        session.get()?.setAgcStageActive(false)
    }

    private fun enterCommunicationMode() {
        if (isTv) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (previousAudioMode == null) previousAudioMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    private fun restoreAudioMode() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSet) {
                communicationDeviceSet = false
                runCatching { am.clearCommunicationDevice() }
            }
            val prevSpeaker = previousSpeakerphoneOn
            if (prevSpeaker != null) {
                previousSpeakerphoneOn = null
                @Suppress("DEPRECATION")
                runCatching { am.isSpeakerphoneOn = prevSpeaker }
            }
        }
        val prev = previousAudioMode ?: return
        previousAudioMode = null
        if (am != null) runCatching { am.mode = prev }
    }

    /**
     * Voice-call stream at its minimum step (1, not 0) makes USAGE_VOICE_COMMUNICATION
     * inaudible. Raise it to match media volume or at least 80% of max. STREAM_MUSIC
     * (the video slider) is not touched. Never while a phone call owns the stream.
     */
    private fun ensureDiscordCallStreamAudible() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (am.mode == AudioManager.MODE_IN_CALL) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (runCatching { am.isStreamMute(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(false)) {
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            }
        }
        val current = runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull() ?: return
        val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val music = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault((maxMusic * 0.7f).toInt())
        val target = DiscordSdkAudio.audibleCallStreamVolume(current, maxCall, music, maxMusic)
        if (target == current) return
        if (runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0) }.isFailure) return
        if (!raisedCallVolume) {
            previousCallVolume = current
            raisedCallVolume = true
        }
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

    private fun startPlayback(frameSamples: Int) {
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        audioTrack = null
        playbackResampler = null
        playbackResamplerRate = VoicePcm.SAMPLE_RATE
        // Relayed voice stays on the media stream (TV speakers, and phones where the call stream is 0).
        // Discord lobby voice on a phone uses the voice-call stream so the video volume slider,
        // which sets STREAM_MUSIC, does not scale voice. TV keeps media: the call stream is often silent.
        val shareVideo = !activeDiscord || DiscordSdkAudio.playbackSharesVideoStream(isTv)
        if (!shareVideo) {
            enterCommunicationMode()
            ensureDiscordCallStreamAudible()
        }
        val usage = if (shareVideo) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION
        val nativeRate = nativeOutputRate()
        val rates = if (nativeRate == VoicePcm.SAMPLE_RATE) {
            intArrayOf(VoicePcm.SAMPLE_RATE)
        } else {
            intArrayOf(nativeRate, VoicePcm.SAMPLE_RATE)
        }
        var track: AudioTrack? = null
        var openedRate = VoicePcm.SAMPLE_RATE
        val burst = nativeBurstFrames()
        for (rate in rates) {
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) continue
            val frameBytes = maxOf(rate / 50, 160) * 2
            val lowBuf = VoiceDeviceClock.lowLatencyBufferBytes(minBuf, frameBytes, burst)
            val built = buildAudioTrack(usage, rate, lowBuf, lowLatency = true)
            if (built != null) {
                track = built
                openedRate = rate
                break
            }
        }
        if (track == null) {
            val minBuf = AudioTrack.getMinBufferSize(
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return
            val fallbackBuf = maxOf(minBuf * 2, frameSamples * 2 * 4)
            track = buildAudioTrack(usage, VoicePcm.SAMPLE_RATE, fallbackBuf, lowLatency = false) ?: return
            openedRate = VoicePcm.SAMPLE_RATE
        }
        val opened = track ?: return
        if (!preferOutputDevice(opened) && !preferredOutputId.isNullOrEmpty()) {
            preferredOutputId = null
            if (activeDiscord) discordEngine.setOutputDeviceId(null)
            onDeviceLost?.invoke(false)
        }
        // One resampler, and only when the track is not already the Opus clock.
        // The track was opened at the device native rate when that succeeded, so AudioFlinger
        // does not resample again underneath this.
        val actualTrackRate = openedRate.takeIf { it > 0 } ?: VoicePcm.SAMPLE_RATE
        playbackResamplerRate = actualTrackRate
        playbackResampler = if (actualTrackRate == VoicePcm.SAMPLE_RATE) {
            null
        } else {
            StreamingResampler(VoicePcm.SAMPLE_RATE, actualTrackRate)
        }
        audioTrack = opened
        opened.play()
    }

    /**
     * [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY] selects AAudio on Android 8.1+ and falls back
     * to the legacy path when the HAL rejects it. Oboe is the NDK wrapper over that same
     * AAudio API; this process stays on the SDK builders.
     */
    private fun buildAudioTrack(usage: Int, sampleRate: Int, bufferBytes: Int, lowLatency: Boolean): AudioTrack? {
        val track = runCatching {
            val attrsBuilder = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            if (lowLatency && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                attrsBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrsBuilder.build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        }.getOrNull() ?: return null
        if (track.state == AudioTrack.STATE_INITIALIZED) return track
        runCatching { track.release() }
        return null
    }

    /** HAL output rate, or 48 kHz when the device does not report one we can open. */
    private fun nativeOutputRate(): Int {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return VoicePcm.SAMPLE_RATE
        val reported = runCatching {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: return VoicePcm.SAMPLE_RATE
        return VoiceDeviceClock.openRate(reported)
    }

    /** HAL burst length in frames. 0 when the device does not report one. */
    private fun nativeBurstFrames(): Int {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        return runCatching {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        }.getOrNull()?.coerceIn(32, 4_096) ?: 0
    }

    private fun captureRates(discord: Boolean): IntArray {
        val preferred = if (discord) {
            DiscordSdkAudio.preferredCaptureRates()
        } else {
            intArrayOf(VoicePcm.SAMPLE_RATE, 44_100, 16_000, DiscordSdkAudio.NARROWBAND_FALLBACK_RATE)
        }
        val native = nativeOutputRate()
        val ordered = ArrayList<Int>(preferred.size + 1)
        if (native != VoicePcm.SAMPLE_RATE) ordered += native
        for (rate in preferred) {
            if (rate !in ordered) ordered += rate
        }
        return ordered.toIntArray()
    }

    private fun writePlayback(pcm: ShortArray) {
        val track = audioTrack ?: return
        val outPcm = playbackResampler?.push(pcm) ?: pcm
        if (outPcm.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.write(outPcm, 0, outPcm.size, AudioTrack.WRITE_NON_BLOCKING) }
        } else {
            runCatching { track.write(outPcm, 0, outPcm.size) }
        }
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
        val outputs = outputInfos()
        val id = preferredOutputId?.toIntOrNull()
        if (id != null) {
            val device = outputs.firstOrNull { it.id == id } ?: return false
            val ok = runCatching { track.preferredDevice = device; true }.getOrDefault(false)
            if (activeDiscord) applyCommunicationRoute(device)
            return ok
        }
        val external = outputs.firstOrNull { it.type in EXTERNAL_OUTPUT_TYPES }
        if (activeDiscord && DiscordSdkAudio.playbackUsesBuiltInSpeaker(
                isTv = isTv,
                userSelectedOutput = false,
                externalOutputConnected = external != null,
            )
        ) {
            val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                runCatching { track.preferredDevice = speaker }
                applyCommunicationRoute(speaker)
                return true
            }
        }
        runCatching { track.preferredDevice = null }
        if (activeDiscord) applyCommunicationRoute(external)
        return true
    }

    /** Loudspeaker unless the chosen device is something else. Restored in [restoreAudioMode]. */
    private fun applyCommunicationRoute(target: AudioDeviceInfo?) {
        if (isTv || !activeDiscord) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val wantSpeaker = target?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && target != null) {
            val applied = runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)
            if (applied) communicationDeviceSet = true
        }
        @Suppress("DEPRECATION")
        runCatching {
            if (previousSpeakerphoneOn == null) previousSpeakerphoneOn = am.isSpeakerphoneOn
            if (am.isSpeakerphoneOn != wantSpeaker) am.isSpeakerphoneOn = wantSpeaker
        }
    }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val track = audioTrack
                if (track != null && preferredOutputId == null && activeDiscord) {
                    preferOutputDevice(track)
                }
            }

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
                    if (media != null) startPlayback(frameSamples)
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

        /** Wired, Bluetooth, USB, or HDMI. Lobby voice follows these instead of forcing the speaker. */
        private val EXTERNAL_OUTPUT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_DEVICE)
            add(AudioDeviceInfo.TYPE_HDMI)
            add(AudioDeviceInfo.TYPE_HDMI_ARC)
            add(AudioDeviceInfo.TYPE_DOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
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
