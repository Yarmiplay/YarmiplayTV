package com.yarmiplaytv.update

import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.desktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReleaseNotesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the notes are the metainfo's releases, newest first`() {
        val metainfo = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "packaging/linux/com.yarmiplay.TV.metainfo.xml") }.first { it.isFile }.readText()
        val top = Regex("""<release version="([^"]+)" date="([^"]+)"""").find(metainfo)!!.groupValues
        val newest = ReleaseNotes.all.first()
        assertEquals(top[1] to top[2], newest.version to newest.date)
        assertEquals(Regex("<release ").findAll(metainfo).count(), ReleaseNotes.all.size)
        val versions = ReleaseNotes.all.map { AppVersions.parse(it.version)!! }
        versions.zipWithNext().forEach { (a, b) -> assertTrue("$a before $b", AppVersions.isNewer(a.joinToString("."), b.joinToString("."))) }
        assertTrue(ReleaseNotes.all.all { it.paragraphs.isNotEmpty() && it.paragraphs.none(String::isBlank) })
    }

    @Test
    fun `notes by version`() {
        assertEquals("1.8.0", ReleaseNotes.of("1.8")?.version)
        assertEquals("1.8.0", ReleaseNotes.of("v1.8.0")?.version)
        assertNull(ReleaseNotes.of("0.0.1"))
        assertNull(ReleaseNotes.of("dev"))
    }

    @Test
    fun `the card shows once after an update, never on a fresh install or a downgrade`() {
        val note = ReleaseNote("2.0.0", "2030-01-01", listOf("New"))
        val notes = { v: String -> note.takeIf { v == "2.0.0" } }
        assertEquals("2.0.0", WhatsNew.cardVersion("1.9.0", "2.0.0", notes))
        assertEquals("used before this setting", "2.0.0", WhatsNew.cardVersion(SettingsStore.USED_BEFORE_WHATS_NEW, "2.0.0", notes))
        assertNull("seen", WhatsNew.cardVersion("2.0.0", "2.0.0", notes))
        assertNull("fresh install", WhatsNew.cardVersion("", "2.0.0", notes))
        assertNull("downgrade", WhatsNew.cardVersion("2.1.0", "2.0.0", notes))
        assertNull("no notes", WhatsNew.cardVersion("1.9.0", "2.0.1", notes))
        assertNull("development build", WhatsNew.cardVersion("1.9.0", "dev", notes))
    }

    @Test
    fun `a fresh install remembers its version, an app used before shows the card`() = runBlocking {
        val fresh = desktopSettingsStore(tmp.newFolder("fresh"), scope)
        assertEquals("", fresh.current().lastSeenVersion)
        WhatsNew.onLaunch(fresh, "1.8.5")
        assertEquals("1.8.5", fresh.current().lastSeenVersion)

        val used = desktopSettingsStore(tmp.newFolder("used"), scope)
        used.saveShowRoomChat(false)
        assertEquals(SettingsStore.USED_BEFORE_WHATS_NEW, used.current().lastSeenVersion)
        WhatsNew.onLaunch(used, "1.8.5")
        assertEquals(SettingsStore.USED_BEFORE_WHATS_NEW, used.current().lastSeenVersion)
        assertNotNull(WhatsNew.cardVersion(used.current().lastSeenVersion, ReleaseNotes.all.first().version))
    }
}
