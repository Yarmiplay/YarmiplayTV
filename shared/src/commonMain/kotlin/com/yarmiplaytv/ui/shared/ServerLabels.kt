package com.yarmiplaytv.ui.shared

import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexServer
import com.yarmiplaytv.media.plex.PlexSource

/** "Jellyfin" or "Plex" for a [MediaSource.kind]. */
fun kindName(kind: String): String = when (kind) {
    JellyfinSource.KIND -> "Jellyfin"
    PlexSource.KIND -> "Plex"
    else -> "Media server"
}

/** Who is signed in and where, e.g. "Jellyfin · sam · http://192.168.1.20:8096". */
fun serverDetail(source: MediaSource): String {
    val (user, url) = when (source) {
        is JellyfinSource -> source.session.userName to source.session.serverUrl
        is PlexSource -> source.session.userName to source.session.serverUrl
        else -> "" to ""
    }
    return listOf(kindName(source.kind), user, url).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** A server's name with its backend, for lists that mix both, e.g. "Living Room (Jellyfin)". */
fun serverLabel(source: MediaSource): String = "${source.displayName} (${kindName(source.kind)})"

/** Test tag suffix for a server key (keys contain ':', which UiAutomator resource names don't like). */
fun serverTag(key: String): String = key.replace(':', '_')

fun plexServerLabel(server: PlexServer, added: Boolean): String = buildString {
    append(server.name)
    if (!server.owned) append(" (shared)")
    if (added) append(" · added")
}

/** Home card title for one backend's servers, e.g. "Jellyfin: Living Room" or "Plex: 2 servers". */
fun serversTitle(kind: String, servers: List<MediaSource>): String = when (servers.size) {
    0 -> "Connect ${kindName(kind)}"
    1 -> "${kindName(kind)}: ${servers.single().displayName}"
    else -> "${kindName(kind)}: ${servers.size} servers"
}

/** Home card detail for one backend's servers once at least one is signed in. */
fun serversDetail(servers: List<MediaSource>): String =
    if (servers.size == 1) "Signed in" else servers.joinToString(", ") { it.displayName }

/** The server that streams a room's file when several have it: the saved one, else the first. */
fun preferredOf(servers: List<MediaSource>, preferredKey: String): MediaSource? =
    servers.firstOrNull { it.key == preferredKey } ?: servers.firstOrNull()

/** The server after the preferred one, for a setting that cycles through them. */
fun nextPreferred(servers: List<MediaSource>, preferredKey: String): String {
    val i = servers.indexOfFirst { it.key == preferredKey }.coerceAtLeast(0)
    return servers[(i + 1) % servers.size].key
}
