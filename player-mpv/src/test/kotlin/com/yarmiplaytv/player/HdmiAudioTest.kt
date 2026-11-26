package com.yarmiplaytv.player

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HdmiAudioTest {
    @Test
    fun noReportedEncodingLeavesPassthroughOff() {
        assertEquals(emptyList<String>(), HdmiAudio.codecsFor(emptySet()))
    }

    @Test
    fun iecEnablesTheFullSpdifSet() {
        assertEquals(
            listOf("ac3", "eac3", "dts", "truehd", "dts-hd"),
            HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_IEC61937)),
        )
    }

    @Test
    fun rawOnlyEncodingsEnableOnlyUnwrappableCodecs() {
        assertEquals(
            listOf("ac3", "eac3", "dts"),
            HdmiAudio.codecsFor(
                setOf(
                    AudioFormat.ENCODING_AC3,
                    AudioFormat.ENCODING_E_AC3,
                    AudioFormat.ENCODING_DTS,
                    AudioFormat.ENCODING_DOLBY_TRUEHD,
                    AudioFormat.ENCODING_DTS_HD,
                    AudioFormat.ENCODING_AC4,
                ),
            ),
        )
        assertFalse(HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_AC4)).contains("ac4"))
        assertEquals(emptyList<String>(), HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_DOLBY_TRUEHD)))
        assertEquals(emptyList<String>(), HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_DTS_HD)))
    }

    @Test
    fun describeFormatsReportedEncodings() {
        assertEquals("none", HdmiAudio.describe(emptySet()))
        assertEquals(
            "AC3,E-AC3,DTS",
            HdmiAudio.describe(setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_DTS)),
        )
    }
}
