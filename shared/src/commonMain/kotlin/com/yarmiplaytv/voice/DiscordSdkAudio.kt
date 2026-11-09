package com.yarmiplaytv.voice

/**
 * Audio controls the Discord Social SDK exposes, and how this app maps its voice settings onto them.
 *
 * The native `discordpp` library is not linked: there is no SDK binary and no proprietary Krisp model
 * (`.kef` / `.kw` / `discord_krisp`). Instead, v3.0.0 ships:
 * - Desktop WebRTC AudioProcessing (`dev.onvoid.webrtc:webrtc-java` APM) for AEC3, AGC, and WebRTC NS
 *   with playback reference (`processReverseStream`), matching Android's platform AEC/NS/AGC.
 * - Optional AI noise suppression via DeepFilterNet3 (`libDF` JNI on Android/TV, ONNX Runtime on desktop)
 *   as a mutually exclusive alternative to WebRTC/platform NS (like Discord's Krisp vs WebRTC NS).
 * - Configurable Opus bitrate up to 128 kbps (default 64 kbps) and optional stereo mode on the lobby path,
 *   with automatic negotiation and fallback for older clients and the Syncplay-port relay.
 *
 * Other invariants:
 * - [Client::SetNoiseCancellation] (Krisp) stays off. DeepFilterNet3 is the optional AI suppressor
 *   and replaces WebRTC or platform noise suppression; it never stacks on top of them.
 * - [Client::SetInputVolume] is recorded as a percentage in `[0, 100]` (100 = full). The in-app
 *   mic slider goes to 200%. The digital capture path applies that boost and [VoiceLimiter] is
 *   the one playback limiter. The recorded SDK percent stays capped at 100.
 * - [Client::SetOutputVolume] and [Call::SetParticipantVolume] are percentages in `[0, 200]`,
 *   100 = unity, applied as linear amplitude (`percent / 100`).
 * - Device ids are the platform's (Android [AudioDeviceInfo] id, Java Sound mixer name). An empty
 *   id is the system default. There is no Discord device-id namespace without the SDK.
 * - Encode is in-process Opus at 48 kHz, 20 ms, FEC on, DTX off. The lobby path can use 24–128 kbps
 *   and stereo; the Syncplay relay stays inside its existing cap and channel count.
 * - [Call::SetVADThreshold] automatic mode defaults to −60 dBFS.
 * - Desktop lobby capture runs WebRTC AEC3, AGC2, and either WebRTC NS or DeepFilterNet, each once.
 *   Android uses one platform AEC, one platform AGC, and either platform NS or DeepFilterNet.
 *   The Syncplay relay does not use WebRTC APM or DeepFilterNet.
 * - When WebRTC or platform AGC is active, the fixed desktop makeup gain stays at unity.
 * - On a phone or tablet the Discord playback track uses the voice-call stream so the video
 *   volume slider (media stream) does not scale voice. Android TV stays on the media stream:
 *   the voice-call stream is commonly silent there. While the mic is open, Android still enters
 *   communication mode so the platform echo canceller has a reference.
 */
object DiscordSdkAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val STEREO_CHANNELS = 2
    const val FRAME_MS = 20
    /** Discord's default voice Opus bitrate (64 kbps); configurable up to [MAX_BITRATE] (128 kbps). */
    const val BITRATE = 64_000
    const val MIN_BITRATE = 24_000
    const val MAX_BITRATE = 128_000
    val BITRATE_OPTIONS_BPS: List<Int> = listOf(32_000, 64_000, 96_000, 128_000)

    const val INPUT_VOLUME_MAX = 100f
    const val OUTPUT_VOLUME_MAX = 200f
    const val UNITY_PERCENT = 100f

    const val VAD_THRESHOLD_MIN_DB = -100f
    const val VAD_THRESHOLD_MAX_DB = 0f
    const val VAD_THRESHOLD_DEFAULT_DB = -60f

    /** Proprietary Discord Krisp ([Client::SetNoiseCancellation]) is not shipped; DeepFilterNet3 is used for AI NS. */
    const val KRISP_AVAILABLE = false

    /** DeepFilterNet3 AI noise suppression is supported on desktop and Android/TV. */
    const val DEEPFILTERNET_AVAILABLE = true

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

    /**
     * Phones with no headset and no user-picked output play lobby voice on the loudspeaker.
     * Voice-call usage otherwise stays on the earpiece, which is barely audible when the
     * phone is not against the ear. TV keeps the media stream.
     */
    fun playbackUsesBuiltInSpeaker(
        isTv: Boolean,
        userSelectedOutput: Boolean,
        externalOutputConnected: Boolean,
    ): Boolean = !isTv && !userSelectedOutput && !externalOutputConnected

    /**
     * Volume step to use for the voice-call stream.
     *
     * Android's minimum for that stream is 1, not 0. Treating any `current > 0` as audible
     * left the stream at step 1 of about 7, which is effectively silent. Raise anything
     * below the target up to at least 80% of max, or the media slider's fraction of max,
     * whichever is higher. Never lower a stream the user already set louder.
     */
    fun audibleCallStreamVolume(current: Int, maxCall: Int, music: Int, maxMusic: Int): Int {
        val maxC = maxCall.coerceAtLeast(1)
        val maxM = maxMusic.coerceAtLeast(1)
        val musicRatio = (music.coerceAtLeast(0).toFloat() / maxM.toFloat()).coerceIn(0f, 1f)
        val floor = (maxC * 0.8f).toInt().coerceIn(1, maxC)
        val matched = (maxC * musicRatio).toInt().coerceIn(0, maxC)
        val target = maxOf(floor, matched).coerceIn(1, maxC)
        return if (current >= target) current else target
    }

    /** App mic gain `0–2` → SDK input percent. `1` = 100%. Above `1` clamps to 100. */
    fun inputVolumePercent(appGain: Float): Float =
        (appGain.coerceIn(0f, 1f) / 1f) * INPUT_VOLUME_MAX

    /**
     * Label for the mic and playback sliders. Unity (gain `1`) is 100%, and `2` is 200%.
     * Dividing by the slider max made unity look like 50%.
     */
    fun voiceGainPercentLabel(gain: Float): String {
        val rounded = (gain.coerceAtLeast(0f) * 100f + 0.5f).toInt()
        return "$rounded%"
    }

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
 * [noiseCancellation] (Krisp) is always false; [aiNoiseSuppression] controls DeepFilterNet3,
 * which is mutually exclusive with WebRTC/platform [noiseSuppression] when active.
 */
data class DiscordAudioConfig(
    val inputDeviceId: String = "",
    val outputDeviceId: String = "",
    val inputVolumePercent: Float = DiscordSdkAudio.UNITY_PERCENT,
    val outputVolumePercent: Float = DiscordSdkAudio.UNITY_PERCENT,
    val participantVolumePercent: Map<String, Float> = emptyMap(),
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    /** [Client::SetNoiseSuppression]. Default on. Mutually exclusive with [aiNoiseSuppression] when AI NS is active. */
    val noiseSuppression: Boolean = true,
    /** [Client::SetEchoCancellation]. Default on, independent of [noiseSuppression] and [aiNoiseSuppression]. */
    val echoCancellation: Boolean = true,
    /** [Client::SetAutomaticGainControl]. Default on, independent of [noiseSuppression] and [aiNoiseSuppression]. */
    val automaticGainControl: Boolean = true,
    /** Client-side AI noise suppression via DeepFilterNet3 (replaces WebRTC/platform NS when on). */
    val aiNoiseSuppression: Boolean = false,
    /** Effective proprietary Krisp state. Always false in this build. */
    val noiseCancellation: Boolean = false,
    /** What the caller asked for on [DiscordVoiceEngine.setNoiseCancellation]. Recorded so tests can see the refusal. */
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
