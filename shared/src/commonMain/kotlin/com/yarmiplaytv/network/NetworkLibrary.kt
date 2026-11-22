package com.yarmiplaytv.network

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.media.ExternalSubtitle
import com.yarmiplaytv.media.sidecarLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** The playback address for one network file, and subtitle files already copied next to each other. */
data class OpenedNetworkFile(
    val playbackUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sidecars: List<ExternalSubtitle>,
)

/**
 * Saved FTP and SMB places, and the one file currently being handed to the player.
 * Listing and reading happen off the UI thread. Passwords are not written to the log.
 */
class NetworkLibrary(
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    cacheDir: File,
    private val backend: NetworkBackend,
) {
    private val sidecarDir = File(cacheDir, "network-subs")
    private val locationsMutex = Mutex()
    private val _locations = MutableStateFlow<List<NetworkLocation>>(emptyList())
    val locations: StateFlow<List<NetworkLocation>> = _locations.asStateFlow()

    init {
        scope.launch {
            _locations.value = runCatching { settingsStore.loadNetworkLocations() }.getOrElse {
                Logger.w(TAG, "Couldn't read saved network places", it)
                emptyList()
            }
        }
    }

    fun location(id: String): NetworkLocation? = _locations.value.firstOrNull { it.id == id }

    suspend fun add(location: NetworkLocation) = locationsMutex.withLock {
        val next = _locations.value.filterNot { it.id == location.id } + location
        settingsStore.saveNetworkLocations(next)
        _locations.value = next
    }

    suspend fun remove(id: String) = locationsMutex.withLock {
        val next = _locations.value.filterNot { it.id == id }
        settingsStore.saveNetworkLocations(next)
        _locations.value = next
    }

    suspend fun list(id: String, directory: String): List<NetworkEntry> = withContext(Dispatchers.IO) {
        val place = location(id) ?: throw IllegalArgumentException("That network place is no longer saved")
        val path = NetworkLocations.normalizeRelative(directory) ?: throw IllegalArgumentException("That folder isn't inside the share")
        backend.list(place, path).filter { NetworkLocations.childPath(path, it.name) != null }
    }

    /**
     * Starts serving [uri] on localhost and copies matching subtitle files into the cache.
     * The returned address has no username or password in it.
     */
    suspend fun openForPlayback(uri: String): OpenedNetworkFile = withContext(Dispatchers.IO) {
        val (id, path) = NetworkUris.parse(uri) ?: throw IllegalArgumentException("Not a network file")
        val name = path.substringAfterLast('/').ifEmpty { throw IllegalArgumentException("Choose a file") }
        val place = location(id) ?: throw IllegalArgumentException("That network place is no longer saved")
        Logger.i(TAG, "Opening ${place.protocol} ${place.host} ${place.share} $path")
        val remote = backend.open(place, path)
        val playbackUrl = backend.publish(name, remote.size, remote.reader) { remote.close() }
        val sidecars = writeSidecars(remote.sidecars)
        OpenedNetworkFile(playbackUrl, name, remote.size, sidecars)
    }

    /** Closes the FTP or SMB file the player was reading, once playback has moved on. */
    fun releasePlayback() = backend.releasePublished()

    private fun writeSidecars(sidecars: List<NamedBytes>): List<ExternalSubtitle> {
        sidecarDir.deleteRecursively()
        if (sidecars.isEmpty()) return emptyList()
        val directory = File(sidecarDir, UUID.randomUUID().toString())
        if (!directory.mkdirs()) return emptyList()
        return sidecars.mapNotNull { named ->
            val safe = named.name.substringAfterLast('/').substringAfterLast('\\')
            if (safe.isBlank() || safe == "." || safe == ".." || safe.any { it == '\u0000' }) return@mapNotNull null
            val target = File(directory, safe)
            if (target.parentFile != directory) return@mapNotNull null
            runCatching {
                target.writeBytes(named.bytes)
                ExternalSubtitle(target.toURI().toString(), safe.substringBeforeLast('.'), sidecarLanguage(safe))
            }.onFailure { Logger.w(TAG, "Couldn't keep subtitle $safe", it) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "NetworkLibrary"
    }
}
