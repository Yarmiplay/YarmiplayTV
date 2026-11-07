package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.UpdateFailedException
import com.yarmiplaytv.update.UpdateFailure
import com.yarmiplaytv.update.explainUpdateFailure
import com.yarmiplaytv.update.redactUpdateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FailedInstallSignalTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test
    fun parseLegacyVersionOnly() {
        val s = parseFailedInstall("2.8.8")
        assertEquals("2.8.8", s?.version)
        assertNull(s?.exitCode)
        assertNull(s?.detail)
    }

    @Test
    fun parseExitAndDetail() {
        val s = parseFailedInstall("2.8.9|exit=1|Couldn't replace files: Access is denied")!!
        assertEquals("2.8.9", s.version)
        assertEquals(1, s.exitCode)
        assertEquals("Couldn't replace files: Access is denied", s.detail)
    }

    @Test
    fun formatRoundTrip() {
        val raw = formatFailedInstall("2.8.9", 1603, "msiexec failed")
        assertEquals("2.8.9|stage=install|exit=1603|msiexec failed", raw)
        val s = parseFailedInstall(raw)!!
        assertEquals(1603, s.exitCode)
        assertEquals("msiexec failed", s.detail)
        assertEquals("install", s.stage)
    }

    @Test
    fun failureFileSurvivesForTheNextLaunchAndKeepsAPathWithSpaces() {
        val file = File(tmp.root, "update-failure.txt")
        val detail = "Couldn't replace files in C:\\Users\\Dor Farhi\\AppData\\Local\\YarmiplayTV: Access is denied"
        writeUpdateFailure(UpdateFailure("2.9.8", "replace", 5, detail), file)
        val read = readUpdateFailureFile(file)!!
        assertEquals("replace", read.stage)
        assertEquals(5, read.exitCode)
        assertEquals(detail, read.detail)
        val shown = loadPendingUpdateFailure(env = "2.9.8", failureFile = file, statusFile = File(tmp.root, "missing-status.txt"))
        assertTrue(shown!!.contains("stage=replace"))
        assertTrue(shown.contains("Dor Farhi"))
        val message = explainUpdateFailure("replace", 5, read.detail)
        assertTrue(message.contains("Couldn't replace the app's files"))
        assertTrue(message.contains("Dor Farhi"))
        assertTrue(message.contains("Access is denied"))
        clearPendingUpdateFailure(file)
        assertFalse(file.exists())
    }

    @Test
    fun statusLineFillsAVersionOnlySignalWithoutInventingACause() {
        val status = File(tmp.root, "update-status.txt")
        status.writeText("Couldn't create the install folder (C:\\Users\\Dor Farhi\\AppData\\Local\\YarmiplayTV)\n")
        val merged = loadPendingUpdateFailure("2.9.8", failureFile = File(tmp.root, "nope.txt"), statusFile = status)
        assertTrue(merged!!.contains("Couldn't create the install folder"))
        assertTrue(merged.contains("Dor Farhi"))
        val bare = explainUpdateFailure("install", 1603, null)
        assertEquals("The installer didn't finish (exit code 1603).", bare)
        assertFalse(bare.contains("Access"))
        assertFalse(redactUpdateText("https://example.com/a.msi?token=supersecret").contains("supersecret"))
    }

    @Test
    fun helperThatCannotStartIsRecordedWithoutInventingACause() {
        val failure = failureFromHelperStart(
            "2.9.8",
            UpdateFailedException("launch", "Couldn't start PowerShell: token=supersecret"),
        )
        assertEquals("launch", failure.stage)
        assertEquals("2.9.8", failure.version)
        assertFalse(failure.detail.orEmpty().contains("supersecret"))
        assertTrue(failure.detail.orEmpty().contains("token=(redacted)"))
        val generic = failureFromHelperStart("2.9.8", IllegalStateException("error 5"))
        assertEquals("launch", generic.stage)
        assertEquals("error 5", generic.detail)
        val file = File(tmp.root, "update-failure.txt")
        writeUpdateFailure(generic, file)
        val loaded = loadPendingUpdateFailure(null, failureFile = file, statusFile = File(tmp.root, "no-status.txt"))
        assertTrue(loaded!!.contains("stage=launch"))
        assertTrue(loaded.contains("error 5"))
    }

    @Test
    fun legacyExitFormStillParses() {
        val s = parseFailedInstall("2.8.9|exit=1603|msiexec failed")!!
        assertEquals("2.8.9", s.version)
        assertEquals(1603, s.exitCode)
        assertEquals("msiexec failed", s.detail)
    }
}
