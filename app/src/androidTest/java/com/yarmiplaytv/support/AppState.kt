package com.yarmiplaytv.support

import android.net.Uri
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.TestSupport
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalFolder
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.media.jellyfin.DiscoveredServer
import com.yarmiplaytv.media.jellyfin.JellyfinDiscovery
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Puts the app's process-wide state into fixed, known values for screenshots and restores the user's
 * own settings (Syncplay profile, Jellyfin sign-in, media folders) afterwards. Nothing here changes
 * app code: private state is reached by reflection, so the hooks stay out of the shipped app.
 */
class AppState {
    private val container: AppContainer get() = TestSupport.container
    private lateinit var saved: AppSettings
    private var savedRoomPlaylists = emptyMap<String, List<String>>()
    private var wasConnected = false

    fun snapshot() {
        saved = container.settings.value
        savedRoomPlaylists = runBlocking { container.settingsStore.roomPlaylists() }
        wasConnected = container.sync.isActive
    }

    fun restore() {
        JellyfinDiscovery.scanner = null
        onMain { container.playlist.stop() }
        container.sync.disconnect()
        runBlocking {
            val store = container.settingsStore
            store.saveSyncplay(saved.syncplay, saved.autoConnect)
            store.saveSync(saved.sync, saved.autoReadyOnLoad)
            store.savePlayback(saved.playback)
            store.saveLastJellyfinUrl(saved.lastJellyfinUrl)
            store.saveLocalFolders(saved.localFolders)
            store.saveAutosavePlaylists(saved.autosavePlaylists)
            store.replaceRoomPlaylists(savedRoomPlaylists)
        }
        onMain { container.setJellyfinSession(saved.jellyfin) }
        setLocalLibrary(saved.localFolders.map { LocalFolder(it, folderName(it)) }, emptyList())
        runBlocking { if (saved.localFolders.isNotEmpty()) container.local.refresh() }
        clearFeed()
        if (wasConnected && saved.syncplay.isComplete) container.sync.connect(saved.syncplay.toConfig())
    }

    /** No room, nothing playing, no Jellyfin, no media folders, no saved room playlists, default settings and a fixed profile. */
    fun baseline() {
        // The host's real servers would otherwise show up in "Found on your network".
        JellyfinDiscovery.scanner = { listOf(DiscoveredServer(address = "http://192.168.1.20:8096", id = "fake-server", name = "Living Room")) }
        onMain { container.playlist.stop() }
        container.sync.disconnect()
        runBlocking {
            val store = container.settingsStore
            store.saveSyncplay(PROFILE, autoConnect = false)
            store.saveSync(SyncSettings(), autoReady = true)
            store.savePlayback(PlaybackPrefs())
            store.saveLastJellyfinUrl(LAST_JELLYFIN_URL)
            store.saveAutosavePlaylists(true)
            store.replaceRoomPlaylists(emptyMap())
        }
        onMain { container.setJellyfinSession(null) }
        runBlocking { container.settingsStore.saveLastJellyfinUrl(LAST_JELLYFIN_URL) }
        setLocalLibrary(emptyList(), emptyList())
        waitUntil(5_000) {
            val s = container.settings.value
            s.syncplay == PROFILE && s.jellyfin == null && container.mediaSource.value == null && s.lastJellyfinUrl == LAST_JELLYFIN_URL
        }
        clearFeed()
    }

    fun signInJellyfin(fake: FakeJellyfin) {
        onMain { container.setJellyfinSession(fake.session) }
        waitUntil(5_000) { container.mediaSource.value != null }
    }

    fun joinRoom(server: FakeSyncplayServer) {
        container.sync.connect(PROFILE.copy(host = "127.0.0.1", port = server.port).toConfig())
        waitUntil(10_000) {
            container.sync.room.value.status == ConnectionStatus.CONNECTED && PROFILE.username in server.connectedUsers &&
                container.sync.feed.value.any { it.text.startsWith("Joined room") }
        }
    }

    /** Shows two media folders with a few videos, as if the user had picked them. */
    fun fakeMediaFolders() {
        val movies = LocalFolder("content://com.android.externalstorage.documents/tree/primary%3AMovies", "Movies")
        val shows = LocalFolder("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FShows", "Shows")
        fun file(name: String, size: Long, folder: LocalFolder) =
            LocalFile(name, size, "${folder.uri}/document/primary%3A${Uri.encode(name)}", folder.name)
        setLocalLibrary(
            listOf(movies, shows),
            listOf(
                file("North Wind (2022).mkv", 2_254_857_830, movies),
                file("Orbit Station S01E01.mkv", 734_003_200, shows),
                file("Orbit Station S01E02.mkv", 730_857_472, shows),
                file("Paper Boats (2018).mp4", 1_610_612_736, movies),
            ),
        )
    }

    fun clearFeed() {
        @Suppress("UNCHECKED_CAST")
        val feed = SyncController::class.java.getDeclaredField("_feed").apply { isAccessible = true }.get(container.sync) as MutableStateFlow<List<FeedMessage>>
        feed.value = emptyList()
    }

    private fun setLocalLibrary(folders: List<LocalFolder>, files: List<LocalFile>) {
        fun <T> field(name: String): MutableStateFlow<T> {
            @Suppress("UNCHECKED_CAST")
            return LocalLibrary::class.java.getDeclaredField(name).apply { isAccessible = true }.get(container.local) as MutableStateFlow<T>
        }
        field<List<LocalFolder>>("_folders").value = folders
        field<List<LocalFile>>("_files").value = files
    }

    private fun folderName(uri: String) = Uri.decode(uri.substringAfterLast("%3A", uri.substringAfterLast('/'))).substringAfterLast('/')

    companion object {
        val PROFILE = SyncplayProfile(host = "syncplay.example.org", port = 8999, username = "Sam", room = FakeSyncplayServer.ROOM, password = "", useTls = false)
        const val LAST_JELLYFIN_URL = "http://192.168.1.20:8096"

        fun onMain(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }

        fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition()) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out after $timeoutMs ms")
                Thread.sleep(50)
            }
        }
    }
}
