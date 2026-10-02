package com.yarmiplaytv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
    fun `settings survive a restart`() = runBlocking {
        val dir = tmp.newFolder("YarmiplayTV")
        val profile = SyncplayProfile(host = "syncplay.example.org", port = 8999, username = "Sam", room = "movie-night", useTls = false)
        val firstRun = CoroutineScope(Dispatchers.IO + SupervisorJob())
        desktopSettingsStore(dir, firstRun).apply {
            saveSyncplay(profile, autoConnect = true)
            saveLocalFolders(listOf("file:///C:/Videos/", "file:///D:/Shows/"))
        }
        firstRun.coroutineContext.job.cancelAndJoin()

        val secondRun = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = desktopSettingsStore(dir, secondRun).current()
        secondRun.cancel()
        assertEquals(profile, settings.syncplay)
        assertEquals(true, settings.autoConnect)
        assertEquals(listOf("file:///C:/Videos/", "file:///D:/Shows/"), settings.localFolders)
        assertNull(settings.jellyfin)
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
}
