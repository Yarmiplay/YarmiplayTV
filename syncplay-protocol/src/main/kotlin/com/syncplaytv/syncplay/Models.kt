package com.syncplaytv.syncplay

/** Connection settings for one Syncplay server/room. */
data class SyncplayConfig(
    val host: String,
    val port: Int = 8999,
    val username: String,
    val room: String,
    /** Plain-text server password; hashed with MD5 before sending, like the desktop client. */
    val password: String? = null,
    /** Try STARTTLS first and fall back to plain TCP if the server doesn't support it. */
    val useTls: Boolean = true,
    val autoReconnect: Boolean = true,
)

/** The desktop client's sync tuning, with the same defaults. */
data class SyncSettings(
    val rewindOnDesync: Boolean = true,
    val rewindThreshold: Double = Constants.DEFAULT_REWIND_THRESHOLD,
    val slowOnDesync: Boolean = true,
    val slowdownThreshold: Double = Constants.DEFAULT_SLOWDOWN_KICKIN_THRESHOLD,
    val unpauseMode: UnpauseMode = UnpauseMode.IF_OTHERS_READY,
    /** Ready state sent right after connecting when we have no previous ready state. */
    val readyAtStart: Boolean = false,
)

enum class UnpauseMode {
    /** Unpausing always unpauses the room. */
    ALWAYS,

    /** Unpausing only unpauses the room if everyone else is ready; otherwise it just marks you ready. */
    IF_OTHERS_READY,

    /** First unpause marks you ready, second one unpauses. */
    IF_ALREADY_READY,
}

data class FileInfo(
    val name: String,
    /** Seconds. */
    val duration: Double,
    /** Bytes; 0 when unknown. */
    val size: Long,
)

data class RoomUser(
    val name: String,
    val room: String,
    val file: FileInfo? = null,
    /** null when the user's client doesn't support readiness. */
    val isReady: Boolean? = null,
    val isController: Boolean = false,
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

data class RoomState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    /** Our username as confirmed by the server (it may add a suffix if the name is taken). */
    val username: String = "",
    val room: String = "",
    val serverVersion: String? = null,
    val motd: String? = null,
    val tls: Boolean = false,
    /** Everyone in our room, including us. */
    val users: List<RoomUser> = emptyList(),
    val playlist: List<String> = emptyList(),
    val playlistIndex: Int? = null,
    val isReady: Boolean? = null,
    val globalPaused: Boolean = true,
    val globalPosition: Double = 0.0,
    val lastSetBy: String? = null,
    val serverFeatures: Map<String, Any?> = emptyMap(),
    val rttMillis: Double = 0.0,
) {
    val others: List<RoomUser> get() = users.filter { it.name != username }
    val currentPlaylistFile: String? get() = playlistIndex?.let { playlist.getOrNull(it) }
}

object Constants {
    const val CLIENT_VERSION = "1.2.255"
    const val REAL_VERSION = "1.7.6"
    const val DEFAULT_PORT = 8999

    const val SEEK_THRESHOLD = 1.0
    const val DEFAULT_REWIND_THRESHOLD = 4.0
    const val SLOWDOWN_RATE = 0.95
    const val DEFAULT_SLOWDOWN_KICKIN_THRESHOLD = 1.5
    const val SLOWDOWN_RESET_THRESHOLD = 0.1
    const val DIFFERENT_DURATION_THRESHOLD = 2.5
    const val PROTOCOL_TIMEOUT = 12.5
    const val PING_MOVING_AVERAGE_WEIGHT = 0.85
    const val PLAYER_ASK_DELAY_MS = 100L
    const val PLAYLIST_LOAD_NEXT_FILE_MINIMUM_LENGTH = 10.0
    const val PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD = 5.0
    const val NEWFILE_IGNORE_TIME = 1.0
    const val STREAM_ADDITIONAL_IGNORE_TIME = 10.0
    const val RECENT_REWIND_THRESHOLD = 5.0
    const val AUTOPLAY_DELAY = 3.0
}
