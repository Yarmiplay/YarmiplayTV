package com.yarmiplaytv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `config directory follows each platform's convention`() {
        val env = mapOf("APPDATA" to "C:\\Users\\sam\\AppData\\Roaming")
        assertEquals(
            File("C:\\Users\\sam\\AppData\\Roaming", "YarmiplayTV"),
            DesktopPaths.configDir("Windows 11", env::get, "C:\\Users\\sam"),
        )
        assertEquals(
            File("/Users/sam", "Library/Application Support/YarmiplayTV"),
            DesktopPaths.configDir("Mac OS X", { null }, "/Users/sam"),
        )
        assertEquals(File("/home/sam/.config", "yarmiplaytv"), DesktopPaths.configDir("Linux", { null }, "/home/sam"))
        assertEquals(
            File("/tmp/xdg", "yarmiplaytv"),
            DesktopPaths.configDir("Linux", mapOf("XDG_CONFIG_HOME" to "/tmp/xdg")::get, "/home/sam"),
        )
    }

    @Test
    fun `portable app keeps its settings next to its launcher`() {
        val launcher = File(tmp.root, "YarmiplayTV/YarmiplayTV.exe").path
        assertEquals(File(tmp.root, "YarmiplayTV/data"), DesktopPaths.portableDir("true", launcher))
        assertNull("installed app", DesktopPaths.portableDir(null, launcher))
        assertNull("run from Gradle", DesktopPaths.portableDir("true", null))
    }

    @Test
    fun `settings survive a restart`() = runBlocking {
        val dir = tmp.newFolder("YarmiplayTV")
        val profile = SyncplayProfile(host = "syncplay.example.org", port = 8999, username = "Sam", room = "movie-night", useTls = false)
        val den = PlexSession("https://10-0-0-5.abc.plex.direct:32400", "Benny", "mid", "sam", "srvtok", "acct")
        val office = PlexSession("https://10-0-0-9.def.plex.direct:32400", "Office", "mid2", "sam", "srvtok2", "acct")
        val living = JellyfinSession("http://192.168.1.20:8096", "Living Room", "srv1", "u1", "sam", "tok1")
        val firstRun = CoroutineScope(Dispatchers.IO + SupervisorJob())
        desktopSettingsStore(dir, firstRun).apply {
            saveSyncplay(profile, autoConnect = true)
            saveLocalFolders(listOf("file:///C:/Videos/", "file:///D:/Shows/"))
            saveServers(listOf(living), listOf(den, office))
            saveServerPrefs(preferredServer = PlexSource.keyOf(office), reportPlayback = false)
            saveDownloadDirectory("file:///D:/Downloads/")
            saveScreenshotDirectory("file:///D:/Screenshots/")
        }
        firstRun.coroutineContext.job.cancelAndJoin()

        val secondRun = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = desktopSettingsStore(dir, secondRun).current()
        secondRun.cancel()
        assertEquals(profile, settings.syncplay)
        assertEquals(true, settings.autoConnect)
        assertEquals(listOf("file:///C:/Videos/", "file:///D:/Shows/"), settings.localFolders)
        assertEquals(listOf(living), settings.jellyfinServers)
        assertEquals(listOf(den, office), settings.plexServers)
        assertEquals("plex:mid2", settings.preferredServer)
        assertEquals(false, settings.reportPlayback)
        assertEquals("file:///D:/Downloads/", settings.downloadDirectory)
        assertEquals("file:///D:/Screenshots/", settings.screenshotDirectory)
    }

    @Test
    fun `single-server settings from older versions become one-item lists`() = runBlocking {
        val dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { tmp.newFolder("legacy").resolve("settings.preferences_pb").toOkioPath() })
        dataStore.edit {
            it[stringPreferencesKey("jf_url")] = "http://192.168.1.20:8096"
            it[stringPreferencesKey("jf_server_name")] = "Living Room"
            it[stringPreferencesKey("jf_server_id")] = "srv1"
            it[stringPreferencesKey("jf_user_id")] = "u1"
            it[stringPreferencesKey("jf_user_name")] = "sam"
            it[stringPreferencesKey("jf_token")] = "tok1"
            it[stringPreferencesKey("px_url")] = "https://10-0-0-5.abc.plex.direct:32400"
            it[stringPreferencesKey("px_server_name")] = "Benny"
            it[stringPreferencesKey("px_machine_id")] = "mid"
            it[stringPreferencesKey("px_user_name")] = "sam"
            it[stringPreferencesKey("px_server_token")] = "srvtok"
            it[stringPreferencesKey("px_account_token")] = "acct"
            it[stringPreferencesKey("preferred_server")] = "plex"
        }
        val store = SettingsStore(dataStore)
        val living = JellyfinSession("http://192.168.1.20:8096", "Living Room", "srv1", "u1", "sam", "tok1")
        val benny = PlexSession("https://10-0-0-5.abc.plex.direct:32400", "Benny", "mid", "sam", "srvtok", "acct")

        val migrated = store.current()
        assertEquals(listOf(living), migrated.jellyfinServers)
        assertEquals(listOf(benny), migrated.plexServers)
        assertEquals("plex:mid", migrated.preferredServer)

        val second = living.copy(serverName = "Attic", serverId = "srv2", serverUrl = "http://192.168.1.30:8096")
        store.saveServers(migrated.jellyfinServers + second, migrated.plexServers)
        val saved = dataStore.data.first()
        assertNull(saved[stringPreferencesKey("jf_token")])
        assertNull(saved[stringPreferencesKey("px_server_token")])
        assertEquals("plex:mid", saved[stringPreferencesKey("preferred_server")])
        assertEquals(listOf(living, second), store.current().jellyfinServers)

        store.saveServers(listOf(second), emptyList())
        assertEquals(listOf(second), store.current().jellyfinServers)
        assertTrue(store.current().plexServers.isEmpty())
    }

    @Test
    fun `settings from before the rename move to the new folder once`() {
        assertEquals(
            File("/home/sam/.config", "syncplaytv"),
            DesktopPaths.legacyConfigDir("Linux", { null }, "/home/sam"),
        )
        val legacy = tmp.newFolder("SyncplayTV").apply { resolve("settings.preferences_pb").writeText("old") }
        val target = File(tmp.root, "YarmiplayTV")
        assertEquals(target, DesktopPaths.migrateLegacyConfig(target, legacy))
        assertEquals("old", target.resolve("settings.preferences_pb").readText())
        assertFalse(legacy.exists())

        val stale = tmp.newFolder("SyncplayTV").apply { resolve("settings.preferences_pb").writeText("stale") }
        DesktopPaths.migrateLegacyConfig(target, stale)
        assertEquals("old", target.resolve("settings.preferences_pb").readText())
        assertTrue(stale.exists())
    }

    @Test
    fun `device id is generated once`() = runBlocking {
        val store = desktopSettingsStore(tmp.newFolder("ids"))
        assertEquals(store.deviceId(), store.deviceId())
    }

    @Test
    fun `defaults for TV vs phone and tablet`() = runBlocking {
        val tvFile = tmp.newFolder("tv").resolve("settings.preferences_pb")
        val tvStore = SettingsStore(PreferenceDataStoreFactory.createWithPath(produceFile = { tvFile.toOkioPath() }), isTv = true)

        val phoneFile = tmp.newFolder("phone").resolve("settings.preferences_pb")
        val phoneStore = SettingsStore(PreferenceDataStoreFactory.createWithPath(produceFile = { phoneFile.toOkioPath() }), isTv = false)

        val tvSettings = tvStore.current()
        assertFalse("TV PiP should be off by default", tvSettings.playback.pictureInPicture)
        assertFalse("TV stay connected should be off by default", tvSettings.playback.stayConnectedMinimized)

        val phoneSettings = phoneStore.current()
        assertTrue("Phone/tablet PiP should be on by default", phoneSettings.playback.pictureInPicture)
        assertTrue("Phone/tablet stay connected should be on by default", phoneSettings.playback.stayConnectedMinimized)

        // Overrides persist
        tvStore.savePlayback(tvSettings.playback.copy(pictureInPicture = true, stayConnectedMinimized = true))
        assertTrue("TV PiP override persisted", tvStore.current().playback.pictureInPicture)
        assertTrue("TV stay connected override persisted", tvStore.current().playback.stayConnectedMinimized)
    }

    @Test
    fun `persisted seek positions can be retrieved with allSeekPositions`() = runBlocking {
        val folder = tmp.newFolder("seek_positions")
        val store = desktopSettingsStore(folder)

        store.saveSeekPosition("ep1.mkv", 123.4)
        store.saveSeekPosition("ep2.mkv", 456.7)

        val positions = store.allSeekPositions()
        assertEquals(123.4, positions["ep1.mkv"] ?: 0.0, 0.01)
        assertEquals(456.7, positions["ep2.mkv"] ?: 0.0, 0.01)
    }

    @Test
    fun `tv player scale default and persistence`() = runBlocking {
        val folder = tmp.newFolder("player_scale")
        val store = desktopSettingsStore(folder)

        assertEquals(0.85f, store.current().playback.tvPlayerScale, 0.001f)

        store.saveTvPlayerScale(0.75f)
        assertEquals(0.75f, store.current().playback.tvPlayerScale, 0.001f)

        val updated = store.current().playback.copy(tvPlayerScale = 1.0f)
        store.savePlayback(updated)
        assertEquals(1.0f, store.current().playback.tvPlayerScale, 0.001f)
    }
}
