package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Discord Application Lobby voice for app id [DEFAULT_DISCORD_APP_ID].
 *
 * The native Discord Social SDK is not linked, so this engine does not open a `discordpp::Call`.
 * It records the SDK audio calls — devices, volumes, mute, deafen, voice-activity mode, and the
 * WebRTC noise suppression / echo cancellation / AGC toggles — and the platform engines apply
 * that config to the capture and playback path opened for the lobby. See [DiscordSdkAudio] for
 * what the SDK supports and what this build cannot do (Krisp, SDK device ids, the SDK encoder).
 *
 * Speaking callbacks ([onUserSpeaking], [onLocalSpeaking]) stay for tests and for a future SDK
 * bridge. Production speaking and the mic ring come from the capture path, which uses the same
 * mute / deaf / gain rules.
 */
class DiscordVoiceEngine(
    private val externalScope: CoroutineScope? = null,
    private val playSoundDelegate: ((VoiceSound, Float) -> Unit)? = null,
) : VoiceEngine {
    @Suppress("unused")
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connected = AtomicBoolean(false)
    private val transmitting = AtomicBoolean(false)
    private val selfMuted = AtomicBoolean(false)
    private val localSpeaking = AtomicBoolean(false)
    private val micTesting = AtomicBoolean(false)
    private val micLevel = AtomicReference(0.0f)
    private val testLevel = AtomicReference(0.0f)
    private val activeSpeakers = AtomicReference<Set<String>>(emptySet())
    private val peerMutes = ConcurrentHashMap<String, Boolean>()
    private val configLock = Any()
    @Volatile private var config = DiscordAudioConfig()
    private var currentParams: VoiceMediaParams? = null
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    val isConnected: Boolean get() = connected.get()
    val appId: String get() = currentParams?.appId?.takeIf { it.isNotBlank() } ?: DEFAULT_DISCORD_APP_ID
    val lobbyId: String get() = currentParams?.lobbyId.orEmpty()

    /** Snapshot of the audio state that would be passed to the Discord client / call. */
    fun audioConfig(): DiscordAudioConfig = config

    /** Linear mic amplitude after the SDK's 0–100% input cap. */
    fun inputAmplitude(): Float = DiscordSdkAudio.amplitudeFromPercent(config.inputVolumePercent)

    /** Linear speaker amplitude. 1 = 100%, 2 = 200%. */
    fun outputAmplitude(): Float = DiscordSdkAudio.amplitudeFromPercent(config.outputVolumePercent)

    /** Linear playout amplitude for one participant. Missing ids are 100%. */
    fun peerAmplitude(participantId: String): Float =
        DiscordSdkAudio.amplitudeFromPercent(
            config.participantVolumePercent[participantId] ?: DiscordSdkAudio.UNITY_PERCENT,
        )

    override suspend fun detectCapture(): VoiceCaptureKind = VoiceCaptureKind.OPEN

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        val kept = config
        disconnect()
        currentParams = params
        // Devices, volumes, and processing survive a reconnect. Call flags do not.
        synchronized(configLock) {
            config = kept.copy(selfMute = false, selfDeaf = false, pttActive = false)
        }
        val effectiveAppId = params.appId.ifBlank { DEFAULT_DISCORD_APP_ID }
        if (effectiveAppId.isBlank()) {
            return false
        }
        connected.set(true)
        playSound(VoiceSound.JOIN, 0.8f)
        return true
    }

    override fun disconnect() {
        if (connected.getAndSet(false)) {
            playSound(VoiceSound.LEAVE, 0.8f)
        }
        transmitting.set(false)
        selfMuted.set(false)
        localSpeaking.set(false)
        micTesting.set(false)
        micLevel.set(0.0f)
        testLevel.set(0.0f)
        activeSpeakers.set(emptySet())
        currentParams = null
        update { it.copy(selfMute = false, selfDeaf = false, pttActive = false) }
    }

    override fun setTransmitting(on: Boolean) {
        val effective = config.transmits(on)
        val was = transmitting.getAndSet(effective)
        update { it.copy(pttActive = it.audioMode == DiscordAudioMode.PUSH_TO_TALK && effective) }
        if (was != effective) {
            playSound(if (effective) VoiceSound.MIC_ON else VoiceSound.MIC_OFF, 0.7f)
            if (!effective) {
                localSpeaking.set(false)
                micLevel.set(0.0f)
            }
        }
    }

    override fun setSelfMuted(muted: Boolean) {
        selfMuted.set(muted)
        update { it.copy(selfMute = muted) }
        if (muted) {
            transmitting.set(false)
            localSpeaking.set(false)
            micLevel.set(0.0f)
            update { it.copy(pttActive = false) }
        }
    }

    override fun setSelfDeaf(deaf: Boolean) {
        update { it.copy(selfDeaf = deaf) }
        if (deaf) {
            transmitting.set(false)
            localSpeaking.set(false)
            micLevel.set(0.0f)
            update { it.copy(pttActive = false) }
        }
    }

    override fun setVoiceActivityMode(activity: Boolean) {
        update {
            it.copy(
                audioMode = if (activity) DiscordAudioMode.VOICE_ACTIVITY else DiscordAudioMode.PUSH_TO_TALK,
            )
        }
    }

    override fun setInputGain(gain: Float) {
        update { it.copy(inputVolumePercent = DiscordSdkAudio.inputVolumePercent(gain)) }
    }

    override fun setOutputGain(gain: Float) {
        update { it.copy(outputVolumePercent = DiscordSdkAudio.outputVolumePercent(gain)) }
    }

    override fun setNoiseSuppression(on: Boolean) {
        update { it.copy(noiseSuppression = on) }
    }

    /**
     * [Client::SetNoiseCancellation]. Krisp is not in this build, so the request is recorded and
     * the effective flag stays off. WebRTC noise suppression is left as the user set it — turning
     * Krisp on in the SDK would disable it, but disabling it here would leave no noise reduction.
     */
    fun setNoiseCancellation(on: Boolean) {
        update { it.copy(noiseCancellationRequested = on, noiseCancellation = false) }
    }

    /** [Call::SetVADThreshold]. Automatic ignores [thresholdDb]; manual clamps to `[-100, 0]`. */
    fun setVadThreshold(automatic: Boolean, thresholdDb: Float = DiscordSdkAudio.VAD_THRESHOLD_DEFAULT_DB) {
        val db = thresholdDb.coerceIn(DiscordSdkAudio.VAD_THRESHOLD_MIN_DB, DiscordSdkAudio.VAD_THRESHOLD_MAX_DB)
        update { it.copy(vadAutomatic = automatic, vadThresholdDb = if (automatic) DiscordSdkAudio.VAD_THRESHOLD_DEFAULT_DB else db) }
    }

    override fun setPeerMuted(participantId: String, muted: Boolean) {
        // The SDK has no per-user mute distinct from SetParticipantVolume. Local mute stays its
        // own flag so unmuting restores the previous volume instead of leaving them at 0%.
        peerMutes[participantId] = muted
    }

    override fun setPeerVolume(participantId: String, volume: Float) {
        val percent = DiscordSdkAudio.outputVolumePercent(volume)
        update { cfg ->
            cfg.copy(participantVolumePercent = cfg.participantVolumePercent + (participantId to percent))
        }
    }

    override fun setInputDeviceId(id: String?) {
        update { it.copy(inputDeviceId = id?.takeIf { value -> value.isNotEmpty() }.orEmpty()) }
    }

    override fun setOutputDeviceId(id: String?) {
        update { it.copy(outputDeviceId = id?.takeIf { value -> value.isNotEmpty() }.orEmpty()) }
    }

    override fun playSound(kind: VoiceSound, volume: Float) {
        playSoundDelegate?.invoke(kind, volume)
    }

    override fun captureLevel(): Float {
        if (!transmitting.get() || selfMuted.get() || config.selfDeaf) return 0f
        return micLevel.get()
    }

    override fun audibleParticipantIds(): Set<String> {
        if (!connected.get() || config.selfDeaf) return emptySet()
        val speakers = activeSpeakers.get()
        if (speakers.isEmpty()) return emptySet()
        return speakers.filter { peerMutes[it] != true && (config.participantVolumePercent[it] ?: DiscordSdkAudio.UNITY_PERCENT) > 0f }.toSet()
    }

    override fun capturingSpeech(): Boolean {
        if (micTesting.get()) return false
        return transmitting.get() && !selfMuted.get() && !config.selfDeaf && localSpeaking.get()
    }

    override fun setMicTest(active: Boolean) {
        micTesting.set(active)
        if (!active) testLevel.set(0.0f)
    }

    override fun isMicTestActive(): Boolean = micTesting.get()

    override fun micTestLevel(): Float = if (micTesting.get()) testLevel.get() else 0f

    /** Forwards a remote speaking callback. Production audio uses the capture/playback mix. */
    fun onUserSpeaking(userId: String, speaking: Boolean) {
        val current = activeSpeakers.get()
        val next = if (speaking) current + userId else current - userId
        activeSpeakers.set(next)
    }

    /** Forwards local speech. [level] is 0–1 before the SDK input cap. */
    fun onLocalSpeaking(speaking: Boolean, level: Float = if (speaking) 0.5f else 0.0f) {
        if (micTesting.get()) {
            localSpeaking.set(false)
            testLevel.set(if (speaking) (level * inputAmplitude()).coerceIn(0f, 1f) else 0.0f)
            return
        }
        val audible = speaking && transmitting.get() && !selfMuted.get() && !config.selfDeaf
        localSpeaking.set(audible)
        micLevel.set(if (audible) (level * inputAmplitude()).coerceIn(0f, 1f) else 0.0f)
    }

    private fun update(block: (DiscordAudioConfig) -> DiscordAudioConfig) {
        synchronized(configLock) {
            config = block(config)
        }
    }

    companion object {
        const val DEFAULT_DISCORD_APP_ID = "1210325027023753307"
    }
}
