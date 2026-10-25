package com.yarmiplaytv.voice

import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.syncplay.VoiceDescriptor
import com.yarmiplaytv.syncplay.VoicePeer

/**
 * A selectable mic or speaker route in voice settings.
 * [id] empty means the platform/system default; engines fall back to that when a device is unplugged.
 */
data class VoiceAudioDevice(
    val id: String,
    val label: String,
    val isDefault: Boolean = id.isEmpty(),
)

/** Whether we can capture mic audio on this device right now. */
enum class VoiceCaptureKind {
    /** Continuous mic (phone/desktop headset or built-in). */
    OPEN,

    /** Hold-to-talk only (TV remote mic, or user chose PTT). */
    PUSH_TO_TALK,

    /** No usable input — listen only. */
    NONE,
}

/** Connection to the room's voice channel. */
enum class VoiceConnection {
    OFF,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class VoiceUiState(
    val available: Boolean = false,
    val enabledByClient: Boolean = true,
    val connection: VoiceConnection = VoiceConnection.OFF,
    val selfMuted: Boolean = false,
    val talking: Boolean = false,
    /**
     * TV toggle latch (and any future toggle UI): true means the mic is armed open until toggled off.
     * Distinct from [talking], which is the actual TX gate after mute/connection checks.
     */
    val talkLatched: Boolean = false,
    val capture: VoiceCaptureKind = VoiceCaptureKind.NONE,
    val talkMode: VoiceTalkMode = VoiceTalkMode.OPEN_MIC,
    val participantId: String? = null,
    val peers: List<VoicePeer> = emptyList(),
    val speakingIds: Set<String> = emptySet(),
    val descriptor: VoiceDescriptor? = null,
    /** Short status for the room UI (e.g. "Listening only — no mic"). */
    val statusLine: String? = null,
    /** One-time TV tip naming the mic button / remote key. */
    val showMicHint: Boolean = false,
    /** Last remote key label that toggled the mic (e.g. "mic button"), when known. */
    val remoteKeyLabel: String = "",
    /**
     * True after the user denied RECORD_AUDIO with "Don't ask again" (or equivalent).
     * Room UI offers a button that opens the app's permission settings.
     */
    val showMicPermissionSettings: Boolean = false,
) {
    val showUi: Boolean get() = available && enabledByClient
    val listenOnly: Boolean get() = showUi && capture == VoiceCaptureKind.NONE
    /** Joining or connected — mic/mute controls only make sense then. */
    val inVoice: Boolean
        get() = connection == VoiceConnection.CONNECTED || connection == VoiceConnection.CONNECTING
    /** Mic control while connected (hidden in listen-only and when not in voice). */
    val showMicButton: Boolean get() = showUi && !listenOnly && connection == VoiceConnection.CONNECTED
    /**
     * Single membership control: Leave while in/joining voice, Join while out (after Leave, auto-join
     * off, or a failed connect). Never show both labels at once.
     */
    val showVoiceMembershipButton: Boolean get() = showUi
    /** True → label "Leave voice"; false → "Join voice". */
    val voiceMembershipIsLeave: Boolean get() = inVoice
    /** Clear on-screen “mic is open” affordance. */
    val micLive: Boolean get() = showUi && !listenOnly && talking
}
