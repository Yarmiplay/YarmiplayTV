package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RelayFile
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the relay is doing for the file that's playing, for the UI. */
data class RelayStatus(
    val fileName: String,
    /** Viewers who have the file loaded and can seed it. */
    val seeders: List<String>,
    val downloading: Boolean,
    val haveBytes: Long,
    val size: Long,
    val bytesPerSecond: Double?,
    val etaSeconds: Double?,
    /** Marked not ready until enough of the file is here to play through. */
    val waitingToPlay: Boolean,
    val complete: Boolean,
    val error: String?,
) {
    /** Share of the file already in the cache, for the download bar. */
    val fraction: Float
        get() = if (size <= 0L) 0f else (haveBytes.toDouble() / size).toFloat().coerceIn(0f, 1f)

    /**
     * What to put on the player. Streaming replaces the loading card immediately, including before the file
     * opens, so a relayed file is not stuck on "Loading…". The download bar shows while this viewer is held,
     * or until the file opens, and then goes away.
     */
    fun playbackHint(fileLoaded: Boolean): RelayHint = when {
        complete -> RelayHint.None
        downloading && (waitingToPlay || !fileLoaded) -> RelayHint.Downloading
        !downloading -> RelayHint.Streaming
        else -> RelayHint.None
    }

    val text: String
        get() {
            val from = if (seeders.isEmpty()) "" else " from ${seeders.joinToString(", ")}"
            return when {
                complete -> "Playing a copy relayed$from via the Syncplay server"
                downloading -> buildString {
                    append("Downloading$from via the Syncplay server · ${(haveBytes * 100 / size.coerceAtLeast(1))}%")
                    etaSeconds?.let { append(" · ${RelayPolicy.formatEta(it)} left") }
                }
                else -> "Streaming$from via the Syncplay server"
            }
        }
}

enum class RelayHint { None, Streaming, Downloading }

/** When to stream, when to download, and when a download is far enough along to play. */
object RelayPolicy {
    /** Download when throughput is below this × the file's bitrate. */
    const val DOWNLOAD_BELOW = 1.3
    /** Go back to streaming when throughput is above this × the bitrate. */
    const val STREAM_ABOVE = 2.0
    const val MARGIN = 0.8

    /** Bytes per second the file plays at, or null without a duration. */
    fun bitrate(size: Long, duration: Double): Double? = if (size > 0 && duration > 0) size / duration else null

    /** [rate] is the measured bytes per second, null when the fetches idled (the network keeps up). */
    fun shouldDownload(rate: Double?, size: Long, duration: Double): Boolean {
        val bitrate = bitrate(size, duration) ?: return false
        return rate != null && rate < DOWNLOAD_BELOW * bitrate
    }

    fun shouldStream(rate: Double?, size: Long, duration: Double): Boolean {
        val bitrate = bitrate(size, duration) ?: return true
        return rate != null && rate > STREAM_ABOVE * bitrate
    }

    /** `(size - have) / rate <= (duration - position) * 0.8`: the rest arrives before playback gets there. */
    fun canPlayThrough(size: Long, have: Long, rate: Double?, duration: Double, position: Double): Boolean {
        val remaining = size - have
        if (remaining <= 0) return true
        if (rate == null || rate <= 0 || duration <= 0) return false
        return remaining / rate <= (duration - position) * MARGIN
    }

    fun eta(size: Long, have: Long, rate: Double?): Double? =
        rate?.takeIf { it > 0 }?.let { (size - have).coerceAtLeast(0) / it }

    fun formatEta(seconds: Double): String = when {
        seconds < 60 -> "less than a minute"
        seconds < 3600 -> "about ${(seconds / 60).toInt() + 1} min"
        else -> "about ${(seconds / 3600).toInt()} h ${((seconds % 3600) / 60).toInt()} min"
    }
}

