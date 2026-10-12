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

    // --- Managed rooms ---------------------------------------------------------------

    /** [SyncplayClient.createManagedRoom] made [room] (the full `+name:HASH`), which we've joined; [password] makes its operators. */
    data class ManagedRoomCreated(val room: String, val password: String) : SyncplayEvent

    /**
     * Someone tried to become an operator of [room]. For our own attempts [password] is the one we sent, so it can
     * be saved on success and forgotten on failure; null for other users.
     */
    data class ControllerAuth(val user: String, val room: String, val success: Boolean, val password: String? = null) : SyncplayEvent

    /** [setBy], an operator, marked [user] (maybe us) ready or not ready. */
    data class ReadySetBy(val user: String, val setBy: String, val isReady: Boolean) : SyncplayEvent

    // --- YarmiplayServerTV extensions ------------------------------------------------

    /** The device state changed (see [YarmiplayInfo.device]); pending, approved and the refusals each come once. */
    data class DeviceStateChanged(val state: DeviceState) : SyncplayEvent

    /** A new extension session started; the HTTP side is reachable with [YarmiplaySession]. */
    data class SessionStarted(val session: YarmiplaySession) : SyncplayEvent

    /** The session ended: vanilla mode switched on, or the connection closed. Stop all relay and Jellyfin work. */
    data object SessionEnded : SyncplayEvent

    /**
     * The server typed as IP address [address] has a certificate for [name]; the connection verifies it as that
     * name. Saving the server under [name] lets it be joined away from home too.
     */
    data class ServerNamed(val address: String, val name: String) : SyncplayEvent

    /** The server wants bytes of a file this device offered. */
    data class UploadRequested(val request: UploadRequest) : SyncplayEvent
    data class UploadCancelled(val id: String) : SyncplayEvent

    /** The server's answer to [SyncplayClient.authorizeJellyfin]. */
    data class JellyfinAuthorized(val code: String, val ok: Boolean, val error: String?) : SyncplayEvent
}
