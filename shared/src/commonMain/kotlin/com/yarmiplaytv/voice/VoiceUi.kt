package com.yarmiplaytv.voice

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.VoicePeerPrefs
import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.syncplay.VoicePeer

/** Status line for the room panel when voice UI is shown. */
fun voiceBanner(ui: VoiceUiState): String? = when {
    !ui.showUi -> null
    ui.connection == VoiceConnection.CONNECTING -> "Connecting voice…"
    ui.connection == VoiceConnection.FAILED -> ui.statusLine ?: "Voice couldn't connect"
    ui.connection == VoiceConnection.OFF -> ui.statusLine
    ui.listenOnly -> "No microphone available — listening only"
    ui.micLive -> "Mic live"
    ui.talkMode == VoiceTalkMode.TOGGLE -> ui.statusLine ?: "Mic off — press Mic to talk"
    ui.talkMode == VoiceTalkMode.PUSH_TO_TALK || ui.capture == VoiceCaptureKind.PUSH_TO_TALK ->
        ui.statusLine ?: "Hold to talk"
    ui.connection == VoiceConnection.CONNECTED -> "Voice connected"
    else -> ui.statusLine
}

/**
 * Whether the Mic control should look "on" — shared by Room tab and player bar.
 * OPEN_MIC keys off [VoiceUiState.selfMuted]; TOGGLE/PTT use [VoiceUiState.micLive].
 */
fun VoiceUiState.micControlLive(): Boolean {
    if (!showMicButton) return false
    return when (talkMode) {
        VoiceTalkMode.OPEN_MIC -> !selfMuted && connection == VoiceConnection.CONNECTED
        VoiceTalkMode.TOGGLE, VoiceTalkMode.PUSH_TO_TALK -> micLive
    }
}

/** Short label next to the Mic icon (Room tab / player). */
fun VoiceUiState.micControlLabel(): String = when {
    talkMode == VoiceTalkMode.OPEN_MIC && selfMuted -> "Mic muted"
    micControlLive() -> "Mic on"
    else -> "Mic off"
}

/**
 * Mic button accessibility / tooltip. Desktop and web append Ctrl+M (Cmd+M is not used —
 * it clashes with macOS window minimize).
 */
fun VoiceUiState.micControlDescription(showKeyboardShortcut: Boolean): String {
    val base = when {
        talkMode == VoiceTalkMode.OPEN_MIC && selfMuted -> "Unmute mic"
        micControlLive() -> "Mute mic"
        else -> "Unmute mic"
    }
    return if (showKeyboardShortcut) "$base (Ctrl+M)" else "$base — tap to toggle"
}

fun VoiceUiState.isSpeaking(username: String): Boolean {
    val id = peers.firstOrNull { it.username == username }?.participantId ?: return false
    return id in speakingIds
}

fun VoiceUiState.peerSpeaking(participantId: String?): Boolean =
    participantId != null && participantId in speakingIds

fun AppSettings.peerPrefs(username: String): VoicePeerPrefs =
    voicePeerPrefs[username] ?: VoicePeerPrefs()

fun VoiceUiState.selfPeerId(): String? = participantId

/** Volume step for TV D-pad friendly controls. */
fun steppedVoiceVolume(current: Float, up: Boolean): Float {
    val step = 0.25f
    val next = if (up) current + step else current - step
    return (next * 4f).toInt().coerceIn(0, 8) / 4f
}

fun VoicePeer.displaySpeaking(ui: VoiceUiState): Boolean =
    participantId in ui.speakingIds
