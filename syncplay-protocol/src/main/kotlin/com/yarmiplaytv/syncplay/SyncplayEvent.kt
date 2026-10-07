package com.yarmiplaytv.syncplay

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

    /** The server sent a new playlist; [setBy] is null for the room's list sent on joining it, which comes even when empty. */
    data class PlaylistChanged(val files: List<String>, val setBy: String?, val previous: List<String> = emptyList()) : SyncplayEvent

    /** Playback reached the end of the file and the next playlist entry should be loaded locally. */
    data class AdvancePlaylist(val nextIndex: Int, val filename: String) : SyncplayEvent

    // --- YarmiplayServerTV extensions ------------------------------------------------

    /** The device state changed (see [YarmiplayInfo.device]); pending, approved and the refusals each come once. */
    data class DeviceStateChanged(val state: DeviceState) : SyncplayEvent

    /** A new extension session started; the HTTP side is reachable with [YarmiplaySession]. */
    data class SessionStarted(val session: YarmiplaySession) : SyncplayEvent

    /** The session ended: vanilla mode switched on, or the connection closed. Stop all relay and Jellyfin work. */
    data object SessionEnded : SyncplayEvent

    /** The server wants bytes of a file this device offered. */
    data class UploadRequested(val request: UploadRequest) : SyncplayEvent
    data class UploadCancelled(val id: String) : SyncplayEvent

    /** The server's answer to [SyncplayClient.authorizeJellyfin]. */
    data class JellyfinAuthorized(val code: String, val ok: Boolean, val error: String?) : SyncplayEvent
}
