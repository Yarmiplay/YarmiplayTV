package com.yarmiplaytv.sync

import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.syncplay.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the rooms joined, for rejoining from a list: every room reached (the first join, switching rooms, a new
 * managed room) becomes a recent room, or freshens a saved one. Only rooms actually joined are kept, so a mistyped
 * server never is. Switching rooms also moves the saved connection there, so starting the app goes back to it.
 */
class RoomHistory(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val store: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** "Save this room" on the join form: kept once that room is joined (port and room), not before. */
    @Volatile private var saveNext: Pair<Int, String>? = null

    fun saveWhenJoined(port: Int, room: String) {
        saveNext = port to room.trim()
    }

    fun start() {
        scope.launch {
            sync.room.map { (it.status == ConnectionStatus.CONNECTED) to it.room }.distinctUntilChanged().collect { (connected, room) ->
                if (connected && room.isNotBlank()) record(room)
            }
        }
    }

    /** [room] on the current connection as a list entry; [SavedRoom.key] identifies its entry in the lists. */
    fun visitOf(room: String): SavedRoom? {
        val config = sync.client?.config ?: return null
        // The server as the app knows it now: a typed IP that turned out to have a name is saved under the name.
        val server = sync.server ?: SyncController.serverKey(config.host, config.port)
        val host = server.substringBeforeLast(':')
        val port = server.substringAfterLast(':').toIntOrNull() ?: config.port
        return SavedRoom(host, port, config.useTls, config.username, room, config.password.orEmpty(), lastUsedMillis = now())
    }

    private suspend fun record(room: String) {
        val visit = visitOf(room) ?: return
        val port = visit.port
        store.saveRoomVisit(visit)
        if (saveNext == (port to room)) {
            saveNext = null
            if (store.current().savedRooms.none { it.key == visit.key && it.saved }) store.setRoomSaved(visit.key, true)
        }
        val profile = store.current().syncplay
        if (profile.room != room && profile.host.equals(visit.host, ignoreCase = true) && profile.port == port) store.saveSyncplay(profile.copy(room = room))
    }
}
