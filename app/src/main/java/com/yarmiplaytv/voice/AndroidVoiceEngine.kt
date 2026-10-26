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
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var frameSamples: Int = 960
    private var noiseSuppression = true
    private var platformNs: NoiseSuppressor? = null
    private var platformAec: AcousticEchoCanceler? = null
    private var platformAgc: AutomaticGainControl? = null
    private var previousAudioMode: Int? = null
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
        val media = VoiceMediaSession(scope) { _, pcm -> writePlayback(pcm) }
        if (!media.connect(params)) return false
        media.setNoiseSuppression(noiseSuppression)
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
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        stopCapture()
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        audioTrack = null
        restoreAudioMode()
        runCatching { player?.release() }
        player = null
    }

    override fun setTransmitting(on: Boolean) {
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
        val media = session.get()
        media?.setSelfMuted(muted)
        syncCapture(media)
    }

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
        session.get()?.setInputGain(gain)
    }

    override fun setOutputGain(gain: Float) {
        session.get()?.setOutputGain(gain)
    }

    override fun setNoiseSuppression(on: Boolean) {
        noiseSuppression = on
        session.get()?.setNoiseSuppression(on)
        applyPlatformEffects(audioRecord)
    }

    override fun setPeerMuted(participantId: String, muted: Boolean) {
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerMuted(id, muted)
    }

    override fun setPeerVolume(participantId: String, volume: Float) {
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerVolume(id, volume)
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
        media.setNoiseSuppression(noiseSuppression)
        enterCommunicationMode()
        val opened = openCaptureRecord(media.frameSamples) ?: run {
            android.util.Log.e(TAG, "startCapture: no usable AudioRecord (TV remote mic missing?)")
            restoreAudioMode()
            return
        }
        val (record, sourceName) = opened
        if (!preferInputDevice(record) && !preferredInputId.isNullOrEmpty()) {
            preferredInputId = null
            onDeviceLost?.invoke(true)
        }
        applyPlatformEffects(record)
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
    private fun openCaptureRecord(frameSamples: Int): Pair<AudioRecord, String>? {
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
        // Prefer 48 kHz; also try 44.1 / 16 kHz hardware rates and resample with StreamingResampler.
        val rates = intArrayOf(VoicePcm.SAMPLE_RATE, 44_100, 16_000, 8_000)
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

    private fun applyPlatformEffects(record: AudioRecord?) {
        releasePlatformEffects()
        if (record == null || !noiseSuppression) return
        val sessionId = record.audioSessionId
        if (sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE) return
        if (NoiseSuppressor.isAvailable()) {
            platformNs = runCatching { NoiseSuppressor.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (AcousticEchoCanceler.isAvailable()) {
            platformAec = runCatching { AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            platformAgc = runCatching { AutomaticGainControl.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
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
        val sampleRate = 48_000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        // USAGE_MEDIA follows the same volume stream as video/music and TV speakers; VOICE_COMMUNICATION
        // often stays silent on TVs and phones when the call stream is at zero while media is loud.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // ~200 ms (10 × 20 ms frames) so paced jitter-buffer output does not underrun.
            .setBufferSizeInBytes(maxOf(minBuf * 4, frameSamples * 2 * 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (!preferOutputDevice(track) && !preferredOutputId.isNullOrEmpty()) {
            preferredOutputId = null
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
        // TV: auto-select a remote / BLE / HDMI mic when the user hasn't picked one.
        if (isTv) {
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
                    onDeviceLost?.invoke(true)
                    if (media != null) {
                        stopCapture()
                        syncCapture(media)
                    }
                }
                if (preferredOutputId != null && preferredOutputId in removedIds) {
                    preferredOutputId = null
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
