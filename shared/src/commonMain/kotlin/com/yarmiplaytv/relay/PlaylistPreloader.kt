package com.yarmiplaytv.relay

import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.media.SharedHttp
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.MediaLocator
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.sync.RoomLocation
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Summary of background playlist pre-downloading, shown in Settings > Media. */
data class PreloadState(
    val activeFile: String? = null,
    val cachedCount: Int = 0,
    val cachedBytes: Long = 0L,
    val pausedForPlayback: Boolean = false,
    val blockedByNetwork: Boolean = false,
)

/** Pure gating decision for background pre-downloading so playback always gets first priority. */
internal enum class PreloadGate {
    ALLOWED,
    DISABLED_BY_SETTING,
    YIELD_TO_PLAYBACK,
}

internal object PreloadPolicy {
    /** Remote stream buffer floor (seconds) before background pre-downloads may run. */
    const val MIN_PLAYBACK_CACHE_SECONDS = 15.0
    /** Within this many seconds of EOF, mpv's cache naturally shrinks toward 0 — don't falsely treat it as starving. */
    const val EOF_MARGIN_SECONDS = 20.0

    fun isRemoteUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }

    fun gate(
        settings: AppSettings,
        onUnmetered: Boolean,
        nowPlaying: NowPlaying?,
        playback: PlaybackState,
        playlistStatus: PlaylistStatus,
        relayCurrentIncomplete: Boolean,
    ): PreloadGate {
        val enabled = if (onUnmetered) settings.preDownloadPlaylistWifi else settings.preDownloadPlaylistMobile
        if (!enabled) return PreloadGate.DISABLED_BY_SETTING
        if (playlistStatus is PlaylistStatus.Resolving || playlistStatus is PlaylistStatus.Loading) {
            return PreloadGate.YIELD_TO_PLAYBACK
        }
        if (nowPlaying != null) {
            if (!playback.fileLoaded || playback.buffering || playback.seeking) {
                return PreloadGate.YIELD_TO_PLAYBACK
            }
            if (relayCurrentIncomplete) {
                return PreloadGate.YIELD_TO_PLAYBACK
            }
            val nearEnd = playback.eofReached ||
                (playback.duration > 0.0 && playback.position >= playback.duration - EOF_MARGIN_SECONDS)
            if (isRemoteUrl(nowPlaying.url) && !nearEnd && playback.cacheSeconds < MIN_PLAYBACK_CACHE_SECONDS) {
                return PreloadGate.YIELD_TO_PLAYBACK
            }
        }
        return PreloadGate.ALLOWED
    }

    /** Upcoming playlist indices in priority order (next entry first), excluding [currentIndex]. */
    fun upcomingIndices(size: Int, currentIndex: Int, loopPlaylist: Boolean): List<Int> {
        if (size <= 1 || currentIndex !in 0 until size) return emptyList()
        val out = ArrayList<Int>(size - 1)
        for (i in (currentIndex + 1) until size) out += i
        if (loopPlaylist) {
            for (i in 0 until currentIndex) out += i
        }
        return out
    }
}

/**
 * Pre-downloads upcoming room playlist entries in the background while giving strict priority to the
 * currently playing video:
 * - Relay entries use `mode=prefetch&order=N` via [RelayManager.prefetch] (full file).
 * - Jellyfin/Plex server entries download via resumable HTTP Range into `<cacheDir>/preload/`.
 * - Immediately pauses whenever the active video is loading, seeking, buffering, has low cache, or
 *   is still downloading over the relay.
 * - Controlled solely from Settings > Media (Wi-Fi/Ethernet default ON, mobile data default OFF).
 */