/**
 * The room's file relay on a YarmiplayServerTV: offers and serves this device's copies of playlist files, and
 * plays files only other viewers have through a local cache. Idle on other servers (the room never has a session).
 */
class RelayManager(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val player: Player,
    local: LocalLibrary,
    cacheDir: File,
    /** Whether this device offers its files to the room; viewing relayed files works either way. */
    sharing: Flow<Boolean> = flowOf(true),
) {
    private val http = relayHttpClient()
    private val cache by lazy { RelayCache(File(cacheDir, "relay")) }
    private val proxy = RelayProxy(scope)
    private val offers = RelayOffers(scope, sync, local, sharing)
    private val uploader = RelayUploader(scope, sync, local, offers, http)

    private val transfers = HashMap<String, RelayTransfer>()
    private var current: RelayTransfer? = null
    private var currentEntry: RelayFile? = null
    private var currentUrl: String? = null
    private var notReadyByUs = false
    private var lastRoom: String? = null
    /** Refreshes the download bar as blocks land. The stream-or-download choice stays on [tick]. */
    private var progressJob: Job? = null

    private val _status = MutableStateFlow<RelayStatus?>(null)
    val status: StateFlow<RelayStatus?> = _status.asStateFlow()

    /** The room's relay list; changes when seeders come and go. */
    val files: Flow<List<RelayFile>> = sync.room.map { it.yarmiplay.relayFiles }.distinctUntilChanged()

    init {
        offers.start()
        uploader.start()
        scope.launch(Dispatchers.IO) { cache.trim() }
        scope.launch {
            sync.room.map { Triple(it.room, it.playlist, it.status) }.distinctUntilChanged().collect { (room, playlist, status) ->
                cleanup(room.takeIf { status != ConnectionStatus.DISCONNECTED }, playlist)
            }
        }
        scope.launch {
            player.state.map { it.url }.distinctUntilChanged().collect { url ->
                if (current != null && url != null && url != currentUrl) detach()
            }
        }
        scope.launch {
            sync.events.collect { if (it is SyncplayEvent.SessionEnded) current?.let { t -> t.stop(); update() } }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /**
     * The relay entry for the room file [fileName]: same name, the size the room reports for it (if any), and
     * readable (someone seeds it or the server has all of it).
     */
    fun find(fileName: String): RelayFile? = find(sync.room.value, fileName)

    /** Starts fetching [entry] and returns the URL the player opens. */
    suspend fun open(entry: RelayFile): String {
        Logger.i(TAG, "Opening ${entry.name}: ${entry.size} bytes, ${entry.sources} source(s), server has ${entry.cachedBytes}")
        val key = "${entry.quickHash}-${entry.size}"
        val transfer = transfers[key] ?: withContext(Dispatchers.IO) {
            RelayTransfer(key, entry.name, entry.size, cache.open(key, entry.size), http, scope) { sourceFor(entry.quickHash, entry.size) }
        }.also { transfers[key] = it }
        if (current !== transfer) detach()
        current = transfer
        currentEntry = entry
        transfer.demand(0)
        transfer.start()
        val url = withContext(Dispatchers.IO) { proxy.url(transfer) }
        currentUrl = url
        watch(transfer)
        update()
        return url
    }

    /** Moves the download bar as each block lands. The stream-or-download choice stays on [tick]. */
    private fun watch(transfer: RelayTransfer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            transfer.cache.version.collect {
                if (current === transfer && transfer.downloading) update()
            }
        }
    }

    fun isRelayUrl(url: String?): Boolean = proxy.isProxyUrl(url)

    fun close() {
        progressJob?.cancel()
        progressJob = null
        transfers.values.forEach { it.stop() }
        uploader.cancelAll()
        proxy.close()
    }

    private fun sourceFor(quickHash: String, size: Long): RelaySource? {
        val session = sync.session.value ?: return null
        val file = sync.room.value.yarmiplay.relayFiles.firstOrNull { it.quickHash == quickHash && it.size == size } ?: return null
        return RelaySource(session, file.id)
    }

    private fun detach() {
        progressJob?.cancel()
        progressJob = null
        current?.stop()
        current = null
        currentEntry = null
        currentUrl = null
        notReadyByUs = false
        _status.value = null
    }

    private fun tick() {
        val t = current ?: return
        val entry = currentEntry ?: return
        val room = sync.room.value
        if (notReadyByUs && room.isReady == true) notReadyByUs = false
        val duration = entry.duration.takeIf { it > 0 } ?: player.duration
        val own = t.rate()
        if (t.cache.complete) {
            if (t.downloading) t.setDownload(false)
            readyAgain()
        } else if (!t.downloading) {
            if (RelayPolicy.shouldDownload(own, entry.size, duration)) {
                t.setDownload(true)
                if (room.isReady == true) {
                    sync.setReady(false, manuallyInitiated = false)
                    notReadyByUs = true
                }
            }
        } else {
            val rate = own ?: listRate(t)
            if (notReadyByUs && RelayPolicy.canPlayThrough(entry.size, t.cache.cachedBytes, rate, duration, player.currentPosition())) {
                readyAgain()
            }
            if (RelayPolicy.shouldStream(own, entry.size, duration)) {
                t.setDownload(false)
                readyAgain()
            }
        }
        val keep = t.key
        scope.launch(Dispatchers.IO) { cache.trim(setOf(keep)) }
        update()
    }

    private fun readyAgain() {
        if (!notReadyByUs) return
        notReadyByUs = false
        if (sync.room.value.isReady == false) sync.setReady(true, manuallyInitiated = false)
    }

    private fun listRate(t: RelayTransfer): Double? =
        sync.room.value.yarmiplay.relayFiles.firstOrNull { "${it.quickHash}-${it.size}" == t.key }?.rate?.takeIf { it > 0 }?.toDouble()

    private fun update() {
        val t = current ?: return
        val entry = currentEntry ?: return
        val room = sync.room.value
        val rate = t.rate() ?: listRate(t)
        val have = t.cache.cachedBytes
        _status.value = RelayStatus(
            fileName = entry.name,
            seeders = room.users.filter { it.yarmiplay && it.name != room.username && it.file?.name == entry.name }.map { it.name },
            downloading = t.downloading,
            haveBytes = have,
            size = entry.size,
            bytesPerSecond = rate,
            etaSeconds = if (t.downloading) RelayPolicy.eta(entry.size, have, rate) else null,
            waitingToPlay = notReadyByUs,
            complete = t.cache.complete,
            error = t.lastError.takeIf { !t.cache.complete },
        )
    }

    /**
     * Drops cached files when leaving the room, and files the playlist no longer has; the one playing stays
     * (it can keep playing from what's cached).
     */
    private fun cleanup(room: String?, playlist: List<String>) {
        val roomChanged = room != lastRoom
        lastRoom = room
        val names = playlist.map { FileNames.baseName(it) }.toSet()
        val drop = transfers.filter { (_, t) -> t !== current && (roomChanged || t.name !in names) }
        for ((key, t) in drop) {
            t.stop()
            proxy.remove(t)
            transfers.remove(key)
        }
        if (drop.isNotEmpty()) scope.launch(Dispatchers.IO) { drop.keys.forEach(cache::delete) }
    }

    companion object {
        private const val TAG = "RelayManager"
        private const val TICK_MS = 2_000L

        fun find(room: RoomState, fileName: String): RelayFile? {
            if (!room.yarmiplay.relayActive) return null
            val name = FileNames.baseName(fileName)
            val reportedSize = room.users.firstNotNullOfOrNull { u -> u.file?.takeIf { it.name == name && it.size > 0 }?.size }
            return room.yarmiplay.relayFiles.firstOrNull { it.name == name && it.readable && (reportedSize == null || it.size == reportedSize) }
        }
    }
}
