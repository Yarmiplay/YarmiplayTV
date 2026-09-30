package com.syncplaytv.syncplay

/** One-off things that happened; persistent state lives in [RoomState]. */
sealed interface SyncplayEvent {
    data class Connected(val username: String, val room: String, val motd: String?, val tls: Boolean) : SyncplayEvent
    data class Disconnected(val reason: String?, val willReconnect: Boolean) : SyncplayEvent

    /** Something worth showing on screen, e.g. "Alice paused". */
    data class Notification(val message: String, val isError: Boolean = false) : SyncplayEvent
    data class Chat(val username: String, val message: String) : SyncplayEvent

    /**
     * The room selected a playlist entry and this client should load it.
     * [resetPosition] is false only for the first index received after joining a room,
     * in which case playback should follow the room position instead of starting at 0.
     */
    data class SwitchToPlaylistItem(
        val index: Int,
        val filename: String,
        val setBy: String?,
        val resetPosition: Boolean,
    ) : SyncplayEvent

    data class PlaylistChanged(val files: List<String>, val setBy: String?) : SyncplayEvent

    /** Playback reached the end of the file and the next playlist entry should be loaded locally. */
    data class AdvancePlaylist(val nextIndex: Int, val filename: String) : SyncplayEvent
}
