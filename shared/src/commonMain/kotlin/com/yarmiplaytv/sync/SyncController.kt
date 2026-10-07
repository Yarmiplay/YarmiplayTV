package com.yarmiplaytv.sync

import com.yarmiplaytv.Logger
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.DeviceAuth
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.RelayOffer
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.SyncplayClient
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A line in the on-screen chat / notification feed. */
data class FeedMessage(val text: String, val from: String? = null, val isError: Boolean = false, val at: Long = System.currentTimeMillis())

/**
 * Owns the [SyncplayClient] for the current room and wires it to the mpv player. The protocol
 * layer does the actual sync maths (seek/slowdown thresholds, readiness, ignoring its own echoed
 * state changes); this class manages connection lifecycle and surfaces state for the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncController(
    private val scope: CoroutineScope,
    private val player: Player,
) {
    private val adapter = MpvPlayerAdapter(player)
    private val clientFlow = MutableStateFlow<SyncplayClient?>(null)
    private var eventsJob: Job? = null

    val client: SyncplayClient? get() = clientFlow.value

    /** "host:port" of the server [connect] last used. */
    var server: String? = null
        private set
    private var lastConfig: SyncplayConfig? = null

    val room: StateFlow<RoomState> = clientFlow
        .flatMapLatest { it?.state ?: flowOf(RoomState()) }
        .stateIn(scope, SharingStarted.Eagerly, RoomState())

    /** The YarmiplayServerTV extension session of the current connection; null on other servers. */
    val session: StateFlow<YarmiplaySession?> = clientFlow
        .flatMapLatest { it?.session ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _events = MutableSharedFlow<SyncplayEvent>(extraBufferCapacity = 128)
    /** All protocol events of the current client (for the playlist controller and UI). */
    val events: SharedFlow<SyncplayEvent> = _events.asSharedFlow()

    private val _feed = MutableStateFlow<List<FeedMessage>>(emptyList())
    /** Chat plus notable notifications, newest last (bounded). */
    val feed: StateFlow<List<FeedMessage>> = _feed.asStateFlow()

    private val _toasts = MutableSharedFlow<FeedMessage>(extraBufferCapacity = 32)
    val toasts: SharedFlow<FeedMessage> = _toasts.asSharedFlow()

    /** Off drops all room chat, including what's already in the feed; room notifications still show. */
    var showChat: Boolean = true
        set(value) {
            field = value
            if (!value) _feed.value = _feed.value.filter { it.from == null }
        }

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Names whose chat is hidden until the room is left. Syncplay names aren't identities (anyone can rejoin under
     * another one, or take a name someone used before), so these are never saved.
     */
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    val isActive: Boolean get() = clientFlow.value != null && room.value.status != ConnectionStatus.DISCONNECTED

    var syncSettings: SyncSettings = SyncSettings()
        set(value) {
            field = value
            clientFlow.value?.settings = value
        }

    /** The file currently loaded and reported to the room (kept across reconnects/room changes). */
    private var reportedFile: FileInfo? = null
    private var loading = false

    /** This device's keys; with them the client opts in to the YarmiplayServerTV extensions. */
    var deviceAuth: DeviceAuth? = null
    var appVersion: String = ""

    /**
     * Joins [config]'s room. [requestAccess] asks a YarmiplayServerTV host to approve this device if they haven't
     * yet; pass false for connections the user didn't start (connecting on launch).
     */
    fun connect(config: SyncplayConfig, requestAccess: Boolean = true) {
        disconnect()
        lastConfig = config
        server = serverKey(config.host, config.port)
        val newClient = SyncplayClient(
            config = config.copy(deviceAuth = deviceAuth, appVersion = appVersion, requestAccess = requestAccess),
            player = adapter,
            settings = syncSettings,
            parentScope = scope,
            log = { Logger.d(TAG, it) },
        )
        clientFlow.value = newClient
        // Subscribe before start(): events has no replay, and a local server answers within a millisecond.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newClient.events.collect { event ->
                _events.emit(event)
                when (event) {
                    is SyncplayEvent.Chat -> receiveChat(event.username, event.message)
                    is SyncplayEvent.Notification -> post(FeedMessage(event.message, isError = event.isError))
                    is SyncplayEvent.Connected -> {
                        post(FeedMessage("Joined room '${event.room}' as ${event.username}" + if (event.tls) " (TLS)" else ""))
                        event.motd?.let { post(FeedMessage(it.trim())) }
                    }
                    is SyncplayEvent.Disconnected ->
                        post(FeedMessage("Disconnected: ${event.reason ?: "unknown"}" + if (event.willReconnect) " — reconnecting" else "", isError = !event.willReconnect))
                    else -> Unit
                }
            }
        }
        newClient.start()
        if (loading) newClient.fileLoading()
        else reportedFile?.let { newClient.fileLoaded(it, resetPosition = false) }
    }

    /** Connects again with the last [connect]'s settings, e.g. to ask a YarmiplayServerTV host for access. */
    fun reconnect(requestAccess: Boolean = true) {
        lastConfig?.let { connect(it, requestAccess) }
    }

    fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        clientFlow.value?.close()
        clientFlow.value = null
        player.setSpeed(1.0)
        _blocked.value = emptySet()
    }

    /** Hides [name]'s chat, also the lines already shown, until the room is left. */
    fun block(name: String) {
        _blocked.value += name
        _feed.value = _feed.value.filterNot { it.from == name }
    }

    fun unblock(name: String) {
        _blocked.value -= name
    }

    internal fun receiveChat(from: String, text: String) {
        if (showChat && from !in _blocked.value) post(FeedMessage(text, from = from))
    }

    /** Report that mpv started opening a file; the room ignores the player until [reportFile]. */
    fun reportLoading() {
        loading = true
        clientFlow.value?.fileLoading()
    }

    /** Report a file that finished loading in mpv. */
    fun reportFile(file: FileInfo?, resetPosition: Boolean) {
        loading = false
        reportedFile = file
        clientFlow.value?.fileLoaded(file, resetPosition)
    }

    fun toggleReady() = clientFlow.value?.toggleReady()
    fun setReady(ready: Boolean, manuallyInitiated: Boolean = true) = clientFlow.value?.setReady(ready, manuallyInitiated)
    fun sendChat(text: String) = clientFlow.value?.sendChat(text.trim().take(maxChatLength))

    /** The file reported to the room, if one is loaded. */
    val currentFile: FileInfo? get() = reportedFile

    fun offerFiles(files: List<RelayOffer>) = clientFlow.value?.offerFiles(files)
    fun reportUploadFailed(id: String, error: String) = clientFlow.value?.reportUploadFailed(id, error)
    fun authorizeJellyfin(code: String) = clientFlow.value?.authorizeJellyfin(code)

    /** The server's chat message limit (it cuts longer messages off), or Syncplay's default before joining. */
    val maxChatLength: Int
        get() = (room.value.serverFeatures["maxChatMessageLength"] as? Number)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_MAX_CHAT_LENGTH
    fun changeRoom(room: String) {
        _blocked.value = emptySet()
        clientFlow.value?.changeRoom(room.trim())
    }

    private fun post(message: FeedMessage) {
        _feed.value = (_feed.value + message).takeLast(MAX_FEED)
        _toasts.tryEmit(message)
    }

    fun postLocal(text: String, isError: Boolean = false) = post(FeedMessage(text, isError = isError))

    companion object {
        /** How a Syncplay server is named in settings: "host:port", host lowercased. */
        fun serverKey(host: String, port: Int) = "${host.trim().lowercase()}:$port"

        private const val TAG = "SyncController"
        private const val MAX_FEED = 200
        /** Syncplay's MAX_CHAT_MESSAGE_LENGTH. */
        const val DEFAULT_MAX_CHAT_LENGTH = 150
    }
}
