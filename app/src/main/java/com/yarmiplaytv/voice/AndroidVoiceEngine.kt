package com.yarmiplaytv.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.yarmiplaytv.R
import com.yarmiplaytv.syncplay.VoiceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android capture probe + UI cue sounds. Opus UDP/TCP media path lands when the server protocol is ready;
 * [connect] succeeds as a stub so room voice UI (toggle, listen-only, cues) can be exercised.
 *
 * Headset preference: when a wired or Bluetooth headset input is present, that device is selected.
 */
class AndroidVoiceEngine(
    context: Context,
    private val isTv: Boolean,
) : VoiceEngine {
    private val app = context.applicationContext
    private var preferredInputId: String? = null
    private var player: MediaPlayer? = null

    override suspend fun detectCapture(): VoiceCaptureKind = withContext(Dispatchers.Default) {
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
        // Phones/tablets usually have a built-in mic even when getDevices is empty on older APIs.
        if (!isTv && app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            preferredInputId = null
            return VoiceCaptureKind.OPEN
        }
        // TV with no enumerated input and no mic feature: listen-only (join from phone to talk).
        preferredInputId = null
        return VoiceCaptureKind.NONE
    }

    override suspend fun connect(
        host: String,
        descriptor: VoiceDescriptor,
        sessionToken: String?,
        onSignalOut: (type: String, payload: String, payloadKey: String) -> Unit,
    ): Boolean {
        // Prefer headset id discovered in detectCapture until the real Opus path sets devices.
        preferredInputId?.let { setInputDeviceId(it) }
        // Stub OK: signaling UI works; media packets come with the revised VOICE_PROTOCOL.
        return true
    }

    override fun disconnect() {
        runCatching { player?.release() }
        player = null
    }

    override fun setTransmitting(on: Boolean) = Unit
    override fun setSelfMuted(muted: Boolean) = Unit
    override fun setInputGain(gain: Float) = Unit
    override fun setOutputGain(gain: Float) = Unit
    override fun setPeerMuted(participantId: String, muted: Boolean) = Unit
    override fun setPeerVolume(participantId: String, volume: Float) = Unit
    override fun setInputDeviceId(id: String?) {
        preferredInputId = id
    }
    override fun setOutputDeviceId(id: String?) = Unit
    override fun onSignalIn(type: String, payload: String) = Unit

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

    companion object {
        private val HEADSET_INPUT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
