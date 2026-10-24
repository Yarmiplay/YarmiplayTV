package com.yarmiplaytv.voice

import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.VoicePeerPrefs
import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.VoiceClosedReason
import com.yarmiplaytv.syncplay.VoicePeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Joins room voice when the server offers it and the client setting is on.
 * Local mute/volume of others are persisted; chat block stays separate.
 */
class VoiceController(
    private val sync: SyncController,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val engine: VoiceEngine,
    private val deviceKind: () -> DeviceKind,
) {
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private val settings = MutableStateFlow(AppSettings())
    private var connectJob: Job? = null
    private var lastPeers: List<VoicePeer> = emptyList()
    private var pttHeld = false
    private var selfMuted = false

    init {
        settingsStore.settings.onEach { settings.value = it }.launchIn(scope)

        combine(
            sync.room.map { it.yarmiplay.voiceAvailable to it.yarmiplay.voice }.distinctUntilChanged(),
            settings.map { it.voiceChat to effectiveTalkMode(it) }.distinctUntilChanged(),
        ) { (available, descriptor), (enabled, mode) ->
            Triple(available, enabled, mode to descriptor)
        }.onEach { (available, enabled, modeAndDesc) ->
            val (mode, descriptor) = modeAndDesc
            _ui.update {
                it.copy(
                    available = available,
                    enabledByClient = enabled,
                    talkMode = mode,
                    descriptor = descriptor,
                    capture = it.capture,
                )
            }
            reconcileSession()
        }.launchIn(scope)

        sync.events.onEach(::onEvent).launchIn(scope)

        settings
            .map { it.voiceInputGain to it.voiceOutputGain }
            .distinctUntilChanged()
            .onEach { (input, output) ->
                engine.setInputGain(input)
                engine.setOutputGain(output)
            }
            .launchIn(scope)
    }

    fun setSelfMuted(muted: Boolean) {
        selfMuted = muted
        engine.setSelfMuted(muted)
        _ui.update { it.copy(selfMuted = muted) }
        updateTransmitting()
    }

    fun toggleSelfMuted() = setSelfMuted(!selfMuted)

    /** Hold for push-to-talk (TV remote / button). */
    fun setPushToTalkHeld(held: Boolean) {
        pttHeld = held
        updateTransmitting()
    }

    fun setTalkMode(mode: VoiceTalkMode) {
        scope.launch { settingsStore.saveVoiceTalkMode(mode) }
    }

    fun setPeerMuted(username: String, muted: Boolean) {
        scope.launch {
            val cur = settingsStore.current().voicePeerPrefs[username] ?: VoicePeerPrefs()
            settingsStore.saveVoicePeerPref(username, cur.copy(muted = muted))
            applyPeerAudio(username)
        }
    }

    fun setPeerVolume(username: String, volume: Float) {
        scope.launch {
            val cur = settingsStore.current().voicePeerPrefs[username] ?: VoicePeerPrefs()
            settingsStore.saveVoicePeerPref(username, cur.copy(volume = volume.coerceIn(0f, 2f)))
            applyPeerAudio(username)
        }
    }

    fun leave() {
        sync.client?.leaveVoice()
        tearDown(VoiceConnection.OFF, "Left voice")
    }

    private fun effectiveTalkMode(settings: AppSettings): VoiceTalkMode =
        if (deviceKind() == DeviceKind.TV) VoiceTalkMode.PUSH_TO_TALK else settings.voiceTalkMode

    private fun reconcileSession() {
        val room = sync.room.value
        val want = room.yarmiplay.voiceAvailable && settings.value.voiceChat
        val state = _ui.value
        if (!want) {
            if (state.connection != VoiceConnection.OFF) {
                sync.client?.leaveVoice()
                tearDown(VoiceConnection.OFF, null)
            }
            return
        }
        if (state.connection == VoiceConnection.OFF || state.connection == VoiceConnection.FAILED) {
            join()
        }
    }

    private fun join() {
        val client = sync.client ?: return
        val desc = sync.room.value.yarmiplay.voice ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            _ui.update { it.copy(connection = VoiceConnection.CONNECTING, statusLine = "Connecting voice…") }
            val capture = engine.detectCapture().let { detected ->
                when {
                    deviceKind() == DeviceKind.TV && detected != VoiceCaptureKind.NONE -> VoiceCaptureKind.PUSH_TO_TALK
                    else -> detected
                }
            }
            _ui.update {
                it.copy(
                    capture = capture,
                    statusLine = when (capture) {
                        VoiceCaptureKind.NONE -> "Listening only — no mic on this device"
                        VoiceCaptureKind.PUSH_TO_TALK -> if (deviceKind() == DeviceKind.TV) "Hold to talk" else null
                        VoiceCaptureKind.OPEN -> null
                    },
                )
            }
            client.joinVoice(desc.version)
        }
    }

    private fun onEvent(event: SyncplayEvent) {
        when (event) {
            is SyncplayEvent.VoiceJoined -> scope.launch { onJoined(event) }
            is SyncplayEvent.VoiceClosed -> tearDown(
                VoiceConnection.OFF,
                when (event.reason) {
                    VoiceClosedReason.FULL -> "Voice room is full"
                    VoiceClosedReason.TOGGLE_OFF -> "Voice chat turned off on the server"
                    VoiceClosedReason.ROOM -> null
                    else -> "Voice disconnected"
                },
            )
            is SyncplayEvent.VoicePeersChanged -> onPeers(event.peers)
            is SyncplayEvent.VoiceActivity -> _ui.update {
                val next = if (event.speaking) it.speakingIds + event.participantId else it.speakingIds - event.participantId
                it.copy(speakingIds = next)
            }
            is SyncplayEvent.VoiceSignal -> engine.onSignalIn(event.type, event.payload)
            is SyncplayEvent.Disconnected, SyncplayEvent.SessionEnded -> tearDown(VoiceConnection.OFF, null)
            else -> Unit
        }
    }

    private suspend fun onJoined(event: SyncplayEvent.VoiceJoined) {
        val desc = sync.room.value.yarmiplay.voice ?: return
        val hostOnly = sync.server?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return
        val token = sync.session.value?.token
        val client = sync.client ?: return
        val ok = engine.connect(hostOnly, desc, token) { type, payload, key ->
            client.sendVoiceSignal(type, payload, key)
        }
        if (!ok) {
            tearDown(VoiceConnection.FAILED, "Couldn't connect voice")
            client.leaveVoice()
            return
        }
        engine.setSelfMuted(selfMuted)
        val devices = settings.value
        engine.setInputGain(devices.voiceInputGain)
        engine.setOutputGain(devices.voiceOutputGain)
        engine.setInputDeviceId(devices.voiceInputDeviceId.ifEmpty { null })
        engine.setOutputDeviceId(devices.voiceOutputDeviceId.ifEmpty { null })
        _ui.update {
            it.copy(
                connection = VoiceConnection.CONNECTED,
                participantId = event.participantId,
                statusLine = if (it.capture == VoiceCaptureKind.NONE) "Listening only — no mic on this device" else it.statusLine,
            )
        }
        updateTransmitting()
    }

    private fun onPeers(peers: List<VoicePeer>) {
        val s = settings.value
        if (s.voiceJoinLeaveSounds) {
            val before = lastPeers.map { it.participantId }.toSet()
            val after = peers.map { it.participantId }.toSet()
            val vol = s.voiceJoinLeaveVolume
            if ((after - before).isNotEmpty()) engine.playSound(VoiceSound.JOIN, vol)
            if ((before - after).isNotEmpty() && lastPeers.isNotEmpty()) engine.playSound(VoiceSound.LEAVE, vol)
        }
        lastPeers = peers
        _ui.update { it.copy(peers = peers) }
        peers.forEach { applyPeerAudio(it.username, it.participantId) }
    }

    private fun applyPeerAudio(username: String, participantId: String? = null) {
        val pref = settings.value.voicePeerPrefs[username] ?: VoicePeerPrefs()
        val id = participantId ?: _ui.value.peers.firstOrNull { it.username == username }?.participantId ?: return
        engine.setPeerMuted(id, pref.muted)
        engine.setPeerVolume(id, pref.volume)
    }

    private fun updateTransmitting() {
        val ui = _ui.value
        val mode = ui.talkMode
        val want = when {
            selfMuted -> false
            ui.connection != VoiceConnection.CONNECTED -> false
            ui.capture == VoiceCaptureKind.NONE -> false
            mode == VoiceTalkMode.PUSH_TO_TALK || ui.capture == VoiceCaptureKind.PUSH_TO_TALK -> pttHeld
            else -> true
        }
        engine.setTransmitting(want)
        _ui.update { it.copy(talking = want) }
        if (ui.connection == VoiceConnection.CONNECTED) {
            sync.client?.sendVoiceActivity(want)
        }
    }

    private fun tearDown(connection: VoiceConnection, status: String?) {
        connectJob?.cancel()
        connectJob = null
        engine.disconnect()
        lastPeers = emptyList()
        pttHeld = false
        _ui.update {
            it.copy(
                connection = connection,
                participantId = null,
                peers = emptyList(),
                speakingIds = emptySet(),
                talking = false,
                statusLine = status,
            )
        }
    }
}
