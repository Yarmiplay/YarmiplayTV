package com.yarmiplaytv.sync

import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.syncplay.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Saves the shared playlist of each room (per server) as it changes, and puts it back when joining the
 * room again finds its playlist empty, e.g. after everyone left a non-persistent room or the server restarted.
 */
class PlaylistAutosave(
    scope: CoroutineScope,
    private val sync: SyncController,
    private val shared: SharedPlaylist,
    private val store: SettingsStore,
) {
    var enabled = true

    /** The room ("host:port/room") whose playlist has arrived since we last (re)joined a room. */
    private var joined: String? = null

    private data class Snapshot(val inRoom: Boolean, val room: String, val playlist: List<String>)

    init {
        scope.launch {
            sync.room
                .map { Snapshot(it.status == ConnectionStatus.CONNECTED && it.playlistReceived, it.room, it.playlist) }
                .distinctUntilChanged()
                .collect { (inRoom, room, playlist) ->
                    val key = key(room)
                    if (!inRoom || key == null) {
                        joined = null
                    } else if (key != joined) {
                        joined = key
                        onJoined(key, playlist)
                    } else if (enabled) {
                        store.saveRoomPlaylist(key, playlist)
                    }
                }
        }
    }

    private suspend fun onJoined(key: String, playlist: List<String>) {
        if (!enabled) return
        if (playlist.isNotEmpty()) {
            store.saveRoomPlaylist(key, playlist)
            return
        }
        val saved = store.roomPlaylist(key)
        // Someone may have filled the room, or we may have left it, while the saved list was being read.
        if (saved.isEmpty() || joined != key || sync.room.value.playlist.isNotEmpty()) return
        shared.replace(saved)
        sync.postLocal("Restored this room's playlist (${saved.size} ${if (saved.size == 1) "entry" else "entries"})")
    }

    private fun key(room: String): String? {
        val server = sync.server ?: return null
        return if (room.isBlank()) null else "$server/$room"
    }
}
