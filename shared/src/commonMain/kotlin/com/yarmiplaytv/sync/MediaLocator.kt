package com.yarmiplaytv.sync

import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.local.LocalMatcher
import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.media.ResolveResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow

/** A server item that is the playing file; playback is reported to each one. */
data class ServerCopy(val sourceKey: String, val itemId: String)

/** Where a room's playlist entry is found. */
sealed interface RoomLocation {
    data class Local(val match: LocalMatcher.Match) : RoomLocation
    /** [copies] are every server item that is this exact file, the streamed one included. */
    data class Server(val playable: PlayableMedia, val matchedBy: MatchKind, val copies: List<ServerCopy>) : RoomLocation
    data class Missing(val reason: String) : RoomLocation
}

/**
 * Decides which single copy of a file is opened, and finds the same file on the other servers
 * (exact name and size only) so playback can be reported to all of them.
 */
class MediaLocator(
    private val local: LocalLibrary,
    private val servers: StateFlow<List<MediaSource>>,
) {
    /** [MediaSource.key] of the server that streams a room's file when several have it exactly; empty = the first. */
    @Volatile var preferredServer: String = ""

    val hasServers: Boolean get() = servers.value.isNotEmpty()

    /**
     * A file someone in the room picked: local media folders first; then an exact filename on any
     * server (the preferred one breaks ties); only when no server has it exactly, a looser match
     * (normalized name, then title search) on the preferred server, then the others.
     */
    suspend fun locateForRoom(fileName: String): RoomLocation {
        local.resolve(fileName)?.let { return RoomLocation.Local(it) }
        val servers = ordered(servers.value)
        if (servers.isEmpty()) return RoomLocation.Missing("Not in your media folders")

        val exact = coroutineScope {
            servers.map { s -> async { runCatching { s.findExact(fileName) }.getOrNull()?.let { s to it } } }.awaitAll()
        }.filterNotNull()
        if (exact.isNotEmpty()) {
            val primary = exact.first().second.playable
            val copies = exact
                .filter { (_, found) -> found.playable === primary || sameSize(found.playable.sizeBytes, primary.sizeBytes) }
                .map { (s, found) -> ServerCopy(s.key, found.playable.itemId) }
            return RoomLocation.Server(primary, MatchKind.EXACT_FILENAME, copies)
        }

        val reasons = ArrayList<String>()
        for (s in servers) {
            when (val r = runCatching { s.resolveByFilename(fileName) }.getOrElse { ResolveResult.NotFound(fileName, it.message ?: "Lookup failed") }) {
                is ResolveResult.Found -> return RoomLocation.Server(r.playable, r.matchedBy, listOf(ServerCopy(s.key, r.playable.itemId)))
                is ResolveResult.NotFound -> reasons += r.reason
            }
        }
        return RoomLocation.Missing(reasons.joinToString("; "))
    }

    /**
     * Every server item that is exactly [playable]'s file (same name, and same size when both are
     * known), its own item included. Looks only at the servers' filename indexes; never opens anything.
     */
    suspend fun copiesOf(playable: PlayableMedia): List<ServerCopy> {
        val own = ownCopy(playable)
        val others = coroutineScope {
            servers.value.filter { it.key != playable.sourceKey }.map { s ->
                async {
                    runCatching { s.findExact(playable.fileName) }.getOrNull()
                        ?.takeIf { sameSize(it.playable.sizeBytes, playable.sizeBytes) }
                        ?.let { ServerCopy(s.key, it.playable.itemId) }
                }
            }.awaitAll()
        }.filterNotNull()
        return own + others
    }

    private fun ordered(list: List<MediaSource>): List<MediaSource> = list.sortedBy { if (it.key == preferredServer) 0 else 1 }

    companion object {
        fun ownCopy(playable: PlayableMedia): List<ServerCopy> =
            if (playable.sourceKey.isEmpty()) emptyList() else listOf(ServerCopy(playable.sourceKey, playable.itemId))

        private fun sameSize(a: Long, b: Long) = a <= 0 || b <= 0 || a == b
    }
}
