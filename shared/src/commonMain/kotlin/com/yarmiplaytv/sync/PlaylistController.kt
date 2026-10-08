package com.yarmiplaytv.sync

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.local.LocalMatcher
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.relay.RelayManager
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the player is (about to be) showing. */
data class NowPlaying(
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationHint: Double,
    val url: String,
    val itemId: String? = null,
    /** Server items that are this file (whichever copy is streamed); playback is reported to each. */
    val copies: List<ServerCopy> = emptyList(),
)

sealed interface PlaylistStatus {
    data object Idle : PlaylistStatus
    data class Resolving(val fileName: String) : PlaylistStatus
    data class Loading(val fileName: String) : PlaylistStatus
    data class NotFound(val fileName: String, val reason: String) : PlaylistStatus
    data class Failed(val fileName: String, val reason: String) : PlaylistStatus
    /** The room picked a URL that isn't on a trusted domain; it only opens when the user says so. */
    data class Untrusted(val url: String, val domain: String?) : PlaylistStatus
}

/**
 * Keeps the room's shared playlist and the local player in step: when the room selects an entry
 * (or playback reaches the end and advances) the entry's filename is found in the media folders or
 * on the media servers ([MediaLocator]), played in mpv, reported to the room and marked ready — the
 * TV equivalent of the desktop client's media directories.
 */
