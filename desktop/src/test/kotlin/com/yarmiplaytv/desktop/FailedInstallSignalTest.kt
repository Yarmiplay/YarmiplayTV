package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailedInstallSignalTest {
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
        assertEquals("2.8.9|exit=1603|msiexec failed", raw)
        val s = parseFailedInstall(raw)!!
        assertEquals(1603, s.exitCode)
        assertEquals("msiexec failed", s.detail)
    }
}
