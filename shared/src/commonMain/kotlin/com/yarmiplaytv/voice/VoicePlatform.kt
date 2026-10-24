package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceDescriptor

/** Platform mic/speaker + Opus send/receive. Expect/actual filled in android/desktop; web uses JS. */
interface VoiceEngine {
    /**
     * Probe inputs. Prefer a wired/BT headset mic when present.
     * [VoiceCaptureKind.NONE] means listen-only (no mic button).
     */
    suspend fun detectCapture(): VoiceCaptureKind

    /** Open the media path after [voiceJoined]. Returns false if it could not connect. */
    suspend fun connect(
        host: String,
        descriptor: VoiceDescriptor,
        sessionToken: String?,
        onSignalOut: (type: String, payload: String, payloadKey: String) -> Unit,
    ): Boolean

    fun disconnect()

    /** Start/stop capturing (open-mic, PTT hold, or TV toggle latch). */
    fun setTransmitting(on: Boolean)

    fun setSelfMuted(muted: Boolean)

    fun setInputGain(gain: Float)

    fun setOutputGain(gain: Float)

    /** Local mute of one remote participant (by server participant id). */
    fun setPeerMuted(participantId: String, muted: Boolean)

    fun setPeerVolume(participantId: String, volume: Float)

    fun setInputDeviceId(id: String?)

    fun setOutputDeviceId(id: String?)

    /** Opaque signal from the server (Opus-channel control). */
    fun onSignalIn(type: String, payload: String)

    /** Play a short UI sound (join/leave/mic). [volume] 0–1. */
    fun playSound(kind: VoiceSound, volume: Float)
}

enum class VoiceSound { JOIN, LEAVE, MIC_ON, MIC_OFF }

/** No-op engine until a platform implementation is wired. */
class NullVoiceEngine : VoiceEngine {
    override suspend fun detectCapture(): VoiceCaptureKind = VoiceCaptureKind.NONE
    override suspend fun connect(
        host: String,
        descriptor: VoiceDescriptor,
        sessionToken: String?,
        onSignalOut: (type: String, payload: String, payloadKey: String) -> Unit,
    ): Boolean = false
    override fun disconnect() = Unit
    override fun setTransmitting(on: Boolean) = Unit
    override fun setSelfMuted(muted: Boolean) = Unit
    override fun setInputGain(gain: Float) = Unit
    override fun setOutputGain(gain: Float) = Unit
    override fun setPeerMuted(participantId: String, muted: Boolean) = Unit
    override fun setPeerVolume(participantId: String, volume: Float) = Unit
    override fun setInputDeviceId(id: String?) = Unit
    override fun setOutputDeviceId(id: String?) = Unit
    override fun onSignalIn(type: String, payload: String) = Unit
    override fun playSound(kind: VoiceSound, volume: Float) = Unit
}
