package com.yarmiplaytv.voice

import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.VoicePeerPrefs
import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.VoiceClosedReason
import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.syncplay.VoicePeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    private val _captureLevel = MutableStateFlow(0f)
    /** 0…1 post-NS mic level for the activity ring; 0 when muted / not transmitting. */
    val captureLevel: StateFlow<Float> = _captureLevel.asStateFlow()

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
    /**
     * True only when [voiceAutoMuteWhenPlaying] muted the mic for us. Auto-unmute on pause
     * restores only in that case — never overrides a mute the user set themselves.
     */
    private var autoMutedByPlayback = false
    /** Last known "file loaded and playing" from [bindPlayback]; used to auto-mute on voice connect. */
    private var playbackPlaying = false
    /** Serializes mute/transmit engine work so rapid toggles can't reopen capture out of order. */
    private val transmitMutex = Mutex()

    init {
        engine.onDeviceLost = { input ->
            scope.launch {
                if (input) settingsStore.saveVoiceInputDeviceId("")
                else settingsStore.saveVoiceOutputDeviceId("")
            }
        }
        settingsStore.settings.onEach { settings.value = it }.launchIn(scope)
        scope.launch {
            var lastReportedSpeech: Boolean? = null
            while (isActive) {
                val ui = _ui.value
                val live = ui.micControlLive() && !ui.selfMuted
                val next = if (live) engine.captureLevel() else 0f
                if (next != _captureLevel.value) _captureLevel.value = next
                // Speaking names track locally heard audio — clear the same poll as silence (no linger).
                if (ui.connection == VoiceConnection.CONNECTED) {
                    val heard = engine.audibleParticipantIds()
                    if (heard != ui.speakingIds) {
                        val names = ui.peers
                            .filter { it.participantId in heard && it.participantId != ui.participantId }
                            .map { it.username.ifBlank { it.participantId } }
                        _ui.update { it.copy(speakingIds = heard, receivingNames = names) }
                    }
                    val speech = engine.capturingSpeech()
                    if (speech != lastReportedSpeech) {
                        lastReportedSpeech = speech
                        sync.client?.sendVoiceActivity(speech)
                    }
                } else if (lastReportedSpeech != null) {
                    lastReportedSpeech = null
                    if (ui.receivingNames.isNotEmpty() || ui.speakingIds.isNotEmpty()) {
                        _ui.update { it.copy(speakingIds = emptySet(), receivingNames = emptyList()) }
                    }
                }
                delay(33)
            }
        }

        combine(
            sync.room.map {
                val yp = it.yarmiplay
                val disabledMsg = yp.voiceDisabledMessage
                val lacks = yp.session && !yp.voiceAvailable && disabledMsg == null
                Triple(
                    yp.voiceAvailable,
                    yp.voice,
                    disabledMsg to lacks,
                )
            }.distinctUntilChanged(),
            settings.map {
                VoiceReconcileKeys(it.voiceChat, effectiveTalkMode(it), it.voiceTvMicHintShown, it.voiceAutoJoin)
            }.distinctUntilChanged(),
        ) { (available, descriptor, flags), keys ->
            Triple(available, descriptor to keys, flags)
        }.onEach { (available, descAndKeys, flags) ->
            val (descriptor, keys) = descAndKeys
            val (disabledMsg, sessionWithoutVoice) = flags
            _ui.update {
                val next = it.copy(
                    available = available,
                    enabledByClient = keys.voiceChat,
                    serverLacksVoice = sessionWithoutVoice,
                    voiceDisabledOnServer = disabledMsg != null,
                    voiceDisabledMessage = disabledMsg,
                    talkMode = keys.talkMode,
                    descriptor = descriptor,
                    statusLine = when {
                        disabledMsg != null && keys.voiceChat -> disabledMsg
                        sessionWithoutVoice && keys.voiceChat -> it.statusLine
                        else -> it.statusLine
                    },
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
            .map { it.voiceNoiseSuppression }
            .distinctUntilChanged()
            .onEach { engine.setNoiseSuppression(it) }
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
        autoMutedByPlayback = false // manual
        if (!muted) {
            withMicPermission { applySelfMuted(false, fromAuto = false) }
            return
        }
        applySelfMuted(true, fromAuto = false)
    }

    private fun applySelfMuted(muted: Boolean, fromAuto: Boolean) {
        if (!fromAuto) autoMutedByPlayback = false
        val was = selfMuted
        selfMuted = muted
        // Optimistic UI first — capture open/close and cues must not stall the Mic button.
        val mode = _ui.value.talkMode
        val wantTalk = computeWantTransmitting()
        _ui.update {
            it.copy(
                selfMuted = muted,
                talking = wantTalk,
                showMicPermissionSettings = false,
                statusLine = when {
                    muted && fromAuto -> "Mic muted while playing"
                    muted -> "Mic muted"
                    else -> statusFor(it.capture, talkLatched, it.talkMode)
                },
            )
        }
        if (muted) _captureLevel.value = 0f
        applyTransmitEngine(wantTalk, alsoSelfMuted = muted)
        // OPEN_MIC had no cue before; join/leave sounds still come from [onPeers].
        if (was != muted && !fromAuto && mode == VoiceTalkMode.OPEN_MIC) {
            scope.launch(Dispatchers.Default) { playMicCue(!muted) }
        }
    }

    /**
     * Observe player pause/play so [voiceAutoMuteWhenPlaying] / [voiceAutoUnmuteWhenPaused] can run.
     * Call once from [com.yarmiplaytv.AppContainer] with [Player.state].
     */
    fun bindPlayback(playback: StateFlow<PlaybackState>) {
        playback
            .map { it.fileLoaded && !it.paused }
            .distinctUntilChanged()
            .onEach { playing ->
                playbackPlaying = playing
                onPlaybackPlayingChanged(playing)
            }
            .launchIn(scope)
    }

    private fun onPlaybackPlayingChanged(playing: Boolean) {
        if (!_ui.value.showUi || _ui.value.connection != VoiceConnection.CONNECTED) return
        if (playing) {
            if (!settings.value.voiceAutoMuteWhenPlaying) return
            if (!micIsOpenForAutoMute()) return
            muteForAutoPlayback()
            autoMutedByPlayback = true
        } else {
            if (!settings.value.voiceAutoUnmuteWhenPaused) return
            if (!autoMutedByPlayback) return
            unmuteAfterAutoPlayback()
            autoMutedByPlayback = false
        }
    }

    private fun micIsOpenForAutoMute(): Boolean {
        val ui = _ui.value
        if (ui.listenOnly || !ui.showMicButton) return false
        return when (ui.talkMode) {
            VoiceTalkMode.OPEN_MIC -> !selfMuted
            VoiceTalkMode.TOGGLE, VoiceTalkMode.PUSH_TO_TALK -> talkLatched || ui.talking
        }
    }

    private fun muteForAutoPlayback() {
        when (_ui.value.talkMode) {
            VoiceTalkMode.OPEN_MIC -> applySelfMuted(true, fromAuto = true)
            VoiceTalkMode.TOGGLE -> applyTalkLatched(false, fromAuto = true)
            VoiceTalkMode.PUSH_TO_TALK -> {
                pttHeld = false
                updateTransmitting()
            }
        }
    }

    private fun unmuteAfterAutoPlayback() {
        when (_ui.value.talkMode) {
            VoiceTalkMode.OPEN_MIC -> applySelfMuted(false, fromAuto = true)
            VoiceTalkMode.TOGGLE -> applyTalkLatched(true, fromAuto = true)
            VoiceTalkMode.PUSH_TO_TALK -> Unit // hold-to-talk: don't force hold
        }
    }

    fun toggleSelfMuted() {
        // OPEN_MIC shows "unmuted" when selfMuted is false even before RECORD_AUDIO is granted.
        // The first Mic tap must request permission and start talking, not flip into mute.
        if (!selfMuted && !_ui.value.talking) {
            withMicPermission {
                applySelfMuted(false, fromAuto = false)
            }
            return
        }
        setSelfMuted(!selfMuted)
    }

    /**
     * One-tap Mic used by the Room tab and the player bar. Talk-mode aware so desktop OPEN_MIC
     * mutes via [toggleSelfMuted] while phone/TV TOGGLE latches via [toggleTalk].
     */
    fun toggleMic() {
        when (_ui.value.talkMode) {
            VoiceTalkMode.TOGGLE -> toggleTalk()
            VoiceTalkMode.PUSH_TO_TALK -> Unit // hold handled via [setPushToTalkHeld]
            VoiceTalkMode.OPEN_MIC -> toggleSelfMuted()
        }
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
        autoMutedByPlayback = false // manual
        if (on) {
            withMicPermission { applyTalkLatched(true, fromAuto = false) }
            return
        }
        applyTalkLatched(false, fromAuto = false)
    }

    private fun applyTalkLatched(on: Boolean, fromAuto: Boolean) {
        if (!fromAuto) autoMutedByPlayback = false
        val was = talkLatched
        talkLatched = on
        updateTransmitting()
        _ui.update {
            it.copy(
                talkLatched = on,
                statusLine = statusFor(it.capture, on, it.talkMode),
                showMicHint = false,
                showMicPermissionSettings = false,
            )
        }
        if (was != on && !fromAuto) {
            scope.launch(Dispatchers.Default) { playMicCue(on) }
        }
        if (!fromAuto && (on || was)) dismissMicHint()
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
            client.joinVoice(version = desc.version, mux = desc.usesSyncplayMux)
        }
    }

    private fun statusFor(capture: VoiceCaptureKind, latched: Boolean, mode: VoiceTalkMode): String? = when {
        capture == VoiceCaptureKind.NONE -> "No microphone available — listening only"
        latched -> "Mic live"
        mode == VoiceTalkMode.TOGGLE -> "Mic off — press Mic to talk"
        mode == VoiceTalkMode.PUSH_TO_TALK -> "Hold to talk"
        else -> null
    }

    private fun onEvent(event: SyncplayEvent) {
        when (event) {
            is SyncplayEvent.VoiceJoined -> scope.launch { onJoined(event) }
            is SyncplayEvent.VoiceClosed -> onClosed(event.reason, event.message)
            is SyncplayEvent.VoicePeersChanged -> onPeers(event.peers)
            // Speaking overlay uses locally heard PCM (polled); ignore protocol speaking flags for UI.
            is SyncplayEvent.VoiceActivity -> Unit
            is SyncplayEvent.Disconnected, SyncplayEvent.SessionEnded -> tearDown(VoiceConnection.OFF, null)
            else -> Unit
        }
    }

    private fun onClosed(reason: VoiceClosedReason, message: String? = null) {
        when (reason) {
            VoiceClosedReason.REPLACED -> {
                // Wait for the next voiceJoined with new token/key; keep UI in CONNECTING.
                awaitingReplace = true
                engine.disconnect()
                sync.client?.voiceMux()?.setOnPacket(null)
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
            VoiceClosedReason.DISABLED ->
                tearDown(
                    VoiceConnection.OFF,
                    message?.takeIf { it.isNotBlank() } ?: "voice disabled on this server",
                )
            VoiceClosedReason.VANILLA ->
                tearDown(VoiceConnection.OFF, "Voice chat turned off on the server")
            VoiceClosedReason.VERSION -> {
                lastFailAtMs = System.currentTimeMillis()
                tearDown(VoiceConnection.FAILED, "Voice version not supported")
            }
            VoiceClosedReason.ERROR -> {
                lastFailAtMs = System.currentTimeMillis()
                tearDown(VoiceConnection.FAILED, message?.takeIf { it.isNotBlank() } ?: "Voice disconnected")
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
        val room = sync.room.value
        val desc = room.yarmiplay.voice
        val syncplayPort = event.port
            ?: desc?.port
            ?: client.config.port.takeIf { it > 0 }
            ?: 8999
        val secure = room.tls || client.session.value?.baseUrl?.startsWith("https") == true
        val useMux = event.usesSyncplayMux || desc?.usesSyncplayMux == true
        val syncplayMediaOnly = useMux || desc?.let {
            it.version >= 2 || it.transport.equals("yv1-syncplay", ignoreCase = true)
        } == true
        val mux = if (useMux) client.voiceMux() else null
        val params = VoiceMediaParams(
            host = hostOnly,
            token = event.token,
            key = event.key,
            provider = event.provider,
            appId = event.appId,
            lobbyId = event.lobbyId,
            lobbySecret = event.lobbySecret,
            udpPort = event.udpPort,
            tcpPort = event.tcpPort,
            fingerprint = event.fingerprint.ifEmpty { desc?.fingerprint.orEmpty() },
            wsPath = event.wsPath.ifEmpty { desc?.wsPath ?: "/yarmiplay/voice" },
            maxBitrate = event.maxBitrate,
            fec = event.fec,
            dtx = event.dtx,
            clockRate = event.clockRate,
            channels = event.channels,
            ptimeMs = event.ptimeMs,
            syncplayPort = syncplayPort,
            secure = secure,
            syncplayMediaOnly = syncplayMediaOnly,
            syncplayMux = mux,
        )
        val ok = engine.connect(params)
        if (!ok) {
            lastFailAtMs = System.currentTimeMillis()
            mux?.setOnPacket(null)
            tearDown(
                VoiceConnection.FAILED,
                if (syncplayMediaOnly) "Couldn't open voice on the Syncplay connection"
                else "Couldn't connect voice",
            )
            client.leaveVoice()
            scheduleRetry()
            return
        }
        talkLatched = false
        engine.setSelfMuted(selfMuted)
        val devices = settings.value
        engine.setInputGain(devices.voiceInputGain)
        engine.setOutputGain(devices.voiceOutputGain)
        engine.setNoiseSuppression(devices.voiceNoiseSuppression)
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
        // bindPlayback may have emitted "playing" before we were connected; apply auto-mute now.
        onPlaybackPlayingChanged(playbackPlaying)
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
        // Keep peers; speakingIds stay driven by audible receive energy (instant clear on silence).
        _ui.update { it.copy(peers = peers) }
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

    private fun computeWantTransmitting(): Boolean {
        val ui = _ui.value
        val mode = ui.talkMode
        return when {
            selfMuted -> false
            ui.connection != VoiceConnection.CONNECTED -> false
            ui.capture == VoiceCaptureKind.NONE -> false
            !engine.hasMicPermission() && !micPermissions.granted() -> false
            mode == VoiceTalkMode.TOGGLE -> talkLatched
            mode == VoiceTalkMode.PUSH_TO_TALK -> pttHeld
            else -> true // OPEN_MIC
        }
    }

    private fun updateTransmitting() {
        val ui = _ui.value
        val mode = ui.talkMode
        val want = computeWantTransmitting()
        _ui.update {
            it.copy(
                talking = want,
                talkLatched = talkLatched,
                statusLine = statusFor(it.capture, latched = talkLatched, mode),
            )
        }
        if (!want) _captureLevel.value = 0f
        applyTransmitEngine(want, alsoSelfMuted = null)
        // voiceActivity is reported from the capture/heard poll (actual speech), not mic-open.
    }

    /**
     * Applies mute/transmit to the engine off the UI path. [alsoSelfMuted] non-null also updates
     * [VoiceEngine.setSelfMuted] in the same serialized step (avoids double capture open/close).
     */
    private fun applyTransmitEngine(wantTransmit: Boolean, alsoSelfMuted: Boolean?) {
        scope.launch(Dispatchers.Default) {
            transmitMutex.withLock {
                if (alsoSelfMuted != null) engine.setSelfMuted(alsoSelfMuted)
                engine.setTransmitting(wantTransmit)
            }
        }
    }

    private fun tearDown(connection: VoiceConnection, status: String?) {
        connectJob?.cancel()
        connectJob = null
        awaitingReplace = false
        sync.client?.voiceMux()?.setOnPacket(null)
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
                receivingNames = emptyList(),
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
