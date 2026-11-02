package com.yarmiplaytv.desktop

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Join/leave/mic cues must be on the desktop classpath. They used to live only under
 * shared composeResources, which this module never packaged — playSound then no-oped.
 */
class DesktopVoiceSoundsTest {
    @Test
    fun voiceCueWavsAreOnTheClasspath() {
        val names = listOf("voice_join.wav", "voice_leave.wav", "voice_mic_on.wav", "voice_mic_off.wav")
        for (name in names) {
            val stream = DesktopVoiceEngine::class.java.getResourceAsStream("/$name")
            assertNotNull("missing /$name on desktop classpath (processResources should copy voice_*.wav)", stream)
            stream!!.use { bytes ->
                assertTrue("/$name is empty", bytes.readBytes().isNotEmpty())
            }
        }
    }
}
