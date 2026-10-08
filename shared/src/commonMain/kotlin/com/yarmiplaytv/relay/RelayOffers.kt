package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.local.LocalMatcher
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.QuickHash
import com.yarmiplaytv.syncplay.RelayOffer
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.syncplay.Yarmiplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Offers the room's relay the local files that match its playlist, again whenever the room, the playlist, the
 * local index or the session changes (the client drops repeats of what the server already has). With [sharing]
 * off it offers nothing and withdraws what it offered, so upload requests find no file and are refused.
 */
@OptIn(FlowPreview::class)
class RelayOffers(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val local: LocalLibrary,
    private val sharing: Flow<Boolean>,
) {
    private val hashes = ConcurrentHashMap<String, String>()
    @Volatile private var offered: Map<String, LocalFile> = emptyMap()

    private data class Inputs(val room: String, val playlist: List<String>, val active: Boolean, val files: List<LocalFile>, val sharing: Boolean)

    fun start() {
        scope.launch {
            combine(sync.room, local.files, sharing) { room, files, sharing ->
                Inputs(room.room, room.playlist, room.yarmiplay.relayActive, files, sharing)
            }.distinctUntilChanged().debounce(300).collectLatest { inputs ->
                if (!inputs.active || !inputs.sharing) {
                    offered = emptyMap()
                    if (inputs.active) sync.offerFiles(emptyList())
                    return@collectLatest
                }
                val users = sync.room.value.users
                val reported = sync.currentFile
                val list = withContext(Dispatchers.IO) {
                    offersFor(inputs.playlist, inputs.files, users, reported?.let { it.name to it.duration }, ::hashOf)
                }
                offered = list.associate { (offer, file) -> key(offer.size, offer.quickHash) to file }
                sync.offerFiles(list.map { it.first })
            }
        }
    }

    /** The offered local file with [size] and [quickHash], for an upload request. */
    fun fileFor(size: Long, quickHash: String): LocalFile? = offered[key(size, quickHash)]

    /** The file's quickHash, or null when it can't be read; cached until the file's size or date changes. */
    private fun hashOf(file: LocalFile): String? {
        val cacheKey = "${file.uri}|${file.sizeBytes}|${file.modified}"
        hashes[cacheKey]?.let { return it }
        return runCatching {
            local.openChannel(file.uri).use { channel ->
                QuickHash.of(file.sizeBytes) { offset, buffer ->
                    val bb = ByteBuffer.wrap(buffer)
                    var pos = offset
                    while (bb.hasRemaining()) {
                        val n = channel.read(bb, pos)
                        if (n < 0) error("File is shorter than its size")
                        pos += n
                    }
                }
            }
        }.onFailure { Logger.w(TAG, "Can't hash ${file.name}: ${it.message}") }
            .getOrNull()
            ?.also { hashes[cacheKey] = it }
    }

    companion object {
        private const val TAG = "RelayOffers"

        private fun key(size: Long, quickHash: String) = "$size:$quickHash"

        /**
         * The playlist entries this device has, as relay offers named like the playlist entry (so viewers match
         * them by name), with the local file each one reads. Durations come from what we or the room reported.
         */
        fun offersFor(
            playlist: List<String>,
            files: List<LocalFile>,
            users: List<RoomUser>,
            reported: Pair<String, Double>?,
            hash: (LocalFile) -> String?,
        ): List<Pair<RelayOffer, LocalFile>> {
            val out = ArrayList<Pair<RelayOffer, LocalFile>>()
            val seen = HashSet<String>()
            for (entry in playlist) {
                if (out.size >= Yarmiplay.MAX_OFFERED_FILES) break
                if (Filenames.isUrl(entry)) continue
                val name = FileNames.baseName(entry)
                if (!seen.add(name)) continue
                val file = LocalMatcher.find(files, entry)?.file ?: continue
                if (file.sizeBytes <= 0) continue
                val quickHash = hash(file) ?: continue
                val duration = reported?.takeIf { it.first == name || it.first == file.name }?.second?.takeIf { it > 0 }
                    ?: users.firstNotNullOfOrNull { u -> u.file?.takeIf { it.name == name && it.duration > 0 }?.duration }
                    ?: 0.0
                out += RelayOffer(name, file.sizeBytes, duration, quickHash) to file
            }
            return out
        }
    }
}
