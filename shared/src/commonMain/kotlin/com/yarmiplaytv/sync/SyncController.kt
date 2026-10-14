package com.yarmiplaytv.sync

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.KnownServer
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.DeviceAuth
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.LanServer
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.syncplay.RelayOffer
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.ServerTlsFormat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Saved operator passwords of managed rooms, by "host:port/+name:HASH" (see [SyncController.passwordKey]). */
interface OperatorPasswords {
    fun get(key: String): String?
    /** Saves [password] for [key]; null forgets it. */
    fun save(key: String, password: String?)
}

/** A line in the on-screen chat / notification feed. */
data class FeedMessage(val text: String, val from: String? = null, val isError: Boolean = false, val at: Long = System.currentTimeMillis()) {
    /** Whether it pops up over the video: room notices always, chat only with [chatOnVideo] on. */
    fun showsOnVideo(chatOnVideo: Boolean): Boolean = from == null || chatOnVideo
}

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

    private val _connectionError = MutableStateFlow<String?>(null)
    /** Why the connection last dropped; cleared once it's back, on [connect] and on [disconnect]. */
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

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
    /** Where operator passwords of managed rooms are kept; without it none are remembered. */
    var operatorPasswords: OperatorPasswords? = null
    /** What a server ("host:port") was when last joined: its certificate's name, LAN addresses and id. */
    var knownServer: (server: String) -> KnownServer? = { null }
    /**
     * Finds YarmiplayServerTVs on this network when nothing else reaches the server; null never looks. It must only
     * broadcast on Wi-Fi or Ethernet.
     */
    var lanDiscovery: (suspend (port: Int) -> List<LanServer>)? = null
    var appVersion: String = ""

    /**
     * Joins [config]'s room. [requestAccess] asks a YarmiplayServerTV host to approve this device if they haven't
     * yet; pass false for connections the user didn't start (connecting on launch). With [createManagedRoom], once
     * joined it makes a managed room of that name and moves there as its operator (see [SyncplayClient.createManagedRoom]).
     */
    fun connect(config: SyncplayConfig, requestAccess: Boolean = true, operatorPassword: String? = null, createManagedRoom: String? = null) {
        disconnect()
        lastConfig = config
        server = serverKey(config.host, config.port)
        val known = server?.let(knownServer)
        // Typed on the join form for a managed room: saved like any other, so it's sent once the room is joined.
        operatorPassword?.let(ManagedRooms::normalizePassword)?.takeIf { it.isNotEmpty() && ManagedRooms.isManaged(config.room.trim()) }?.let { password ->
            passwordKey(config.room.trim())?.let { savePassword(it, password) }
        }
        val newClient = SyncplayClient(
            config = config.copy(
                deviceAuth = deviceAuth,
                appVersion = appVersion,
                requestAccess = requestAccess,
                // Its LAN addresses are tried at home; a server saved as an IP is verified as its certificate's name.
                lanAddresses = config.lanAddresses.ifEmpty { known?.lanAddresses.orEmpty() },
                tlsName = config.tlsName ?: known?.tlsName?.takeIf { ServerTlsFormat.isIpLiteral(config.host) },
                knownServerId = config.knownServerId ?: known?.serverId?.takeIf { it.isNotEmpty() },
            ),
            player = adapter,
            settings = syncSettings,
            parentScope = scope,
            log = { Logger.d(TAG, it) },
        )
        newClient.savedOperatorPassword = { room -> passwordKey(room)?.let { operatorPasswords?.get(it) } }
        newClient.lanDiscovery = lanDiscovery
        clientFlow.value = newClient
        // Only on the first join: a reconnect after creating it already goes to the managed room.
        var pendingCreate = createManagedRoom?.trim()?.takeIf { it.isNotEmpty() }
        // Subscribe before start(): events has no replay, and a local server answers within a millisecond.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newClient.events.collect { event ->
                _events.emit(event)
                when (event) {
                    is SyncplayEvent.Chat -> receiveChat(event.username, event.message)
                    is SyncplayEvent.Notification -> post(FeedMessage(event.message, isError = event.isError))
                    is SyncplayEvent.Connected -> {
                        _connectionError.value = null
                        post(FeedMessage("Joined room '${ManagedRooms.label(event.room)}' as ${event.username}" + if (event.tls) " (TLS)" else ""))
                        event.motd?.let { post(FeedMessage(it.trim())) }
                        pendingCreate?.let { name ->
                            pendingCreate = null
                            if (!newClient.createManagedRoom(name)) {
                                val why = if (newClient.state.value.managedRooms) "the name doesn't fit" else "this server doesn't have managed rooms"
                                post(FeedMessage("Couldn't create managed room '$name': $why", isError = true))
                            }
                        }
                    }
                    is SyncplayEvent.ManagedRoomCreated -> {
                        passwordKey(event.room)?.let { savePassword(it, event.password) }
                        lastConfig = lastConfig?.copy(room = event.room)
                        post(FeedMessage("Created managed room '${ManagedRooms.display(event.room)}' (${event.room}). Operator password: ${event.password}"))
                    }
                    is SyncplayEvent.ControllerAuth -> controllerAuth(event)
                    is SyncplayEvent.ServerNamed -> {
                        // From now on it's this server by its name, which also works away from home.
                        server = serverKey(event.name, config.port)
                        lastConfig = lastConfig?.copy(host = event.name, tlsName = null)
                        post(FeedMessage("This server's name is ${event.name}; saved for use away from home."))
                    }
                    is SyncplayEvent.ReadySetBy -> {
                        val what = if (event.isReady) "ready" else "not ready"
                        val whom = if (event.user == room.value.username) "you" else event.user
                        post(FeedMessage("${event.setBy} marked $whom $what"))
                    }
                    is SyncplayEvent.Disconnected -> {
                        _connectionError.value = event.reason ?: "Connection closed"
                        post(FeedMessage("Disconnected: ${event.reason ?: "unknown"}" + if (event.willReconnect) " — reconnecting" else "", isError = !event.willReconnect))
                    }
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
        _connectionError.value = null
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

    private fun controllerAuth(event: SyncplayEvent.ControllerAuth) {
        val mine = event.user == room.value.username
        val key = passwordKey(event.room)
        when {
            mine && event.success -> {
                event.password?.let { password -> key?.let { savePassword(it, password) } }
                post(FeedMessage("You are now a room operator"))
            }
            mine -> {
                key?.let { savePassword(it, null) }
                post(FeedMessage("Wrong room password", isError = true))
            }
            event.success && event.room == room.value.room -> post(FeedMessage("${event.user} is now a room operator"))
        }
    }

    /** Makes a managed room named [baseName] and joins it as its operator; false when the server can't or the name is too long. */
    fun createManagedRoom(baseName: String): Boolean = clientFlow.value?.createManagedRoom(baseName) == true
    fun identifyAsOperator(password: String) = clientFlow.value?.identifyAsOperator(password)
    /** As an operator: marks [username] ready or not ready. */
    fun setOthersReady(username: String, isReady: Boolean) = clientFlow.value?.setOthersReady(username, isReady)

    /** The saved operator password for [room] on the current server, e.g. to show it to its operator. */
    fun savedOperatorPassword(room: String): String? = passwordKey(room)?.let { operatorPasswords?.get(it) }

    private val _operatorPasswordChanges = MutableStateFlow(0)
    /**
     * Counts saved or forgotten operator passwords. The room state can change before a password is saved (the
     * server confirms an operator before the client's own event is handled), so what shows one watches this too.
     */
    val operatorPasswordChanges: StateFlow<Int> = _operatorPasswordChanges.asStateFlow()

    private fun savePassword(key: String, password: String?) {
        operatorPasswords?.save(key, password)
        _operatorPasswordChanges.update { it + 1 }
    }

    /** "host:port/+name:HASH": where [room]'s operator password is kept, like room playlists. */
    private fun passwordKey(room: String): String? = server?.let { "$it/$room" }

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
