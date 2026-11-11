package com.yarmiplaytv.voice

/**
 * Platform family for constructing and validating the voice capture processing chain.
 */
enum class VoiceChainPlatform(val label: String) {
    DESKTOP("desktop"),
    ANDROID("android"),
    ANDROID_TV("android-tv"),
}

/** Echo cancellation stage backend. At most one may be active at any time. */
enum class AecBackend(val label: String) {
    NONE("none"),
    WEBRTC("webrtc"),
    PLATFORM("platform"),
}

/** Automatic gain control stage backend. At most one may be active at any time. */
enum class AgcBackend(val label: String) {
    NONE("none"),
    WEBRTC("webrtc"),
    PLATFORM("platform"),
}

/**
 * Noise suppression stage backend.
 *
 * HARD RULE: At any moment at most ONE noise-suppression stage may process the mic signal:
 * either DeepFilterNet ([DEEPFILTERNET], AI toggle on) OR the WebRTC/platform noise suppressor
 * ([WEBRTC] / [PLATFORM], AI toggle off, NS on) OR [NONE] (or [SPECTRAL] on legacy non-lobby relay only).
 * Never both, and never the old spectral suppressor or hard gate on the Discord lobby path.
 */
enum class NsBackend(val label: String) {
    NONE("none"),
    WEBRTC("webrtc"),
    PLATFORM("platform"),
    DEEPFILTERNET("deepfilternet"),
    SPECTRAL("spectral"),
}

/**
 * Immutable description of the active voice capture processing chain.
 *
 * Enforces the single-stage invariant at construction time:
 * - [aecStageCount] <= 1
 * - [agcStageCount] <= 1
 * - [nsStageCount] <= 1
 * - On the Discord lobby path ([discordLobby] == true), [spectralNsEnabled] and [dropGatedSilence] are always false.
 */
