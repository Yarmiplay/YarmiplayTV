package com.syncplaytv.sync

import com.syncplaytv.Logger
import com.syncplaytv.player.Player
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.syncplay.FileInfo
import com.syncplaytv.syncplay.RoomState
import com.syncplaytv.syncplay.SyncSettings
import com.syncplaytv.syncplay.SyncplayClient
import com.syncplaytv.syncplay.SyncplayConfig
import com.syncplaytv.syncplay.SyncplayEvent
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

    val room: StateFlow<RoomState> = clientFlow
        .flatMapLatest { it?.state ?: flowOf(RoomState()) }
        .stateIn(scope, SharingStarted.Eagerly, RoomState())

    private val _events = MutableSharedFlow<SyncplayEvent>(extraBufferCapacity = 128)
    /** All protocol events of the current client (for the playlist controller and UI). */
    val events: SharedFlow<SyncplayEvent> = _events.asSharedFlow()

    private val _feed = MutableStateFlow<List<FeedMessage>>(emptyList())
    /** Chat plus notable notifications, newest last (bounded). */
    val feed: StateFlow<List<FeedMessage>> = _feed.asStateFlow()

    private val _toasts = MutableSharedFlow<FeedMessage>(extraBufferCapacity = 32)
    val toasts: SharedFlow<FeedMessage> = _toasts.asSharedFlow()

    val isActive: Boolean get() = clientFlow.value != null && room.value.status != ConnectionStatus.DISCONNECTED

    var syncSettings: SyncSettings = SyncSettings()
        set(value) {
            field = value
            clientFlow.value?.settings = value
        }

    /** The file currently loaded and reported to the room (kept across reconnects/room changes). */
    private var reportedFile: FileInfo? = null
    private var loading = false

    fun connect(config: SyncplayConfig) {
        disconnect()
        val newClient = SyncplayClient(
            config = config,
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
                    is SyncplayEvent.Chat -> post(FeedMessage(event.message, from = event.username))
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

    fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        clientFlow.value?.close()
        clientFlow.value = null
        player.setSpeed(1.0)
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
    fun setReady(ready: Boolean) = clientFlow.value?.setReady(ready)
    fun sendChat(text: String) = clientFlow.value?.sendChat(text.trim())
    fun changeRoom(room: String) = clientFlow.value?.changeRoom(room.trim())

    private fun post(message: FeedMessage) {
        _feed.value = (_feed.value + message).takeLast(MAX_FEED)
        _toasts.tryEmit(message)
    }

    fun postLocal(text: String, isError: Boolean = false) = post(FeedMessage(text, isError = isError))

    companion object {
        private const val TAG = "SyncController"
        private const val MAX_FEED = 200
    }
}
