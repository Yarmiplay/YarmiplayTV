package com.yarmiplaytv.voice

import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.VoicePeerPrefs
import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.VoiceClosedReason
import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.syncplay.VoicePeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * TV uses tap-to-toggle (mic starts off); phones/desktop use open-mic or push-to-talk.
 */
class VoiceController(
    private val sync: SyncController,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val engine: VoiceEngine,
    private val micPermissions: MicPermissions = AlwaysGrantedMicPermissions,
    private val deviceKind: () -> DeviceKind,
) {
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private val settings = MutableStateFlow(AppSettings())
    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var lastPeers: List<VoicePeer> = emptyList()
    private var pttHeld = false
    private var talkLatched = false
    private var selfMuted = false
    private var awaitingReplace = false
    private var lastFailAtMs = 0L
    /** After [leave], stay out of voice until [join] (reconcile must not auto-rejoin). */
    private var leftByUser = false

    init {
        engine.onDeviceLost = { input ->
            scope.launch {
                if (input) settingsStore.saveVoiceInputDeviceId("")
                else settingsStore.saveVoiceOutputDeviceId("")
            }
        }
        settingsStore.settings.onEach { settings.value = it }.launchIn(scope)

        combine(
            sync.room.map { it.yarmiplay.voiceAvailable to it.yarmiplay.voice }.distinctUntilChanged(),
            settings.map {
                VoiceReconcileKeys(it.voiceChat, effectiveTalkMode(it), it.voiceTvMicHintShown, it.voiceAutoJoin)
            }.distinctUntilChanged(),
        ) { (available, descriptor), keys ->
            available to (descriptor to keys)
        }.onEach { (available, descAndKeys) ->
            val (descriptor, keys) = descAndKeys
            _ui.update {
                val next = it.copy(
                    available = available,
                    enabledByClient = keys.voiceChat,
                    talkMode = keys.talkMode,
                    descriptor = descriptor,
                )
                maybeOfferHint(next, keys.hintShown)
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

        settings
            .map { it.voiceInputDeviceId to it.voiceOutputDeviceId }
            .distinctUntilChanged()
            .onEach { (input, output) ->
                engine.setInputDeviceId(input.ifEmpty { null })
                engine.setOutputDeviceId(output.ifEmpty { null })
            }
            .launchIn(scope)
    }

    /** Mic routes for the voice settings picker (includes System default). */
    fun listInputDevices(): List<VoiceAudioDevice> = engine.listInputDevices()

    /** Speaker routes for the voice settings picker (includes System default). */
    fun listOutputDevices(): List<VoiceAudioDevice> = engine.listOutputDevices()

    fun setInputDeviceId(id: String) {
        scope.launch { settingsStore.saveVoiceInputDeviceId(id) }
    }

    fun setOutputDeviceId(id: String) {
        scope.launch { settingsStore.saveVoiceOutputDeviceId(id) }
    }

    fun setSelfMuted(muted: Boolean) {
        if (!muted) {
            withMicPermission { applySelfMuted(false) }
            return
        }
        applySelfMuted(true)
    }

    private fun applySelfMuted(muted: Boolean) {
        selfMuted = muted
        engine.setSelfMuted(muted)
        _ui.update { it.copy(selfMuted = muted, showMicPermissionSettings = false) }
        updateTransmitting()
    }

    fun toggleSelfMuted() {
        // OPEN_MIC shows "unmuted" when selfMuted is false even before RECORD_AUDIO is granted.
        // The first Mic tap must request permission and start talking, not flip into mute.
        if (!selfMuted && !_ui.value.talking) {
            withMicPermission {
                applySelfMuted(false)
            }
            return
        }
        setSelfMuted(!selfMuted)
    }

    /** Hold for push-to-talk on phone/desktop. Not used on TV (tap toggle). */
    fun setPushToTalkHeld(held: Boolean) {
        if (held) {
            withMicPermission { applyPushToTalkHeld(true) }
            return
        }
        applyPushToTalkHeld(false)
    }

    private fun applyPushToTalkHeld(held: Boolean) {
        pttHeld = held
        updateTransmitting()
    }

    /**
     * Tap toggle for TV (and any TOGGLE mode): open mic ↔ close mic.
     * Starts off on join. No-op when listen-only or voice UI hidden.
     * Requests RECORD_AUDIO on Android the first time the mic is turned on.
     */
    fun toggleTalk() {
        val state = _ui.value
        if (!state.showUi || state.listenOnly) return
        if (state.connection != VoiceConnection.CONNECTED && state.connection != VoiceConnection.CONNECTING) return
        setTalkLatched(!talkLatched)
    }

    fun setTalkLatched(on: Boolean) {
        val state = _ui.value
        if (!state.showUi || state.listenOnly) return
        if (on) {
            withMicPermission { applyTalkLatched(true) }
            return
        }
        applyTalkLatched(false)
    }

    private fun applyTalkLatched(on: Boolean) {
        val was = talkLatched
        talkLatched = on
        if (was != on) playMicCue(on)
        updateTransmitting()
        _ui.update {
            it.copy(
                talkLatched = on,
                statusLine = statusFor(it.capture, on, it.talkMode),
                showMicHint = false,
                showMicPermissionSettings = false,
            )
        }
        if (on || was) dismissMicHint()
    }

    /**
     * Runs [action] once mic permission is held. On Android, shows the system prompt from a Mic tap;
     * on deny sets a short status (and [VoiceUiState.showMicPermissionSettings] when permanent).
     */
    private fun withMicPermission(action: () -> Unit) {
        if (micPermissions.granted()) {
            engine.onMicPermissionGranted()
            action()
            return
        }
        micPermissions.request { granted ->
            if (granted) {
                engine.onMicPermissionGranted()
                _ui.update { it.copy(showMicPermissionSettings = false) }
                action()
            } else {
                val permanent = micPermissions.permanentlyDenied()
                _ui.update {
                    it.copy(
                        statusLine = "Microphone permission needed to talk",
                        showMicPermissionSettings = permanent,
                    )
                }
            }
        }
    }

    fun dismissMicPermissionSettings() {
        _ui.update { it.copy(showMicPermissionSettings = false) }
    }

    fun openMicPermissionSettings() {
        micPermissions.openAppSettings()
        _ui.update { it.copy(showMicPermissionSettings = false) }
    }

    /** Record which remote key successfully toggled the mic (for the one-time hint label). */
    fun noteRemoteToggleKey(label: String) {
        if (label.isBlank()) return
        _ui.update { it.copy(remoteKeyLabel = label) }
        scope.launch { settingsStore.saveVoiceTvRemoteKeyLabel(label) }
    }

    fun dismissMicHint() {
        if (!_ui.value.showMicHint && settings.value.voiceTvMicHintShown) {
            _ui.update { it.copy(showMicHint = false) }
            return
        }
        _ui.update { it.copy(showMicHint = false) }
        scope.launch { settingsStore.saveVoiceTvMicHintShown(true) }
    }

    fun setTalkMode(mode: VoiceTalkMode) {
        scope.launch { settingsStore.saveVoiceTalkMode(mode) }
    }

    fun setAutoJoin(on: Boolean) {
        scope.launch { settingsStore.saveVoiceAutoJoin(on) }
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
        leftByUser = true
        retryJob?.cancel()
        sync.client?.leaveVoice()
        tearDown(VoiceConnection.OFF, "Left voice")
    }

    /**
     * Rejoin room voice after [leave] (or retry after FAILED). No-op when the server or client
     * voice toggle is off.
     */
    fun join() {
        val room = sync.room.value
        if (!room.yarmiplay.voiceAvailable || !settings.value.voiceChat) return
        leftByUser = false
        lastFailAtMs = 0L
        startJoin()
    }

    /**
     * TV, phone and tablet always use one-tap [VoiceTalkMode.TOGGLE] (mic starts off; capture only while
     * latched on) so the privacy indicator and player control match. Desktop keeps the saved preference.
     */
    private fun effectiveTalkMode(settings: AppSettings): VoiceTalkMode =
        when (deviceKind()) {
            DeviceKind.DESKTOP -> settings.voiceTalkMode
            DeviceKind.TV, DeviceKind.PHONE, DeviceKind.TABLET -> VoiceTalkMode.TOGGLE
        }

    private fun maybeOfferHint(state: VoiceUiState, hintShown: Boolean): VoiceUiState {
        val wantHint = deviceKind() == DeviceKind.TV &&
            state.showUi &&
            !state.listenOnly &&
            !hintShown &&
            state.connection == VoiceConnection.CONNECTED
        val label = settings.value.voiceTvRemoteKeyLabel.ifBlank { state.remoteKeyLabel }
        return state.copy(
            showMicHint = wantHint,
            remoteKeyLabel = label,
            talkLatched = talkLatched,
        )
    }

    private fun reconcileSession() {
        val room = sync.room.value
        val want = room.yarmiplay.voiceAvailable && settings.value.voiceChat
        val state = _ui.value
        if (!want) {
            leftByUser = false
            if (state.connection != VoiceConnection.OFF) {
                sync.client?.leaveVoice()
                tearDown(VoiceConnection.OFF, null)
            }
            return
        }
        if (awaitingReplace || leftByUser) return
        if (state.connection == VoiceConnection.OFF || state.connection == VoiceConnection.FAILED) {
            if (settings.value.voiceAutoJoin) {
                startJoin()
            } else if (state.statusLine == null || state.connection == VoiceConnection.OFF) {
                // Auto-join off: stay out and show Join voice (mic stays hidden).
                _ui.update {
                    it.copy(
                        connection = VoiceConnection.OFF,
                        statusLine = it.statusLine ?: "Not in voice — tap Join voice to talk",
                    )
                }
            }
        }
    }

    private fun startJoin() {
        val client = sync.client ?: return
        val desc = sync.room.value.yarmiplay.voice ?: return
        val now = System.currentTimeMillis()
        if (now - lastFailAtMs < 10_000L && _ui.value.connection == VoiceConnection.FAILED) return
        connectJob?.cancel()
        talkLatched = false
        pttHeld = false
        connectJob = scope.launch {
            _ui.update {
                it.copy(
                    connection = VoiceConnection.CONNECTING,
                    statusLine = "Connecting voice…",
                    talkLatched = false,
                    talking = false,
                    showMicPermissionSettings = false,
                )
            }
            val capture = engine.detectCapture()
            _ui.update {
                it.copy(
                    capture = capture,
                    talkLatched = false,
                    statusLine = statusFor(capture, latched = false, effectiveTalkMode(settings.value)),
                )
            }
            client.joinVoice(desc.version)
        }
    }

    private fun statusFor(capture: VoiceCaptureKind, latched: Boolean, mode: VoiceTalkMode): String? = when {
        capture == VoiceCaptureKind.NONE -> "Listening only — no mic on this device. Join from your phone to talk."
        latched -> "Mic live"
        mode == VoiceTalkMode.TOGGLE -> "Mic off — press Mic to talk"
        mode == VoiceTalkMode.PUSH_TO_TALK -> "Hold to talk"
        else -> null
    }

    private fun onEvent(event: SyncplayEvent) {
        when (event) {
            is SyncplayEvent.VoiceJoined -> scope.launch { onJoined(event) }
            is SyncplayEvent.VoiceClosed -> onClosed(event.reason)
            is SyncplayEvent.VoicePeersChanged -> onPeers(event.peers)
            is SyncplayEvent.VoiceActivity -> _ui.update {
                val next = if (event.speaking) it.speakingIds + event.participantId else it.speakingIds - event.participantId
                it.copy(speakingIds = next)
            }
            is SyncplayEvent.Disconnected, SyncplayEvent.SessionEnded -> tearDown(VoiceConnection.OFF, null)
            else -> Unit
        }
    }

    private fun onClosed(reason: VoiceClosedReason) {
        when (reason) {
            VoiceClosedReason.REPLACED -> {
                // Wait for the next voiceJoined with new token/key; keep UI in CONNECTING.
                awaitingReplace = true
                engine.disconnect()
                _ui.update {
                    it.copy(
                        connection = VoiceConnection.CONNECTING,
                        statusLine = "Reconnecting voice…",
                        talking = false,
                        talkLatched = false,
                    )
                }
            }
            VoiceClosedReason.ROOM -> {
                leftByUser = false
                tearDown(VoiceConnection.OFF, null)
                // Retry immediately in the new Syncplay room if we still want voice.
                if (sync.room.value.yarmiplay.voiceAvailable && settings.value.voiceChat) {
                    startJoin()
                }
            }
            VoiceClosedReason.FULL -> {
                lastFailAtMs = System.currentTimeMillis()
                tearDown(VoiceConnection.FAILED, "Voice room is full")
            }
            VoiceClosedReason.DISABLED, VoiceClosedReason.VANILLA ->
                tearDown(VoiceConnection.OFF, "Voice chat turned off on the server")
            VoiceClosedReason.VERSION -> {
                lastFailAtMs = System.currentTimeMillis()
                tearDown(VoiceConnection.FAILED, "Voice version not supported")
            }
            VoiceClosedReason.ERROR -> {
                lastFailAtMs = System.currentTimeMillis()
                tearDown(VoiceConnection.FAILED, "Voice disconnected")
                scheduleRetry()
            }
            VoiceClosedReason.LEFT -> tearDown(VoiceConnection.OFF, "Left voice")
            VoiceClosedReason.UNKNOWN -> tearDown(VoiceConnection.OFF, "Voice disconnected")
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(10_000)
            if (!leftByUser &&
                _ui.value.connection == VoiceConnection.FAILED &&
                sync.room.value.yarmiplay.voiceAvailable &&
                settings.value.voiceChat
            ) {
                startJoin()
            }
        }
    }

    private suspend fun onJoined(event: SyncplayEvent.VoiceJoined) {
        awaitingReplace = false
        val hostOnly = sync.server?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return
        val client = sync.client ?: return
        val desc = sync.room.value.yarmiplay.voice
        val params = VoiceMediaParams(
            host = hostOnly,
            token = event.token,
            key = event.key,
            udpPort = event.udpPort,
            tcpPort = event.tcpPort,
            fingerprint = event.fingerprint.ifEmpty { desc?.fingerprint.orEmpty() },
            wsPath = event.wsPath,
            maxBitrate = event.maxBitrate,
            fec = event.fec,
            dtx = event.dtx,
            clockRate = event.clockRate,
            channels = event.channels,
            ptimeMs = event.ptimeMs,
        )
        val ok = engine.connect(params)
        if (!ok) {
            lastFailAtMs = System.currentTimeMillis()
            tearDown(VoiceConnection.FAILED, "Couldn't connect voice")
            client.leaveVoice()
            scheduleRetry()
            return
        }
        talkLatched = false
        engine.setSelfMuted(selfMuted)
        val devices = settings.value
        engine.setInputGain(devices.voiceInputGain)
        engine.setOutputGain(devices.voiceOutputGain)
        engine.setInputDeviceId(devices.voiceInputDeviceId.ifEmpty { null })
        engine.setOutputDeviceId(devices.voiceOutputDeviceId.ifEmpty { null })
        val hintShown = devices.voiceTvMicHintShown
        _ui.update {
            val connected = it.copy(
                connection = VoiceConnection.CONNECTED,
                participantId = event.participantId,
                talkLatched = false,
                statusLine = statusFor(it.capture, latched = false, it.talkMode),
                remoteKeyLabel = devices.voiceTvRemoteKeyLabel.ifBlank { it.remoteKeyLabel },
            )
            maybeOfferHint(connected, hintShown)
        }
        updateTransmitting()
    }

    private fun onPeers(peers: List<VoicePeer>) {
        val s = settings.value
        val selfId = _ui.value.participantId
        val othersBefore = lastPeers.map { it.participantId }.filter { it != selfId }.toSet()
        val othersAfter = peers.map { it.participantId }.filter { it != selfId }.toSet()
        if (s.voiceJoinLeaveSounds) {
            val vol = s.voiceJoinLeaveVolume
            if ((othersAfter - othersBefore).isNotEmpty()) engine.playSound(VoiceSound.JOIN, vol)
            if ((othersBefore - othersAfter).isNotEmpty() && lastPeers.isNotEmpty()) {
                engine.playSound(VoiceSound.LEAVE, vol)
            }
        }
        lastPeers = peers
        val speaking = peers.filter { it.speaking }.map { it.participantId }.toSet()
        _ui.update { it.copy(peers = peers, speakingIds = speaking) }
        peers.forEach { applyPeerAudio(it.username, it.participantId) }
    }

    private fun applyPeerAudio(username: String, participantId: String? = null) {
        val pref = settings.value.voicePeerPrefs[username] ?: VoicePeerPrefs()
        val id = participantId ?: _ui.value.peers.firstOrNull { it.username == username }?.participantId ?: return
        engine.setPeerMuted(id, pref.muted)
        engine.setPeerVolume(id, pref.volume)
    }

    private fun playMicCue(on: Boolean) {
        val vol = (settings.value.voiceJoinLeaveVolume * 0.7f).coerceIn(0f, 1f)
        engine.playSound(if (on) VoiceSound.MIC_ON else VoiceSound.MIC_OFF, vol)
    }

    private fun updateTransmitting() {
        val ui = _ui.value
        val mode = ui.talkMode
        val want = when {
            selfMuted -> false
            ui.connection != VoiceConnection.CONNECTED -> false
            ui.capture == VoiceCaptureKind.NONE -> false
            !engine.hasMicPermission() && !micPermissions.granted() -> false
            mode == VoiceTalkMode.TOGGLE -> talkLatched
            mode == VoiceTalkMode.PUSH_TO_TALK -> pttHeld
            else -> true // OPEN_MIC
        }
        engine.setTransmitting(want)
        _ui.update {
            it.copy(
                talking = want,
                talkLatched = talkLatched,
                statusLine = statusFor(it.capture, latched = talkLatched, mode),
            )
        }
        if (ui.connection == VoiceConnection.CONNECTED) {
            sync.client?.sendVoiceActivity(want)
        }
    }

    private fun tearDown(connection: VoiceConnection, status: String?) {
        connectJob?.cancel()
        connectJob = null
        awaitingReplace = false
        engine.disconnect()
        lastPeers = emptyList()
        pttHeld = false
        talkLatched = false
        _ui.update {
            it.copy(
                connection = connection,
                participantId = null,
                peers = emptyList(),
                speakingIds = emptySet(),
                talking = false,
                talkLatched = false,
                showMicHint = false,
                showMicPermissionSettings = false,
                statusLine = status,
            )
        }
    }
}

private data class VoiceReconcileKeys(
    val voiceChat: Boolean,
    val talkMode: VoiceTalkMode,
    val hintShown: Boolean,
    val autoJoin: Boolean,
)
