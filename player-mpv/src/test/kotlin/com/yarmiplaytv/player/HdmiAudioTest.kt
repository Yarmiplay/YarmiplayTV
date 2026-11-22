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
    fun iecEnablesTheStockSpdifSet() {
        assertEquals(
            listOf("ac3", "eac3", "dts", "truehd", "dts-hd"),
            HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_IEC61937)),
        )
    }

    @Test
    fun onlyTheEncodingsTheDeviceReportsAreNamed() {
        assertEquals(
            listOf("ac3", "dts"),
            HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_AC4)),
        )
        assertFalse(HdmiAudio.codecsFor(setOf(AudioFormat.ENCODING_AC4)).contains("ac4"))
    }
}
