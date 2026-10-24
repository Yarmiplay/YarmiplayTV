package com.yarmiplaytv.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
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
 * Prefers a wired/BT headset input when present; TV without a mic stays listen-only.
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

    override suspend fun detectCapture(): VoiceCaptureKind = withContext(Dispatchers.Default) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Still allow join as listen-only until the user grants; UI may request later.
            if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                return@withContext VoiceCaptureKind.OPEN
            }
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return@withContext fallbackCapture()
        val inputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } else {
            emptyList()
        }
        val headset = inputs.firstOrNull { it.type in HEADSET_INPUT_TYPES }
        if (headset != null) {
            preferredInputId = headset.id.toString()
            return@withContext VoiceCaptureKind.OPEN
        }
        val other = inputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        if (other != null) {
            preferredInputId = other.id.toString()
            return@withContext VoiceCaptureKind.OPEN
        }
        fallbackCapture()
    }

    private fun fallbackCapture(): VoiceCaptureKind {
        if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            preferredInputId = null
            return VoiceCaptureKind.OPEN
        }
        preferredInputId = null
        return VoiceCaptureKind.NONE
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnect()
        val media = VoiceMediaSession(scope) { _, pcm -> writePlayback(pcm) }
        if (!media.connect(params)) return false
        session.set(media)
        startPlayback(media.frameSamples)
        startCapture(media)
        return true
    }

    override fun disconnect() {
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

    override fun setInputDeviceId(id: String?) {
        preferredInputId = id
    }

    override fun setOutputDeviceId(id: String?) {
        preferredOutputId = id
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
        preferInputDevice(record)
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
        preferOutputDevice(track)
        audioTrack = track
        track.play()
    }

    private fun writePlayback(pcm: ShortArray) {
        val track = audioTrack ?: return
        runCatching { track.write(pcm, 0, pcm.size) }
    }

    private fun preferInputDevice(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val id = preferredInputId?.toIntOrNull() ?: return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } ?: return
        record.preferredDevice = device
    }

    private fun preferOutputDevice(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val id = preferredOutputId?.toIntOrNull() ?: return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id } ?: return
        track.preferredDevice = device
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
