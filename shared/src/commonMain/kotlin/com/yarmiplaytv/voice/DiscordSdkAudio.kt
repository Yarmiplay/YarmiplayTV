package com.yarmiplaytv.voice

/**
 * Audio controls the Discord Social SDK actually exposes, and how this app maps its existing
 * voice settings onto them.
 *
 * The native `discordpp` library is not linked: there is no SDK binary and no Krisp model
 * (`.kef` / `.kw` / `discord_krisp`). [DiscordVoiceEngine] records the same calls the SDK would
 * take, and Android / desktop apply them on the capture and playback path that already carries
 * the lobby. Limitations that follow from that:
 *
 * - [Client::SetNoiseCancellation] (Krisp) cannot be turned on. The SDK would disable WebRTC
 *   noise suppression when Krisp enables; we refuse Krisp and leave suppression as the user set it.
 * - [Client::SetInputVolume] is a percentage in `[0, 100]` (100 = full). The in-app mic slider
 *   also goes to 200%. That extra boost is not applied on the Discord path, because the SDK has
 *   nowhere to put it and a digital 2× gain clips.
 * - [Client::SetOutputVolume] and [Call::SetParticipantVolume] are percentages in `[0, 200]`,
 *   100 = unity. The SDK calls this "perceptual loudness" but does not publish a curve, so the
 *   percentage is applied as linear amplitude (`percent / 100`), which matches the SDK's own
 *   raw-PCM callback example.
 * - Device ids are the platform's (Android [AudioDeviceInfo] id, Java Sound mixer name). An empty
 *   id is the system default, which is what [Client::GetDefaultAudioDeviceId] means. There is no
 *   Discord device-id namespace without the SDK.
 * - Encode is in-process Opus at 48 kHz, mono, 20 ms, 64 kbps, FEC on, DTX off — Discord's default
 *   voice rate and this app's Opus ceiling. The SDK's own encoder, stereo, and higher bitrates
 *   are not available. Opus DTX is left off because dropping frames into PLC was the chop.
 * - [Call::SetVADThreshold] automatic mode defaults to −60 dBFS. There is no sensitivity slider;
 *   automatic stays on.
 * - Desktop has no WebRTC AEC / AGC. Android uses the platform equivalents
 *   (`NoiseSuppressor`, `AcousticEchoCanceler`, `AutomaticGainControl`) and does not also run the
 *   spectral gate. Echo cancellation and AGC stay at the SDK default (on) when the user turns
 *   noise suppression off; the SDK toggles those separately.
 * - On a phone or tablet the Discord playback track uses the voice-call stream so the video
 *   volume slider (media stream) does not scale voice. Android TV stays on the media stream:
 *   the voice-call stream is commonly silent there. While the mic is open, Android still enters
 *   communication mode so the platform echo canceller has a reference; that can duck media and
 *   is unchanged from the existing capture path.
 */
object DiscordSdkAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    /** Discord's default voice Opus bitrate, and this app's encoder ceiling. */
    const val BITRATE = 64_000

    const val INPUT_VOLUME_MAX = 100f
    const val OUTPUT_VOLUME_MAX = 200f
    const val UNITY_PERCENT = 100f

    const val VAD_THRESHOLD_MIN_DB = -100f
    const val VAD_THRESHOLD_MAX_DB = 0f
    const val VAD_THRESHOLD_DEFAULT_DB = -60f

    /** [Client::SetNoiseCancellation] is not shipped. */
    const val KRISP_AVAILABLE = false

    /**
     * Rates tried before the 8 kHz fallback. 8 kHz resampled up to 48 kHz is the robotic path;
     * Discord capture prefers 48 kHz and only uses 8 kHz if nothing wider will open.
     */
    fun preferredCaptureRates(): IntArray = intArrayOf(SAMPLE_RATE, 44_100, 16_000)

    /** Narrowband last resort. The non-Discord path still tries this in its normal list. */
    const val NARROWBAND_FALLBACK_RATE = 8_000

    /**
     * True on Android TV, where [android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION] does not
     * reach the speakers. Elsewhere Discord voice uses the voice-call stream so it is not scaled
     * by the video player's media-stream volume.
     */
    fun playbackSharesVideoStream(isTv: Boolean): Boolean = isTv

    /** App mic gain `0–2` → SDK input percent. `1` = 100%. Above `1` clamps to 100. */
    fun inputVolumePercent(appGain: Float): Float =
        (appGain.coerceIn(0f, 1f) / 1f) * INPUT_VOLUME_MAX

    /** App output or per-person gain `0–2` → SDK percent. `1` = 100%, `2` = 200%. */
    fun outputVolumePercent(appGain: Float): Float =
        (appGain.coerceIn(0f, 2f) / 1f) * UNITY_PERCENT

    /** Linear amplitude for an SDK percentage. 100 = unity, 200 = 2×, 0 = silence. */
    fun amplitudeFromPercent(percent: Float): Float =
        (percent.coerceAtLeast(0f) / UNITY_PERCENT)

    /**
     * PCM16 RMS at [dbFs] (0 dBFS = full scale). Discord's default VAD threshold is −60 dBFS.
     */
    fun rmsAtDbFs(dbFs: Float): Double {
        val db = dbFs.toDouble().coerceIn(VAD_THRESHOLD_MIN_DB.toDouble(), VAD_THRESHOLD_MAX_DB.toDouble())
        return 32768.0 * Math.pow(10.0, db / 20.0)
    }

    /** Deaf stops transmit as well as playout ([Call::SetSelfDeaf]). Mute alone does not. */
    fun transmits(selfMute: Boolean, selfDeaf: Boolean, wantTransmit: Boolean): Boolean =
        wantTransmit && !selfMute && !selfDeaf

    fun hearsOthers(selfDeaf: Boolean): Boolean = !selfDeaf
}

enum class DiscordAudioMode {
    /** [discordpp::AudioModeType.MODE_VAD] */
    VOICE_ACTIVITY,
    /** [discordpp::AudioModeType.MODE_PTT] */
    PUSH_TO_TALK,
}

/**
 * The voice state that would be passed to `Client` / `Call` for one lobby.
 * [noiseCancellation] is always false; see [DiscordSdkAudio.KRISP_AVAILABLE].
 */
data class DiscordAudioConfig(
    val inputDeviceId: String = "",
    val outputDeviceId: String = "",
    val inputVolumePercent: Float = DiscordSdkAudio.UNITY_PERCENT,
    val outputVolumePercent: Float = DiscordSdkAudio.UNITY_PERCENT,
    val participantVolumePercent: Map<String, Float> = emptyMap(),
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    /** [Client::SetNoiseSuppression]. Default on. Mutually exclusive with Krisp, which is unavailable. */
    val noiseSuppression: Boolean = true,
    /** [Client::SetEchoCancellation]. Default on, independent of [noiseSuppression]. */
    val echoCancellation: Boolean = true,
    /** [Client::SetAutomaticGainControl]. Default on, independent of [noiseSuppression]. */
    val automaticGainControl: Boolean = true,
    /** Effective Krisp state. Always false in this build. */
    val noiseCancellation: Boolean = false,
    /** What the caller asked for. Recorded so tests can see the refusal. */
    val noiseCancellationRequested: Boolean = false,
    val audioMode: DiscordAudioMode = DiscordAudioMode.VOICE_ACTIVITY,
    val pttActive: Boolean = false,
    val vadAutomatic: Boolean = true,
    val vadThresholdDb: Float = DiscordSdkAudio.VAD_THRESHOLD_DEFAULT_DB,
    val sampleRate: Int = DiscordSdkAudio.SAMPLE_RATE,
    val channels: Int = DiscordSdkAudio.CHANNELS,
    val frameMs: Int = DiscordSdkAudio.FRAME_MS,
    val bitrate: Int = DiscordSdkAudio.BITRATE,
) {
    fun transmits(wantTransmit: Boolean): Boolean =
        DiscordSdkAudio.transmits(selfMute, selfDeaf, wantTransmit)

    fun hearsOthers(): Boolean = DiscordSdkAudio.hearsOthers(selfDeaf)
}
