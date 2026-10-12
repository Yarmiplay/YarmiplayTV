package com.yarmiplaytv.sync

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The room's "skip openings and endings" setting, as the room screens show it. */
data class RoomSkip(
    /** In a room at all; out of one, nothing to show. */
    val inRoom: Boolean = false,
    val on: Boolean = false,
    /** The setting is the room's, the same for everyone (YarmiplayServerTV); else it is this device's choice. */
    val shared: Boolean = false,
    /** Who turned it on or off last, when shared. */
    val setBy: String? = null,
    /** Whether this device may change it (in a managed room only operators can). */
    val canChange: Boolean = false,
)

/**
 * Skipping openings and endings in rooms. On YarmiplayServerTV it is the room's setting, shared by everyone in it;
 * elsewhere each device decides for itself, remembered with the room in the Saved and Recent lists. A saved room
 * this device last had it on in turns it on again on joining (never off: someone else may have wanted it).
 */
class RoomAutoSkip(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val history: RoomHistory,
    private val store: SettingsStore,
    private val settings: StateFlow<AppSettings>,
) {
    val state: StateFlow<RoomSkip> = combine(sync.room, settings) { room, s -> stateOf(room, s) }
        .stateIn(scope, SharingStarted.Eagerly, RoomSkip())

    init {
        scope.launch {
            sync.events.collect { event ->
                if (event !is SyncplayEvent.RoomSettingsChanged) return@collect
                val me = sync.room.value.username
                if (event.first) {
                    restoreSaved(event.settings.autoSkip)
                } else if (event.settings.setBy != null && event.settings.setBy != me) {
                    val what = if (event.settings.autoSkip) "turned on" else "turned off"
                    sync.postLocal("${event.settings.setBy} $what skipping openings and endings")
                }
            }
        }
    }

    /** Turns it on or off: for the whole room when shared, else for this device in this room. */
    fun set(on: Boolean) {
        val room = sync.room.value
        if (room.status != ConnectionStatus.CONNECTED || !state.value.canChange) return
        if (room.yarmiplay.roomSettingsShared) sync.client?.setRoomAutoSkip(on)
        val key = history.visitOf(room.room)?.key ?: return
        scope.launch { store.setRoomAutoSkip(key, on) }
    }

    private fun stateOf(room: RoomState, s: AppSettings): RoomSkip {
        if (room.status != ConnectionStatus.CONNECTED || room.room.isBlank()) return RoomSkip()
        if (room.yarmiplay.roomSettingsShared) {
            val shared = room.yarmiplay.roomSettings
            return RoomSkip(inRoom = true, on = shared?.autoSkip == true, shared = true, setBy = shared?.setBy, canChange = !room.followsOperators)
        }
        val key = history.visitOf(room.room)?.key
        val mine = s.savedRooms.firstOrNull { it.key == key }?.autoSkip == true
        return RoomSkip(inRoom = true, on = mine, shared = false, canChange = true)
    }

    /** Joined a room whose settings are shared: a saved room this device had it on in turns it on again. */
    private fun restoreSaved(roomHasIt: Boolean) {
        if (roomHasIt) return
        val room = sync.room.value
        val key = history.visitOf(room.room)?.key ?: return
        val saved = settings.value.savedRooms.firstOrNull { it.key == key } ?: return
        if (!saved.saved || !saved.autoSkip) return
        scope.launch {
            // In a managed room, operator status comes from the saved password just after joining.
            withTimeoutOrNull(OPERATOR_WAIT_MS) { sync.room.first { !it.followsOperators } } ?: return@launch
            val now = sync.room.value
            if (now.room == room.room && now.yarmiplay.roomSettings?.autoSkip != true) sync.client?.setRoomAutoSkip(true)
        }
    }

    private companion object {
        const val OPERATOR_WAIT_MS = 10_000L
    }
}
