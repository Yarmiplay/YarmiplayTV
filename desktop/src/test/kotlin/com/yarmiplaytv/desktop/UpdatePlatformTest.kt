package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePlatformTest {
    @Test
    fun namesTheDownloadPagePackage() {
        assertEquals("windows", updatePlatform("Windows 11", store = null))
        assertEquals("macos", updatePlatform("Mac OS X", store = null))
        assertEquals("linux", updatePlatform("Linux", store = null))
    }

    @Test
    fun storeBuildsDontLookForUpdates() {
        assertNull(updatePlatform("Windows 11", store = "msstore"))
    }
}