data class VoiceProcessingChain(
    val platform: VoiceChainPlatform,
    val discordLobby: Boolean,
    val aec: AecBackend,
    val agc: AgcBackend,
    val ns: NsBackend,
    val webrtcAecEnabled: Boolean,
    val platformAecEnabled: Boolean,
    val webrtcAgcEnabled: Boolean,
    val platformAgcEnabled: Boolean,
    val webrtcNsEnabled: Boolean,
    val platformNsEnabled: Boolean,
    val deepFilterNetEnabled: Boolean,
    val spectralNsEnabled: Boolean,
    val dropGatedSilence: Boolean,
    val fallbackNotice: String? = null,
) {
    val aecStageCount: Int
        get() = listOf(webrtcAecEnabled, platformAecEnabled).count { it }

    val agcStageCount: Int
        get() = listOf(webrtcAgcEnabled, platformAgcEnabled).count { it }

    val nsStageCount: Int
        get() = listOf(webrtcNsEnabled, platformNsEnabled, deepFilterNetEnabled, spectralNsEnabled).count { it }

    /** Deterministic stage execution order on the capture path: AEC (with playback reference) -> AGC -> NS. */
    val orderedStages: List<String>
        get() = listOf(
            "AEC=${aec.label}",
            "AGC=${agc.label}",
            "NS=${ns.label}",
        )

    init {
        require(aecStageCount <= 1) {
            "Hard rule violation: multiple AEC stages active (webrtc=$webrtcAecEnabled, platform=$platformAecEnabled)"
        }
        require(agcStageCount <= 1) {
            "Hard rule violation: multiple AGC stages active (webrtc=$webrtcAgcEnabled, platform=$platformAgcEnabled)"
        }
        require(nsStageCount <= 1) {
            "Hard rule violation: stacked noise suppression stages active " +
                "(webrtc=$webrtcNsEnabled, platform=$platformNsEnabled, deepfilternet=$deepFilterNetEnabled, spectral=$spectralNsEnabled)"
        }
        require((aec != AecBackend.NONE) == (aecStageCount == 1)) {
            "AEC backend ${aec.label} inconsistent with stage flags"
        }
        require((agc != AgcBackend.NONE) == (agcStageCount == 1)) {
            "AGC backend ${agc.label} inconsistent with stage flags"
        }
        require((ns != NsBackend.NONE) == (nsStageCount == 1)) {
            "NS backend ${ns.label} inconsistent with stage flags"
        }
        if (discordLobby) {
            require(!spectralNsEnabled) {
                "Hard rule violation: old spectral noise suppressor must never run on the Discord lobby path"
            }
            require(!dropGatedSilence) {
                "Hard rule violation: extra hard gate / frame-dropping VAD must never run on the Discord lobby path"
            }
        }
    }

    /** Canonical one-line startup log required at voice start. */
    fun logLine(): String = "voice chain: AEC=${aec.label} AGC=${agc.label} NS=${ns.label}"

    companion object {
        /**
         * Builds the active processing chain for the given platform, toggles, and runtime availability.
         *
         * When [aiNoiseSuppression] is true:
         * - If [deepFilterNetAvailable] is true, [NsBackend.DEEPFILTERNET] is selected and both platform
         *   NoiseSuppressor (on Android/TV) and WebRTC APM NS (on desktop) are forced off.
         * - If [deepFilterNetAvailable] is false (model/native lib failed to load or device was too slow),
         *   falls back gracefully to WebRTC/platform NS (when [noiseSuppression] is true) or [NsBackend.NONE]
         *   (when [noiseSuppression] is false), and populates [fallbackNotice].
         */
        /**
         * Desktop mic test and Discord lobby calls share the lobby capture chain.
         * A Syncplay relay session keeps the spectral chain. The mic test never
         * transmits, so a test started outside a lobby (or over a relay session)
         * still uses this lobby chain and the relay chain is rebuilt when it stops.
         */
        fun useLobbyCaptureChain(inSession: Boolean, activeDiscord: Boolean, micTest: Boolean): Boolean =
            !inSession || activeDiscord || micTest

        fun build(
            platform: VoiceChainPlatform,
            discordLobby: Boolean = true,
            echoCancellation: Boolean = true,
            autoGainControl: Boolean = true,
            noiseSuppression: Boolean = true,
            aiNoiseSuppression: Boolean = false,
            webrtcApmAvailable: Boolean = true,
            platformAecAvailable: Boolean = true,
            platformAgcAvailable: Boolean = true,
            platformNsAvailable: Boolean = true,
            deepFilterNetAvailable: Boolean = true,
            deepFilterNetFailureReason: String? = null,
        ): VoiceProcessingChain {
            val useWebrtcPlatform = platform == VoiceChainPlatform.DESKTOP
            val useAndroidPlatform = platform == VoiceChainPlatform.ANDROID || platform == VoiceChainPlatform.ANDROID_TV

            // Syncplay relay is unchanged aside from not stacking the spectral gate on platform NS:
            // desktop keeps the spectral suppressor (and its hard gate); Android keeps platform
            // NS/AEC/AGC, all still tied to the single noise-suppression setting. DeepFilterNet,
            // desktop WebRTC APM, stereo, and the independent AEC/AGC toggles are lobby-only.
            if (!discordLobby) {
                val spectral = useWebrtcPlatform && noiseSuppression
                val platformOn = useAndroidPlatform && noiseSuppression
                val platformNs = platformOn && platformNsAvailable
                val platformAec = platformOn && platformAecAvailable
                val platformAgc = platformOn && platformAgcAvailable
                val relayNs = when {
                    spectral -> NsBackend.SPECTRAL
                    platformNs -> NsBackend.PLATFORM
                    else -> NsBackend.NONE
                }
                return VoiceProcessingChain(
                    platform = platform,
                    discordLobby = false,
                    aec = if (platformAec) AecBackend.PLATFORM else AecBackend.NONE,
                    agc = if (platformAgc) AgcBackend.PLATFORM else AgcBackend.NONE,
                    ns = relayNs,
                    webrtcAecEnabled = false,
                    platformAecEnabled = platformAec,
                    webrtcAgcEnabled = false,
                    platformAgcEnabled = platformAgc,
                    webrtcNsEnabled = false,
                    platformNsEnabled = platformNs,
                    deepFilterNetEnabled = false,
                    spectralNsEnabled = spectral,
                    dropGatedSilence = spectral,
                    fallbackNotice = null,
                )
            }

            val webrtcAec = useWebrtcPlatform && echoCancellation && webrtcApmAvailable
            val platformAec = useAndroidPlatform && echoCancellation && platformAecAvailable
            val aecBackend = when {
                webrtcAec -> AecBackend.WEBRTC
                platformAec -> AecBackend.PLATFORM
                else -> AecBackend.NONE
            }

            val webrtcAgc = useWebrtcPlatform && autoGainControl && webrtcApmAvailable
            val platformAgc = useAndroidPlatform && autoGainControl && platformAgcAvailable
            val agcBackend = when {
                webrtcAgc -> AgcBackend.WEBRTC
                platformAgc -> AgcBackend.PLATFORM
                else -> AgcBackend.NONE
            }

            val useDeepFilter = aiNoiseSuppression && deepFilterNetAvailable
            val aiFallbackTriggered = aiNoiseSuppression && !deepFilterNetAvailable

            val webrtcNs = !useDeepFilter && useWebrtcPlatform && noiseSuppression && webrtcApmAvailable
            val platformNs = !useDeepFilter && useAndroidPlatform && noiseSuppression && platformNsAvailable
            val spectralNs = false

            val nsBackend = when {
                useDeepFilter -> NsBackend.DEEPFILTERNET
                webrtcNs -> NsBackend.WEBRTC
                platformNs -> NsBackend.PLATFORM
                spectralNs -> NsBackend.SPECTRAL
                else -> NsBackend.NONE
            }

            val notice = if (aiFallbackTriggered) {
                val reason = deepFilterNetFailureReason?.takeIf { it.isNotBlank() }
                    ?: "model or native library unavailable"
                val fallbackLabel = when (nsBackend) {
                    NsBackend.WEBRTC, NsBackend.PLATFORM -> "using standard noise suppression"
                    else -> "AI noise suppression off"
                }
                "AI noise suppression unavailable ($reason); $fallbackLabel"
            } else {
                null
            }

            return VoiceProcessingChain(
                platform = platform,
                discordLobby = discordLobby,
                aec = aecBackend,
                agc = agcBackend,
                ns = nsBackend,
                webrtcAecEnabled = webrtcAec,
                platformAecEnabled = platformAec,
                webrtcAgcEnabled = webrtcAgc,
                platformAgcEnabled = platformAgc,
                webrtcNsEnabled = webrtcNs,
                platformNsEnabled = platformNs,
                deepFilterNetEnabled = useDeepFilter,
                spectralNsEnabled = spectralNs,
                dropGatedSilence = !discordLobby && spectralNs,
                fallbackNotice = notice,
            )
        }
    }
}
