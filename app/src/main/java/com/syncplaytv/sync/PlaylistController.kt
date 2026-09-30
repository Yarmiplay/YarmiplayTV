package com.syncplaytv.sync

import android.util.Log
import com.syncplaytv.media.MediaItem
import com.syncplaytv.media.MediaSource
import com.syncplaytv.media.PlayableMedia
import com.syncplaytv.media.ResolveResult
import com.syncplaytv.player.MpvPlayer
import com.syncplaytv.player.PlayerEvent
import com.syncplaytv.syncplay.FileInfo
import com.syncplaytv.syncplay.Filenames
import com.syncplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the player is (about to be) showing. */
data class NowPlaying(
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationHint: Double,
    val url: String,
    val itemId: String? = null,
)

sealed interface PlaylistStatus {
    data object Idle : PlaylistStatus
    data class Resolving(val fileName: String) : PlaylistStatus
    data class Loading(val fileName: String) : PlaylistStatus
    data class NotFound(val fileName: String, val reason: String) : PlaylistStatus
    data class Failed(val fileName: String, val reason: String) : PlaylistStatus
}

/**
 * Keeps the room's shared playlist and the local player in step: when the room selects an entry
 * (or playback reaches the end and advances) the entry's filename is resolved through the media
 * source (Jellyfin), streamed into mpv, reported to the room and marked ready — the TV equivalent
 * of the desktop client's media directories.
 */
class PlaylistController(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val player: MpvPlayer,
    private val mediaSource: StateFlow<MediaSource?>,
) {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _status = MutableStateFlow<PlaylistStatus>(PlaylistStatus.Idle)
    val status: StateFlow<PlaylistStatus> = _status.asStateFlow()

    /** Set to true by the UI when the player screen should be brought up (e.g. room switched file). */
    private val _openPlayerRequests = MutableStateFlow(0)
    val openPlayerRequests: StateFlow<Int> = _openPlayerRequests.asStateFlow()

    var autoReady: Boolean = true

    private data class PendingLoad(val media: NowPlaying, val resetPosition: Boolean, val fromRoom: Boolean)

    private var pending: PendingLoad? = null
    private var resolveJob: Job? = null

    /** Items the user picked explicitly, so the room echo doesn't need a (possibly ambiguous) lookup. */
    private val knownPlayables = LinkedHashMap<String, PlayableMedia>()

    init {
        scope.launch { sync.events.collect(::onSyncEvent) }
        scope.launch { player.events.collect(::onPlayerEvent) }
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
            load(NowPlaying(title = filename, fileName = filename, sizeBytes = 0, durationHint = 0.0, url = filename), resetPosition, fromRoom = true)
            return
        }
        knownPlayables.entries.firstOrNull { Filenames.same(it.key, filename) }?.value?.let {
            load(it.toNowPlaying(), resetPosition, fromRoom = true)
            return
        }
        val source = mediaSource.value
        if (source == null) {
            _status.value = PlaylistStatus.NotFound(filename, "Connect to Jellyfin to auto-load playlist items")
            sync.postLocal("Can't load '$filename': not connected to Jellyfin", isError = true)
            return
        }
        _status.value = PlaylistStatus.Resolving(filename)
        resolveJob = scope.launch {
            val result = runCatching { source.resolveByFilename(filename) }
                .getOrElse { ResolveResult.NotFound(filename, it.message ?: "Lookup failed") }
            when (result) {
                is ResolveResult.Found -> {
                    Log.i(TAG, "Resolved '$filename' via ${result.matchedBy} -> ${result.item.id}")
                    load(result.playable.toNowPlaying(), resetPosition, fromRoom = true)
                }
                is ResolveResult.NotFound -> {
                    _status.value = PlaylistStatus.NotFound(filename, result.reason)
                    sync.postLocal("'$filename' not found in ${source.displayName}", isError = true)
                }
            }
        }
    }

    private fun load(media: NowPlaying, resetPosition: Boolean, fromRoom: Boolean) {
        pending = PendingLoad(media, resetPosition, fromRoom)
        _nowPlaying.value = media
        _status.value = PlaylistStatus.Loading(media.fileName)
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
            remember(playable)
            val client = sync.client
            if (client != null && sync.isActive) {
                client.playInRoom(playable.fileName)
            } else {
                _openPlayerRequests.value++
                load(playable.toNowPlaying(), resetPosition = true, fromRoom = false)
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
            remember(playable)
            _openPlayerRequests.value++
            load(playable.toNowPlaying(), resetPosition = false, fromRoom = false)
        }
    }

    /** Plays a direct URL locally; Syncplay identifies streams by their URL. */
    fun playUrl(url: String) {
        _openPlayerRequests.value++
        val name = url.substringBefore('?').substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        load(NowPlaying(title = name.ifEmpty { url }, fileName = url, sizeBytes = 0, durationHint = 0.0, url = url), resetPosition = false, fromRoom = false)
    }

    fun addToRoomPlaylist(item: MediaItem) {
        val source = mediaSource.value ?: return
        scope.launch {
            val playable = runCatching { source.playable(item) }.getOrElse {
                sync.postLocal("Can't add ${item.name}: ${it.message}", isError = true)
                return@launch
            }
            remember(playable)
            val client = sync.client ?: return@launch
            if (sync.room.value.playlist.any { Filenames.same(it, playable.fileName) }) {
                sync.postLocal("${playable.fileName} is already in the playlist")
            } else {
                client.addToPlaylist(playable.fileName)
                sync.postLocal("Added ${playable.fileName} to the room playlist")
            }
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
            remember(playable)
            if (status != null) knownPlayables[status.fileName] = playable
            _openPlayerRequests.value++
            load(playable.toNowPlaying(), resetPosition = true, fromRoom = true)
        }
    }

    fun selectIndex(index: Int) = sync.client?.selectPlaylistIndex(index)
    fun removeIndex(index: Int) = sync.client?.removeFromPlaylist(index)
    fun move(from: Int, to: Int) = sync.client?.movePlaylistItem(from, to)

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

    private fun remember(playable: PlayableMedia) {
        knownPlayables[playable.fileName] = playable
        while (knownPlayables.size > 200) knownPlayables.remove(knownPlayables.keys.first())
    }

    private fun PlayableMedia.toNowPlaying() = NowPlaying(title, fileName, sizeBytes, durationSeconds, url, itemId)

    companion object {
        private const val TAG = "PlaylistController"
    }
}
