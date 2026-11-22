package com.yarmiplaytv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAudioPassthroughTest {
    @Test
    fun disablesPassthroughWhenNoSupportedEncodingExists() {
        assertEquals(emptyList<String>(), AndroidAudioPassthrough.mpvSpdifCodecs(emptyList()))
    }

    @Test
    fun enablesOnlyRawCodecsReportedAsDirectlySupported() {
        assertEquals(
            listOf("ac3", "dts"),
            AndroidAudioPassthrough.mpvSpdifCodecs(listOf("AC3", "DTS-HD", "TrueHD", "DTS")),
        )
    }

    @Test
    fun usesIecCodecSetOnlyWhenIecIsSupported() {
        assertEquals(
            listOf("ac3", "dts", "eac3", "truehd", "dts-hd"),
            AndroidAudioPassthrough.mpvSpdifCodecs(listOf("IEC61937")),
        )
    }
}
