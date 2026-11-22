package com.yarmiplaytv.network

import kotlinx.serialization.Serializable

/** A saved FTP or SMB place. The password is stored with the other settings and is never part of [toString]. */
@Serializable
data class NetworkLocation(
    val id: String,
    /** `ftp` or `smb`. */
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    /** SMB share name. Empty for FTP. */
    val share: String = "",
    val name: String,
    /** Directory opened first, relative to the FTP login directory or the SMB share root. */
    val startPath: String = "",
) {
    override fun toString(): String =
        "NetworkLocation(id=$id, protocol=$protocol, host=$host, port=$port, username=$username, share=$share, name=$name)"
}

data class NetworkEntry(val name: String, val directory: Boolean, val size: Long)

/** What went wrong while checking a location the user typed, or the location to save. */
sealed interface NetworkDraft {
    data class Ok(val location: NetworkLocation) : NetworkDraft
    data class Invalid(val reason: String) : NetworkDraft
}

object NetworkLocations {
    const val FTP = "ftp"
    const val SMB = "smb"

    fun draft(
        id: String,
        protocol: String,
        host: String,
        portText: String,
        username: String,
        password: String,
        share: String,
        name: String,
        startPath: String,
    ): NetworkDraft {
        val kind = protocol.trim().lowercase()
        if (kind != FTP && kind != SMB) return NetworkDraft.Invalid("Choose FTP or SMB")
        val cleanedHost = host.trim()
        if (!acceptableHost(cleanedHost)) return NetworkDraft.Invalid("Enter the server's address")
        val port = if (portText.isBlank()) defaultPort(kind) else portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) return NetworkDraft.Invalid("The port has to be from 1 to 65535")
        val cleanedShare = share.trim().trim('/')
        if (kind == SMB && (cleanedShare.isEmpty() || cleanedShare.contains('\\') || cleanedShare.contains('/'))) {
            return NetworkDraft.Invalid("Enter the SMB share name")
        }
        val start = normalizeRelative(startPath) ?: return NetworkDraft.Invalid("The folder can't go above the share")
        val label = name.trim().ifEmpty { defaultName(kind, cleanedHost, cleanedShare) }
        return NetworkDraft.Ok(
            NetworkLocation(
                id = id,
                protocol = kind,
                host = cleanedHost,
                port = port,
                username = username,
                password = password,
                share = if (kind == SMB) cleanedShare else "",
                name = label,
                startPath = start,
            ),
        )
    }

    fun defaultPort(protocol: String): Int = if (protocol == SMB) 445 else 21

    fun defaultName(protocol: String, host: String, share: String): String =
        if (protocol == SMB && share.isNotEmpty()) "$share on $host" else host

    /** A hostname or an IPv4/IPv6 address, with nothing that could turn it into a URL or a path. */
    fun acceptableHost(host: String): Boolean {
        if (host.length !in 1..253) return false
        if (host.any { it.isWhitespace() || it in "/\\@?#:" }) return false
        return true
    }

    /**
     * A directory relative to the share. `..` may climb only as far as that root; a path that would leave it
     * is rejected so a listing can't be pointed at another place.
     */
    fun normalizeRelative(path: String): String? {
        val parts = path.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (part in parts) {
            if (part == "..") {
                if (stack.isEmpty()) return null
                stack.removeLast()
            } else if (part.any { it == '\u0000' || it == '/' || it == '\\' }) {
                return null
            } else {
                stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    fun childPath(directory: String, name: String): String? {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) return null
        val combined = if (directory.isEmpty()) name else "$directory/$name"
        return normalizeRelative(combined)
    }
}