class PlaylistController(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val player: Player,
    private val mediaSource: StateFlow<MediaSource?>,
    private val local: LocalLibrary,
    private val locator: MediaLocator,
    private val relay: RelayManager? = null,
) {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _status = MutableStateFlow<PlaylistStatus>(PlaylistStatus.Idle)
    val status: StateFlow<PlaylistStatus> = _status.asStateFlow()

    /** Set to true by the UI when the player screen should be brought up (e.g. room switched file). */
    private val _openPlayerRequests = MutableStateFlow(0)
    val openPlayerRequests: StateFlow<Int> = _openPlayerRequests.asStateFlow()

    /** Last [openPlayerRequests] value a UI acted on; outlives activities so old requests aren't replayed. */
    var handledPlayerRequests = 0

    var autoReady: Boolean = true
    var trustedDomains: List<String> = TrustedDomains.DEFAULT
    var onlySwitchToTrustedDomains: Boolean = true

    /** Syncplay-style editing of the room's playlist (multi-select, shuffle, undo). */
    val shared = SharedPlaylist(scope, sync)

    private data class PendingLoad(val media: NowPlaying, val resetPosition: Boolean, val fromRoom: Boolean)

    private var pending: PendingLoad? = null
    private var resolveJob: Job? = null

    private data class Known(val playable: PlayableMedia, val copies: List<ServerCopy>)

    /** Items the user picked explicitly, so the room echo doesn't need a (possibly ambiguous) lookup. */
    private val knownPlayables = LinkedHashMap<String, Known>()

    init {
        scope.launch { sync.events.collect(::onSyncEvent) }
        scope.launch { player.events.collect(::onPlayerEvent) }
        // A viewer who has the room's file joining (or seeding it) makes "not found" playable.
        relay?.let { r ->
            scope.launch {
                r.files.collect {
                    val missing = _status.value as? PlaylistStatus.NotFound ?: return@collect
                    if (missing.fileName == sync.room.value.currentPlaylistFile && r.find(missing.fileName) != null) {
                        loadFromRoom(missing.fileName, resetPosition = false)
                    }
                }
            }
        }
    }

    private fun onSyncEvent(event: SyncplayEvent) {
        when (event) {
            is SyncplayEvent.SwitchToPlaylistItem -> loadFromRoom(event.filename, event.resetPosition)
            is SyncplayEvent.AdvancePlaylist -> loadFromRoom(event.filename, resetPosition = true)
            else -> Unit
        }
    }

    private fun onPlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.FileLoaded -> {
                val p = pending ?: return
                if (p.media.url != event.url) return
                pending = null
                val duration = event.duration.takeIf { it > 0 } ?: p.media.durationHint
                _status.value = PlaylistStatus.Idle
                sync.reportFile(FileInfo(p.media.fileName, duration, p.media.sizeBytes), p.resetPosition)
                if (p.fromRoom && autoReady && sync.isActive) sync.setReady(true)
                if (!sync.isActive) player.setPaused(false)
            }
            is PlayerEvent.EndFile -> {
                val p = pending ?: return
                if (event.url != null && event.url != p.media.url) return
                pending = null
                _status.value = PlaylistStatus.Failed(p.media.fileName, event.error ?: "Playback failed")
                sync.postLocal("Could not play ${p.media.fileName}: ${event.error}", isError = true)
            }
            else -> Unit
        }
    }

    private fun loadFromRoom(filename: String, resetPosition: Boolean) {
        resolveJob?.cancel()
        _openPlayerRequests.value++
        if (Filenames.isUrl(filename)) {
            if (onlySwitchToTrustedDomains && !TrustedDomains.isTrusted(filename, trustedDomains)) {
                _status.value = PlaylistStatus.Untrusted(filename, TrustedDomains.domainOf(filename))
                sync.postLocal("Not opening $filename: it isn't on a trusted domain", isError = true)
                return
            }
            load(urlNowPlaying(filename), resetPosition, fromRoom = true)
            return
        }
        knownPlayables.entries.firstOrNull { Filenames.same(it.key, filename) }?.value?.let {
            load(it.playable.toNowPlaying(it.copies), resetPosition, fromRoom = true)
            return
        }
        if (!locator.hasServers && !local.hasFolders && !locator.hasRelay(filename)) {
            if (sync.room.value.yarmiplay.relayActive) {
                _status.value = PlaylistStatus.NotFound(filename, "Nobody in the room is sharing it yet")
                sync.postLocal("Can't load '$filename': nobody in the room is sharing it yet", isError = true)
            } else {
                _status.value = PlaylistStatus.NotFound(filename, "Connect a media server or add a media folder to auto-load playlist items")
                sync.postLocal("Can't load '$filename': no media server or media folders", isError = true)
            }
            return
        }
        _status.value = PlaylistStatus.Resolving(filename)
        resolveJob = scope.launch {
            when (val found = locator.locateForRoom(filename)) {
                is RoomLocation.Local -> {
                    Logger.i(TAG, "Resolved '$filename' locally via ${found.match.kind} -> ${found.match.file.uri}")
                    val playable = found.match.file.toPlayable()
                    load(playable.toNowPlaying(), resetPosition, fromRoom = true)
                    findCopies(playable)
                }
                is RoomLocation.Server -> {
                    Logger.i(TAG, "Resolved '$filename' via ${found.matchedBy} -> ${found.playable.sourceKey}/${found.playable.itemId}, copies ${found.copies}")
                    load(found.playable.toNowPlaying(found.copies), resetPosition, fromRoom = true)
                }
                is RoomLocation.Relay -> {
                    val r = relay ?: return@launch
                    Logger.i(TAG, "Resolved '$filename' via the room's file relay (${found.file.sources} source(s))")
                    val url = r.open(found.file)
                    val name = found.file.name
                    load(NowPlaying(name, name, found.file.size, found.file.duration, url), resetPosition, fromRoom = true)
                }
                is RoomLocation.Missing -> {
                    val servers = mediaSource.value?.let { s -> if (s is CompositeMediaSource && s.sources.size > 2) "your media servers" else s.displayName }
                    val where = listOfNotNull(servers, "your media folders".takeIf { local.hasFolders }).joinToString(" or ")
                    _status.value = PlaylistStatus.NotFound(filename, found.reason)
                    sync.postLocal("'$filename' not found in $where", isError = true)
                }
            }
        }
    }

    private fun load(picked: NowPlaying, resetPosition: Boolean, fromRoom: Boolean) {
        // Copies may have been found since the caller took its snapshot.
        val media = knownPlayables[picked.fileName]?.takeIf { it.playable.url == picked.url && it.copies.size > picked.copies.size }
            ?.let { picked.copy(copies = it.copies) } ?: picked
        val alreadyLoading = pending?.media?.url == media.url
        pending = PendingLoad(media, resetPosition, fromRoom)
        // E.g. a reconnect replays the room's selection while a manual pick of the same file is opening.
        if (alreadyLoading) return
        _nowPlaying.value = media
        _status.value = PlaylistStatus.Loading(media.fileName)
        sync.reportLoading()
        player.load(media.url, media.title, startPaused = true)
    }

    // --- User actions ------------------------------------------------------------------

    /**
     * Plays [item] for everyone: adds its real filename to the shared playlist (if needed) and
     * selects it; the local load happens when the server echoes the selection. Without a room
     * it just plays locally.
     */
    fun playInRoom(item: MediaItem) {
        val source = mediaSource.value ?: return
        scope.launch {
            val playable = runCatching { source.playable(item) }.getOrElse {
                sync.postLocal("Can't play ${item.name}: ${it.message}", isError = true)
                return@launch
            }
            val known = remember(playable)
            val client = sync.client
            if (client != null && sync.isActive) {
                client.playInRoom(playable.fileName)
            } else {
                _openPlayerRequests.value++
                load(known.toNowPlaying(), resetPosition = true, fromRoom = false)
            }
        }
    }

    /** Plays locally and reports the file; if it's in the shared playlist the room follows. */
    fun playHere(item: MediaItem) {
        val source = mediaSource.value ?: return
        scope.launch {
            val playable = runCatching { source.playable(item) }.getOrElse {
                sync.postLocal("Can't play ${item.name}: ${it.message}", isError = true)
                return@launch
            }
            val known = remember(playable)
            _openPlayerRequests.value++
            load(known.toNowPlaying(), resetPosition = false, fromRoom = false)
        }
    }

    /** Plays a direct URL locally; Syncplay identifies streams by their URL. */
    fun playUrl(url: String) {
        _openPlayerRequests.value++
        load(urlNowPlaying(url), resetPosition = false, fromRoom = false)
    }

    /** Opens the untrusted URL the room picked, after the user agreed. */
    fun playUntrusted() {
        val status = _status.value as? PlaylistStatus.Untrusted ?: return
        load(urlNowPlaying(status.url), resetPosition = true, fromRoom = true)
    }

    private fun urlNowPlaying(url: String): NowPlaying {
        val name = url.substringBefore('?').substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return NowPlaying(title = name.ifEmpty { url }, fileName = url, sizeBytes = 0, durationHint = 0.0, url = url)
    }

    /**
     * Whether this device can play a playlist entry without asking: a URL, a file picked here, or one in the
     * media folders. Entries that aren't may still be found on a media server when selected.
     */
    fun isAvailable(fileName: String): Boolean =
        Filenames.isUrl(fileName) ||
            knownPlayables.keys.any { Filenames.same(it, fileName) } ||
            LocalMatcher.find(local.files.value, fileName) != null ||
            locator.hasRelay(fileName)

    /** Adds files from this device to the shared playlist in one edit, before index [at] or at the end, skipping ones already in it. */
    fun addLocalFilesToRoomPlaylist(uris: List<String>, at: Int? = null) {
        if (sync.client == null) {
            sync.postLocal("Join a Syncplay room to use the shared playlist", isError = true)
            return
        }
        scope.launch {
            val names = uris.map { uri -> remember(localPlayable(uri)).playable.fileName }
            reportAdded(names.size, shared.add(names, at))
        }
    }

    /** Adds stream URLs to the shared playlist in one edit; anything that isn't an http(s) URL is ignored. */
    fun addUrlsToRoomPlaylist(urls: List<String>): Int {
        val valid = urls.map { it.trim() }.filter { Filenames.isUrl(it) }
        if (valid.isNotEmpty()) reportAdded(valid.size, shared.add(valid))
        return valid.size
    }

    private fun reportAdded(requested: Int, added: Int) {
        val skipped = requested - added
        when {
            added == 0 && skipped > 0 -> sync.postLocal(if (skipped == 1) "That's already in the playlist" else "Those are already in the playlist")
            skipped > 0 -> sync.postLocal("Added $added to the playlist ($skipped already in it)")
            added > 0 -> sync.postLocal(if (added == 1) "Added 1 entry to the playlist" else "Added $added entries to the playlist")
        }
    }

    fun addToRoomPlaylist(item: MediaItem) {
        val source = mediaSource.value ?: return
        scope.launch {
            val playable = runCatching { source.playable(item) }.getOrElse {
                sync.postLocal("Can't add ${item.name}: ${it.message}", isError = true)
                return@launch
            }
            remember(playable)
            addFileNameToPlaylist(playable.fileName)
        }
    }

    /** Manual pick after a failed lookup: play [item] as the substitute for the playlist entry. */
    fun resolveManually(item: MediaItem) {
        val status = _status.value as? PlaylistStatus.NotFound
        val source = mediaSource.value ?: return
        scope.launch {
            val playable = runCatching { source.playable(item) }.getOrElse {
                sync.postLocal("Can't play ${item.name}: ${it.message}", isError = true)
                return@launch
            }
            val known = remember(playable)
            if (status != null) knownPlayables[status.fileName] = known
            _openPlayerRequests.value++
            load(known.toNowPlaying(), resetPosition = true, fromRoom = true)
        }
    }

    /**
     * Plays a file from this device. With [inRoom] (and a room) its filename becomes the room's
     * selection, so the others load their own copy; otherwise it only plays here and is reported.
     */
    fun playLocal(uri: String, inRoom: Boolean) {
        scope.launch {
            val known = remember(localPlayable(uri))
            val client = sync.client
            if (inRoom && client != null && sync.isActive) {
                client.playInRoom(known.playable.fileName)
            } else {
                _openPlayerRequests.value++
                load(known.toNowPlaying(), resetPosition = inRoom, fromRoom = false)
            }
        }
    }

    fun addLocalToRoomPlaylist(uri: String) {
        scope.launch {
            val playable = localPlayable(uri)
            remember(playable)
            addFileNameToPlaylist(playable.fileName)
        }
    }

    /** Manual pick after a failed lookup: play a local file as the substitute for the playlist entry. */
    fun resolveManuallyLocal(uri: String) {
        val status = _status.value as? PlaylistStatus.NotFound
        scope.launch {
            val known = remember(localPlayable(uri))
            if (status != null) knownPlayables[status.fileName] = known
            _openPlayerRequests.value++
            load(known.toNowPlaying(), resetPosition = true, fromRoom = true)
        }
    }

    private suspend fun localPlayable(uri: String): PlayableMedia = local.describe(uri).also(local::rememberOpened).toPlayable()

    private fun addFileNameToPlaylist(fileName: String) {
        if (sync.client == null) {
            sync.postLocal("Join a Syncplay room to use the shared playlist", isError = true)
            return
        }
        if (shared.add(listOf(fileName)) == 0) {
            sync.postLocal("$fileName is already in the playlist")
        } else {
            sync.postLocal("Added $fileName to the room playlist")
        }
    }

    fun selectIndex(index: Int) = sync.client?.selectPlaylistIndex(index)
    fun removeIndex(index: Int) = shared.remove(setOf(index))
    fun move(from: Int, to: Int) = shared.move(from, to)

    fun dismissStatus() {
        _status.value = PlaylistStatus.Idle
    }

    fun stop() {
        resolveJob?.cancel()
        pending = null
        _nowPlaying.value = null
        _status.value = PlaylistStatus.Idle
        player.stop()
        sync.reportFile(null, resetPosition = false)
    }

    /** Remembers a file the user picked here and starts looking for the same file on the other servers. */
    private fun remember(playable: PlayableMedia): Known {
        val known = Known(playable, MediaLocator.ownCopy(playable))
        knownPlayables[playable.fileName] = known
        while (knownPlayables.size > 200) knownPlayables.remove(knownPlayables.keys.first())
        findCopies(playable)
        return known
    }

    /** Fills in the server copies of [playable] once found; never delays or changes playback. */
    private fun findCopies(playable: PlayableMedia) {
        if (!locator.hasServers) return
        scope.launch {
            val copies = runCatching { locator.copiesOf(playable) }.getOrElse { return@launch }
            knownPlayables[playable.fileName]?.takeIf { it.playable.url == playable.url }?.let {
                knownPlayables[playable.fileName] = it.copy(copies = copies)
            }
            _nowPlaying.update { np -> if (np?.url == playable.url) np.copy(copies = copies) else np }
        }
    }

    private fun Known.toNowPlaying() = playable.toNowPlaying(copies)
    private fun PlayableMedia.toNowPlaying(copies: List<ServerCopy> = MediaLocator.ownCopy(this)) =
        NowPlaying(title, fileName, sizeBytes, durationSeconds, url, itemId, copies)
    private fun LocalFile.toPlayable() = PlayableMedia(itemId = uri, url = uri, fileName = name, sizeBytes = sizeBytes, durationSeconds = 0.0, title = name)

    companion object {
        private const val TAG = "PlaylistController"
    }
}
