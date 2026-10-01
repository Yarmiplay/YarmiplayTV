package com.syncplaytv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlatformTest {
    @Test
    fun defaultNamesUseTheHostNameAndFitSyncplayLimit() {
        for (kind in DeviceKind.entries) {
            val name = defaultSyncplayName(kind)
            assertTrue(name, name.length <= 16)
            assertTrue(name, name.matches(Regex("[A-Za-z]+(-[A-Za-z0-9]+)?")))
        }
        assertFalse(DevicePlatform.isEmulator)
    }
}
