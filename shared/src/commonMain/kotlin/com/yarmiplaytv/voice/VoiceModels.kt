package com.yarmiplaytv.voice

import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.syncplay.VoiceDescriptor
import com.yarmiplaytv.syncplay.VoicePeer

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
    val capture: VoiceCaptureKind = VoiceCaptureKind.NONE,
    val talkMode: VoiceTalkMode = VoiceTalkMode.OPEN_MIC,
    val participantId: String? = null,
    val peers: List<VoicePeer> = emptyList(),
    val speakingIds: Set<String> = emptySet(),
    val descriptor: VoiceDescriptor? = null,
    /** Short status for the room UI (e.g. "Listening only — no mic"). */
    val statusLine: String? = null,
) {
    val showUi: Boolean get() = available && enabledByClient
    val listenOnly: Boolean get() = showUi && capture == VoiceCaptureKind.NONE
}