class PlaylistPreloader(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val player: Player,
    private val nowPlaying: StateFlow<NowPlaying?>,
    private val playlistStatus: StateFlow<PlaylistStatus>,
    private val locator: MediaLocator,
    private val relay: RelayManager?,
    private val settings: StateFlow<AppSettings>,
    cacheDir: File,
    private val onUnmetered: () -> Boolean = { DevicePlatform.onUnmeteredNetwork },
    private val http: OkHttpClient = SharedHttp.base.newBuilder()
        .dns(SessionDns)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val preloadDir = File(cacheDir, "preload").apply {
        deleteRecursively()
        mkdirs()
    }
    private val entries = ConcurrentHashMap<String, PreloadedEntry>()
    private val located = ConcurrentHashMap<String, RoomLocation>()
    private val _state = MutableStateFlow(PreloadState())
    val state: StateFlow<PreloadState> = _state.asStateFlow()

    private var worker: Job? = null
    private var lastRoom: String? = null
    @Volatile private var activeGate: PreloadGate = PreloadGate.DISABLED_BY_SETTING

    private data class PreloadedEntry(
        val key: String,
        val fileName: String,
        val sizeBytes: Long,
        val file: File,
        @Volatile var complete: Boolean,
        @Volatile var lastUsed: Long = System.currentTimeMillis(),
    )

    init {
        // React promptly when playback state, room playlist, or settings change.
        scope.launch {
            combine(
                settings.map { Triple(it.preDownloadPlaylistWifi, it.preDownloadPlaylistMobile, it.preDownloadCacheGb) }.distinctUntilChanged(),
                player.state.map { StateSnapshot(it.fileLoaded, it.buffering, it.seeking, it.eofReached, it.cacheSeconds >= PreloadPolicy.MIN_PLAYBACK_CACHE_SECONDS) }.distinctUntilChanged(),
                nowPlaying.map { it?.url }.distinctUntilChanged(),
                playlistStatus,
                sync.room.map { RoomSnapshot(it.status, it.room, it.playlist, it.playlistIndex, it.yarmiplay.relayFiles.size) }.distinctUntilChanged(),
            ) { _, _, _, _, room -> room }.collect { room ->
                onRoomOrStateChanged(room)
            }
        }
    }

    private data class StateSnapshot(
        val loaded: Boolean,
        val buffering: Boolean,
        val seeking: Boolean,
        val eof: Boolean,
        val healthyCache: Boolean,
    )

    private data class RoomSnapshot(
        val status: ConnectionStatus,
        val room: String,
        val playlist: List<String>,
        val index: Int?,
        val relayCount: Int,
    )

    /** Returns the local cached file path if [fileName] was completely pre-downloaded for [sizeBytes]. */
    fun completePath(fileName: String, sizeBytes: Long): String? {
        val key = entryKey(fileName, sizeBytes)
        val hit = entries[key]
            ?: if (sizeBytes <= 0L) entries.values.firstOrNull { Filenames.same(it.fileName, fileName) && it.complete } else null
        if (hit == null || !hit.complete || !hit.file.exists()) return null
        hit.lastUsed = System.currentTimeMillis()
        return hit.file.absolutePath
    }

    /** Deletes all idle pre-downloaded files (keeps the currently playing one if it came from cache). */
    fun clearCache() {
        worker?.cancel()
        worker = null
        relay?.pausePrefetch()
        val currentUrl = nowPlaying.value?.url
        scope.launch(Dispatchers.IO) {
            for ((k, e) in entries.entries.toList()) {
                if (currentUrl != null && e.file.absolutePath == currentUrl) continue
                entries.remove(k)
                e.file.delete()
                File("${e.file.absolutePath}.part").delete()
            }
            refreshState(activeFile = null)
        }
    }

    private fun currentGate(): PreloadGate = PreloadPolicy.gate(
        settings = settings.value,
        onUnmetered = onUnmetered(),
        nowPlaying = nowPlaying.value,
        playback = player.state.value,
        playlistStatus = playlistStatus.value,
        relayCurrentIncomplete = relay?.isCurrentIncomplete() == true,
    )

    private fun onRoomOrStateChanged(room: RoomSnapshot) {
        val activeRoom = room.room.takeIf { room.status == ConnectionStatus.CONNECTED }
        if (activeRoom != lastRoom) {
            lastRoom = activeRoom
            located.clear()
            pruneUnused(if (activeRoom == null) emptyList() else room.playlist)
        } else {
            pruneUnused(room.playlist)
        }

        val gate = currentGate()
        activeGate = gate
        if (gate != PreloadGate.ALLOWED || room.status != ConnectionStatus.CONNECTED || room.index == null) {
            worker?.cancel()
            worker = null
            relay?.pausePrefetch()
            refreshState(activeFile = null)
            return
        }

        if (worker?.isActive != true) {
            worker = scope.launch(Dispatchers.IO) { runLoop() }
        }
    }

    private suspend fun runLoop() {
        // Short settle delay so rapid track skips or seeks don't thrash connections.
        delay(SETTLE_DELAY_MS)
        while (scope.isActive) {
            val gate = currentGate()
            activeGate = gate
            if (gate != PreloadGate.ALLOWED) {
                relay?.pausePrefetch()
                refreshState(activeFile = null)
                return
            }
            val room = sync.room.value
            val index = room.playlistIndex
            if (room.status != ConnectionStatus.CONNECTED || index == null || room.playlist.size <= 1) {
                refreshState(activeFile = null)
                return
            }
            val upcoming = PreloadPolicy.upcomingIndices(room.playlist.size, index, settings.value.sync.loopPlaylist)
            var didWork = false
            for ((pos, idx) in upcoming.withIndex()) {
                if (currentGate() != PreloadGate.ALLOWED) {
                    relay?.pausePrefetch()
                    refreshState(activeFile = null)
                    return
                }
                val fileName = room.playlist.getOrNull(idx) ?: continue
                if (Filenames.isUrl(fileName)) continue
                val order = pos + 1
                val loc = locateCached(fileName)
                when (loc) {
                    is RoomLocation.Local, is RoomLocation.Missing -> continue
                    is RoomLocation.Relay -> {
                        if (loc.file.media != null) continue // Never full-prefetch growing/live files
                        val frac = relay?.cachedFraction(loc.file) ?: 0f
                        if (frac >= 0.999f) continue
                        didWork = true
                        refreshState(activeFile = fileName)
                        prefetchRelayUntilDoneOrYield(loc, order)
                        if (currentGate() != PreloadGate.ALLOWED) return
                    }
                    is RoomLocation.Server -> {
                        val size = loc.playable.sizeBytes
                        if (size <= 0L || completePath(loc.playable.fileName, size) != null) continue
                        val cap = effectiveCapBytes()
                        if (size > cap) continue
                        didWork = true
                        refreshState(activeFile = fileName)
                        downloadServerFile(loc.playable.url, loc.playable.fileName, size, room.playlist)
                        if (currentGate() != PreloadGate.ALLOWED) return
                    }
                }
            }
            refreshState(activeFile = null)
            if (!didWork) return
            delay(IDLE_POLL_MS)
        }
    }

    private suspend fun prefetchRelayUntilDoneOrYield(loc: RoomLocation.Relay, order: Int) {
        val mgr = relay ?: return
        mgr.prefetch(loc.file, order = order, fullFile = true)
        var lastFrac = mgr.cachedFraction(loc.file) ?: 0f
        var stagnantTicks = 0
        while (scope.isActive) {
            delay(RELAY_POLL_MS)
            if (currentGate() != PreloadGate.ALLOWED) {
                mgr.pausePrefetch()
                return
            }
            val frac = mgr.cachedFraction(loc.file) ?: 0f
            refreshState(activeFile = loc.file.name)
            if (frac >= 0.999f) return
            if (frac > lastFrac + 0.0001f) {
                lastFrac = frac
                stagnantTicks = 0
            } else if (++stagnantTicks >= MAX_STAGNANT_TICKS) {
                return
            }
        }
    }

    private suspend fun downloadServerFile(url: String, fileName: String, expectedSize: Long, playlist: List<String>) {
        val key = entryKey(fileName, expectedSize)
        val ext = fileName.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "bin"
        val finalFile = File(preloadDir, "$key.$ext")
        val partFile = File(preloadDir, "$key.$ext.part")

        if (finalFile.exists() && finalFile.length() == expectedSize) {
            entries[key] = PreloadedEntry(key, fileName, expectedSize, finalFile, complete = true)
            refreshState(activeFile = null)
            return
        }

        trimToMakeRoom(expectedSize, playlist)
        var offset = if (partFile.exists()) partFile.length().coerceAtMost(expectedSize) else 0L
        val reqBuilder = Request.Builder().url(url)
        if (offset > 0L) reqBuilder.header("Range", "bytes=$offset-")

        try {
            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    Logger.w(TAG, "Preload HTTP ${resp.code} for $fileName")
                    return
                }
                if (resp.code == 200) offset = 0L
                val body = resp.body ?: return
                body.byteStream().use { input ->
                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(CHUNK_BYTES)
                        var written = offset
                        while (scope.isActive) {
                            if (currentGate() != PreloadGate.ALLOWED) {
                                Logger.i(TAG, "Yielded preload of $fileName at $written/$expectedSize B for active playback")
                                return
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            if (written % (4L shl 20) < CHUNK_BYTES) {
                                refreshState(activeFile = fileName)
                            }
                        }
                        if (written >= expectedSize) {
                            raf.setLength(expectedSize)
                        }
                    }
                }
            }
            if (partFile.exists() && partFile.length() == expectedSize) {
                if (finalFile.exists()) finalFile.delete()
                if (partFile.renameTo(finalFile)) {
                    entries[key] = PreloadedEntry(key, fileName, expectedSize, finalFile, complete = true)
                    Logger.i(TAG, "Pre-downloaded $fileName ($expectedSize B)")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Preload failed for $fileName: ${e.message}")
        } finally {
            refreshState(activeFile = null)
        }
    }

    private suspend fun locateCached(fileName: String): RoomLocation {
        val existing = located[fileName]
        if (existing != null && existing !is RoomLocation.Missing) return existing
        val found = try {
            locator.locateForRoom(fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RoomLocation.Missing(e.message ?: "Lookup failed")
        }
        if (found !is RoomLocation.Missing) located[fileName] = found
        return found
    }

    private fun effectiveCapBytes(): Long {
        val settingBytes = settings.value.preDownloadCacheGb.coerceIn(1, 32).toLong() shl 30
        val usable = (preloadDir.usableSpace / 2).coerceAtLeast(256L shl 20)
        return minOf(settingBytes, usable)
    }

    private fun trimToMakeRoom(incomingBytes: Long, playlist: List<String>) {
        val cap = effectiveCapBytes()
        val currentUrl = nowPlaying.value?.url
        val relayBytes = relay?.cachedBytes() ?: 0L
        var total = relayBytes + preloadBytesOnDisk()
        if (total + incomingBytes <= cap) return
        val inPlaylist = playlist.map { it.lowercase() }.toSet()
        // Evict items no longer in the playlist first, then oldest completed entries.
        val candidates = entries.values
            .filter { currentUrl == null || it.file.absolutePath != currentUrl }
            .sortedWith(compareBy<PreloadedEntry>({ it.fileName.lowercase() in inPlaylist }, { it.lastUsed }))
        for (entry in candidates) {
            if (total + incomingBytes <= cap) break
            val sz = entry.file.length()
            if (entry.file.delete()) {
                entries.remove(entry.key)
                total = (total - sz).coerceAtLeast(0L)
            }
        }
    }

    private fun pruneUnused(playlist: List<String>) {
        val currentUrl = nowPlaying.value?.url
        val keepNames = playlist.map { it.lowercase() }.toSet()
        scope.launch(Dispatchers.IO) {
            for ((key, entry) in entries.entries.toList()) {
                if (currentUrl != null && entry.file.absolutePath == currentUrl) continue
                if (entry.fileName.lowercase() !in keepNames) {
                    entries.remove(key)
                    entry.file.delete()
                    File("${entry.file.absolutePath}.part").delete()
                }
            }
            refreshState(activeFile = _state.value.activeFile)
        }
    }

    private fun preloadBytesOnDisk(): Long =
        preloadDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    private fun refreshState(activeFile: String?) {
        val gate = activeGate
        val bytes = preloadBytesOnDisk() + (relay?.cachedBytes() ?: 0L)
        _state.value = PreloadState(
            activeFile = activeFile,
            cachedCount = entries.values.count { it.complete },
            cachedBytes = bytes,
            pausedForPlayback = gate == PreloadGate.YIELD_TO_PLAYBACK,
            blockedByNetwork = gate == PreloadGate.DISABLED_BY_SETTING,
        )
    }

    private fun entryKey(fileName: String, sizeBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${fileName.lowercase()}|$sizeBytes".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "PlaylistPreloader"
        private const val SETTLE_DELAY_MS = 1_500L
        private const val RELAY_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val MAX_STAGNANT_TICKS = 30
        private const val CHUNK_BYTES = 256 * 1024
    }
}
