package com.yarmiplaytv.voice

import com.yarmiplaytv.data.VoiceTalkMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceBannerTest {
    @Test
    fun openMicMutedShowsMutedNotConnected() {
        val ui = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            capture = VoiceCaptureKind.OPEN,
            talkMode = VoiceTalkMode.OPEN_MIC,
            selfMuted = true,
            statusLine = "Mic muted while playing",
        )
        assertEquals("Mic muted while playing", voiceBanner(ui))
    }

    @Test
    fun openMicLiveShowsMicLive() {
        val ui = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            capture = VoiceCaptureKind.OPEN,
            talkMode = VoiceTalkMode.OPEN_MIC,
            selfMuted = false,
            talking = true,
        )
        assertEquals("Mic live", voiceBanner(ui))
    }
}
