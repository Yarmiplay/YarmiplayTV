package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePlatformTest {
    private val noEnv: (String) -> String? = { null }

    @Test
    fun namesTheDownloadPagePackage() {
        assertEquals("windows", updatePlatform("Windows 11", store = null, env = noEnv))
        assertEquals("macos", updatePlatform("Mac OS X", store = null, env = noEnv))
        assertEquals("linux", updatePlatform("Linux", store = null, env = noEnv))
    }

    @Test
    fun storeBuildsDontLookForUpdates() {
        assertNull(updatePlatform("Windows 11", store = "msstore", env = noEnv))
        assertNull(updatePlatform("Linux", store = "aur", env = noEnv))
        assertNull("Flatpak", updatePlatform("Linux", store = null, env = mapOf("FLATPAK_ID" to "com.yarmiplay.TV")::get))
        assertNull("Snap", updatePlatform("Linux", store = null, env = mapOf("SNAP" to "/snap/yarmiplaytv/12")::get))
    }

    @Test
    fun emptyVariablesDontCount() {
        assertEquals("linux", updatePlatform("Linux", store = "", env = mapOf("FLATPAK_ID" to "", "SNAP" to "")::get))
    }
}
