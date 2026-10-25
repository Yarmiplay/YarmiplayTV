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
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var deviceCallback: AudioDeviceCallback? = null
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    init {
        registerDeviceCallback()
    }

    override suspend fun detectCapture(): VoiceCaptureKind = withContext(Dispatchers.Default) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                return@withContext VoiceCaptureKind.OPEN
            }
        }
        val inputs = inputInfos()
        val headset = inputs.firstOrNull { it.type in HEADSET_INPUT_TYPES }
        if (headset != null) {
            return@withContext VoiceCaptureKind.OPEN
        }
        val other = inputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        if (other != null) {
            return@withContext VoiceCaptureKind.OPEN
        }
        fallbackCapture()
    }

    private fun fallbackCapture(): VoiceCaptureKind {
        if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return VoiceCaptureKind.OPEN
        }
        return VoiceCaptureKind.NONE
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnectMedia()
        val media = VoiceMediaSession(scope) { _, pcm -> writePlayback(pcm) }
        if (!media.connect(params)) return false
        session.set(media)
        frameSamples = media.frameSamples
        startPlayback(media.frameSamples)
        startCapture(media)
        return true
    }

    override fun disconnect() {
        disconnectMedia()
    }

    private fun disconnectMedia() {
        captureJob?.cancel()
        captureJob = null
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        audioTrack = null
        runCatching { player?.release() }
        player = null
    }

    override fun setTransmitting(on: Boolean) {
        session.get()?.setTransmitting(on)
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onMicPermissionGranted() {
        val media = session.get() ?: return
        if (captureJob?.isActive == true) return
        startCapture(media)
    }

    override fun setSelfMuted(muted: Boolean) {
        session.get()?.setSelfMuted(muted)
    }

    override fun setInputGain(gain: Float) {
        session.get()?.setInputGain(gain)
    }

    override fun setOutputGain(gain: Float) {
        session.get()?.setOutputGain(gain)
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
            // Recreate capture on the new (or default) route.
            startCapture(media)
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
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sampleRate = 48_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, media.frameSamples * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        if (!preferInputDevice(record) && !preferredInputId.isNullOrEmpty()) {
            preferredInputId = null
            onDeviceLost?.invoke(true)
        }
        audioRecord = record
        record.startRecording()
        val frame = ShortArray(media.frameSamples)
        captureJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() === media) {
                val n = record.read(frame, 0, frame.size)
                if (n == frame.size) {
                    media.offerCapturePcm(frame)
                } else if (n < 0) {
                    break
                }
            }
        }
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
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(maxOf(minBuf, frameSamples * 2 * 4))
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
        if (id == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { record.preferredDevice = null }
            }
            return true
        }
        val device = inputInfos().firstOrNull { it.id == id } ?: return false
        return runCatching { record.preferredDevice = device; true }.getOrDefault(false)
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
                    if (media != null) startCapture(media)
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

    companion object {
        private val HEADSET_INPUT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
