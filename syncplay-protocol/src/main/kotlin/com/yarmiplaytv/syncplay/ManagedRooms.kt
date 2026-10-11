package com.yarmiplaytv.syncplay

import java.security.SecureRandom

/**
 * Syncplay's managed (controlled) rooms: only the room's operators can play, pause, seek or change the playlist.
 * A managed room is named `+<name>:<HASH>`, where the server derives the 12-character hash from the name, the
 * operator password and its own salt; clients never compute it.
 */
object ManagedRooms {
    private val NAME = Regex("^\\+(.*):(\\w{12})$")
    private val PASSWORD = Regex("^[A-Z]{2}-\\d{3}-\\d{3}$")

    /** Syncplay's limit on room names; the `+` and `:HASH` take 14 of them. */
    const val MAX_ROOM_LENGTH = 35
    const val MAX_BASE_NAME_LENGTH = MAX_ROOM_LENGTH - 14

    fun isManaged(room: String): Boolean = NAME.matches(room)

    /** The name people chose: `movie night` for `+movie night:D6A85289CDB5`; other names as they are. */
    fun display(room: String): String = NAME.matchEntire(room)?.groupValues?.get(1) ?: room

    /** [display] with a lock in front of managed rooms, for one-line labels. */
    fun label(room: String): String = if (isManaged(room)) "🔒 ${display(room)}" else room

    /** Whether [baseName] can become a managed room: 1 to [MAX_BASE_NAME_LENGTH] characters once trimmed. */
    fun isValidBaseName(baseName: String): Boolean = baseName.trim().length in 1..MAX_BASE_NAME_LENGTH

    fun isValidPassword(password: String): Boolean = PASSWORD.matches(password)

    /** What someone typed as a password, the way the server expects it: trimmed and uppercase. */
    fun normalizePassword(input: String): String = input.trim().uppercase()

    /** A new operator password like `AB-123-456`, from a secure random source. */
    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        fun letter() = 'A' + random.nextInt(26)
        fun digits() = "%03d".format(random.nextInt(1000))
        return "${letter()}${letter()}-${digits()}-${digits()}"
    }
}
