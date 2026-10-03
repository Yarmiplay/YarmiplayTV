package com.yarmiplaytv.ui.shared

import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One server's continue-watching row on home, shown when several servers are connected. */
data class ServerRecent(val server: MediaSource, val items: List<MediaItem>, val error: String?) {
    val title: String get() = "Continue watching · ${server.displayName}"
}

/** Every server's [MediaSource.recent] at once; a server that fails only fails its own row. */
suspend fun recentByServer(servers: List<MediaSource>): List<ServerRecent> = coroutineScope {
    servers.map { s ->
        async {
            runCatching { s.recent() }.fold(
                onSuccess = { ServerRecent(s, it, null) },
                onFailure = { ServerRecent(s, emptyList(), it.message ?: it.toString()) },
            )
        }
    }.awaitAll()
}
