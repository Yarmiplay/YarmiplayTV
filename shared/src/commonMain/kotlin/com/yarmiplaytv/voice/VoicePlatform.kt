package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams

/**
 * Platform mic/speaker + Opus `yv1` send/receive.
 * Android and desktop implement against [VoiceMediaSession]; web uses WebSocket + WebCrypto.
 */
interface VoiceEngine {
    /**
     * Probe inputs. Prefer a wired/BT headset mic when present.
     * [VoiceCaptureKind.NONE] means listen-only (no mic button).
     */
    suspend fun detectCapture(): VoiceCaptureKind

    /** Open the media path after [voiceJoined]. Returns false if it could not connect. */
    suspend fun connect(params: VoiceMediaParams): Boolean

    fun disconnect()

    /** Start/stop capturing (open-mic, PTT hold, or TV toggle latch). */
    fun setTransmitting(on: Boolean)

    /** Whether the platform currently allows mic capture (e.g. RECORD_AUDIO granted). */
    fun hasMicPermission(): Boolean = true

    /**
     * Called after the user grants mic permission while already connected, so capture can start
     * (Android's [AudioRecord] is only created once permission is held).
     */
    fun onMicPermissionGranted() = Unit

    fun setSelfMuted(muted: Boolean)

    /**
     * Discord lobby deafen ([discordpp::Call::SetSelfDeaf]): stop playout and stop sending.
     * Mute alone still plays other people. No-op when this engine is not in a Discord lobby.
     * Undeafen resumes sending if the mic was left open; it does not clear an explicit mute.
     */
    fun setSelfDeaf(deaf: Boolean) = Unit

    /**
     * Discord [discordpp::AudioModeType]: `true` is voice activity, `false` is push-to-talk.
     * The relayed path ignores this and keeps gating send on [setTransmitting].
     */
    fun setVoiceActivityMode(activity: Boolean) = Unit

    fun setInputGain(gain: Float)

    fun setOutputGain(gain: Float)

    /**
     * Capture-side standard noise suppression (WebRTC NS on desktop, platform NoiseSuppressor on
     * Android, spectral NS on legacy relay). Mutually exclusive with [setAiNoiseSuppression] when
     * DeepFilterNet3 is active. Default on; applies live while connected.
     */
    fun setNoiseSuppression(on: Boolean) = Unit

    /**
     * Capture-side echo cancellation (WebRTC AEC3 with playback reference on desktop, platform
     * AcousticEchoCanceler on Android). Default on; applies live while connected.
     */
    fun setEchoCancellation(on: Boolean) = Unit

    /**
     * Capture-side automatic gain control (WebRTC digital adaptive AGC on desktop, platform
     * AutomaticGainControl on Android). Default on; applies live while connected.
     */
    fun setAutoGainControl(on: Boolean) = Unit

    /**
     * Client-side AI noise suppression via DeepFilterNet3. When on, replaces the WebRTC/platform
     * noise suppressor (mutually exclusive); AEC and AGC remain governed by their own toggles.
     */
    fun setAiNoiseSuppression(on: Boolean) = Unit

    /**
     * Preferred Opus encoder bitrate in bps (e.g. 32_000..128_000, default 64_000) for lobby voice.
     * Negotiates/clamps against the server relay cap on connect.
     */
    fun setPreferredBitrate(bitrateBps: Int) = Unit

    /**
     * Optional stereo voice mode for the lobby voice path. Falls back to mono on the legacy relay
     * or if the negotiated bitrate is too low.
     */
    fun setPreferredStereo(stereo: Boolean) = Unit

    /**
     * Non-null when AI noise suppression (DeepFilterNet3) fell back to standard NS because the
     * native library/model failed to load or the device could not sustain real-time 20 ms frames.
     */
    fun statusNotice(): String? = null

    /** Local mute of one remote participant (by server participant id, e.g. `c17`). */
    fun setPeerMuted(participantId: String, muted: Boolean)

    fun setPeerVolume(participantId: String, volume: Float)

    /** Mic / input routes for the voice settings picker. Always includes a system-default entry. */
    fun listInputDevices(): List<VoiceAudioDevice> = listOf(VoiceAudioDevice("", "System default", isDefault = true))

    /** Speaker / output routes for the voice settings picker. Always includes a system-default entry. */
    fun listOutputDevices(): List<VoiceAudioDevice> = listOf(VoiceAudioDevice("", "System default", isDefault = true))

    /**
     * Prefer this capture device (empty/null = system default). Applies live while connected —
     * no need to leave and rejoin voice. When the device disappears, engines fall back to default
     * and invoke [onDeviceLost] with `input=true`.
     */
    fun setInputDeviceId(id: String?)

    /**
     * Prefer this playback device (empty/null = system default). Applies live while connected.
     * Unplug falls back to default and invokes [onDeviceLost] with `input=false`.
     */
    fun setOutputDeviceId(id: String?)

    /**
     * Invoked when a selected device is removed (unplugged) and the engine fell back to the
     * system default. `input` true for mic, false for speaker.
     */
    var onDeviceLost: ((input: Boolean) -> Unit)?
        get() = null
        set(_) = Unit

    /** Play a short UI sound (join/leave/mic). [volume] 0–1. */
    fun playSound(kind: VoiceSound, volume: Float)

    /**
     * 0…1 post-noise-suppression mic level while unmuted and transmitting; 0 when muted / idle.
     * Polled for the Mic activity ring — must be cheap and non-blocking.
     */
    fun captureLevel(): Float = 0f

    /**
     * Participant ids (`c17`) whose decoded audio we are hearing right now.
     * Empty the same tick they go silent / PLC / locally muted — no linger. Cheap to poll.
     */
    fun audibleParticipantIds(): Set<String> = emptySet()

    /** True while local capture has speech energy (for reporting voiceActivity). */
    fun capturingSpeech(): Boolean = false

    /**
     * Start or stop local microphone loopback testing (processes mic through the exact send chain:
     * processing + Opus encode + Opus decode + limiter, and plays back locally without sending to
     * the lobby/room).
     */
    fun setMicTest(active: Boolean) = Unit

    /** True while [setMicTest] loopback is running. */
    fun isMicTestActive(): Boolean = false

    /** 0…1 post-encode/decode level meter while [isMicTestActive] is running; 0 otherwise. */
    fun micTestLevel(): Float = 0f
}

enum class VoiceSound { JOIN, LEAVE, MIC_ON, MIC_OFF }

/** No-op engine until a platform implementation is wired. */
class NullVoiceEngine : VoiceEngine {
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null
    override suspend fun detectCapture(): VoiceCaptureKind = VoiceCaptureKind.NONE
    override suspend fun connect(params: VoiceMediaParams): Boolean = false
    override fun disconnect() = Unit
    override fun setTransmitting(on: Boolean) = Unit
    override fun hasMicPermission(): Boolean = true
    override fun onMicPermissionGranted() = Unit
    override fun setSelfMuted(muted: Boolean) = Unit
    override fun setInputGain(gain: Float) = Unit
    override fun setOutputGain(gain: Float) = Unit
    override fun setNoiseSuppression(on: Boolean) = Unit
    override fun setPeerMuted(participantId: String, muted: Boolean) = Unit
    override fun setPeerVolume(participantId: String, volume: Float) = Unit
    override fun setInputDeviceId(id: String?) = Unit
    override fun setOutputDeviceId(id: String?) = Unit
    override fun playSound(kind: VoiceSound, volume: Float) = Unit
    override fun captureLevel(): Float = 0f
    override fun audibleParticipantIds(): Set<String> = emptySet()
    override fun capturingSpeech(): Boolean = false
}
