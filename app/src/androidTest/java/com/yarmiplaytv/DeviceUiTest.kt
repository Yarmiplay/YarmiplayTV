package com.yarmiplaytv

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phoneOrTabletIsNotDetectedAsTv() {
        val kind = DeviceUi.kind(context)
        assertNotEquals(DeviceKind.TV, kind)
        val expected = if (context.resources.configuration.smallestScreenWidthDp >= 600) DeviceKind.TABLET else DeviceKind.PHONE
        assertEquals(expected, kind)
    }

    @Test
    fun debugExtraOverridesDetection() {
        assertEquals(DeviceKind.TV, DeviceUi.kind(context, Intent().putExtra(DeviceUi.EXTRA_UI, "tv")))
        assertNotEquals(DeviceKind.TV, DeviceUi.kind(context, Intent().putExtra(DeviceUi.EXTRA_UI, "mobile")))
    }

    @Test
    fun defaultNamesFitSyncplayLimit() {
        val prefixes = mapOf(DeviceKind.TV to "TV", DeviceKind.PHONE to "Phone", DeviceKind.TABLET to "Tablet")
        for ((kind, prefix) in prefixes) {
            val name = DeviceUi.defaultUserName(kind)
            assertTrue(name, name.length <= 16)
            assertTrue(name, name.startsWith(prefix))
        }
    }

    @Test
    fun activityPicksMobileUiByDefaultAndTvUiWithExtra() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotEquals(DeviceKind.TV, it.deviceKind) }
        }
        val tvIntent = Intent(context, MainActivity::class.java).putExtra(DeviceUi.EXTRA_UI, "tv")
        ActivityScenario.launch<MainActivity>(tvIntent).use { scenario ->
            scenario.onActivity { assertEquals(DeviceKind.TV, it.deviceKind) }
        }
    }
}
