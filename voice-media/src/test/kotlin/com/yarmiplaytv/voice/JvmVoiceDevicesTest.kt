package com.yarmiplaytv.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for desktop mic/speaker enumeration (no server required). */
class JvmVoiceDevicesTest {
    @Test
    fun `lists system default and reports platform devices`() {
        val inputs = JvmVoiceDevices.listInputs()
        val outputs = JvmVoiceDevices.listOutputs()
        println("PLATFORM_INPUTS=" + inputs.joinToString(" | ") { it.label })
        println("PLATFORM_OUTPUTS=" + outputs.joinToString(" | ") { it.label })
        assertTrue(inputs.first().id.isEmpty())
        assertTrue(outputs.first().id.isEmpty())
        assertTrue(JvmVoiceDevices.isAvailable(null, target = true))
        assertTrue(JvmVoiceDevices.isAvailable("", target = false))
        assertFalse(JvmVoiceDevices.isAvailable("__no_such_device__", target = true))
    }
}
