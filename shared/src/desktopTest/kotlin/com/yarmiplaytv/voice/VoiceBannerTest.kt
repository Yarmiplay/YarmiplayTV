package com.yarmiplaytv.voice

import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.syncplay.VoicePeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun serverWithoutVoiceShowsClearNotice() {
        val ui = VoiceUiState(
            available = false,
            enabledByClient = true,
            serverLacksVoice = true,
        )
        val banner = voiceBanner(ui)
        assertTrue(banner!!.contains("doesn't support voice"))
    }

    @Test
    fun voiceDisabledOnServerShowsClearNotice() {
        val ui = VoiceUiState(
            available = false,
            enabledByClient = true,
            voiceDisabledOnServer = true,
        )
        assertEquals("Voice disabled on this server", voiceBanner(ui))
    }

    @Test
    fun receivingNamesPreferRxDiagnostic() {
        val ui = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            capture = VoiceCaptureKind.OPEN,
            talkMode = VoiceTalkMode.OPEN_MIC,
            talking = true,
            receivingNames = listOf("bob"),
            peers = listOf(VoicePeer("c2", "bob")),
        )
        assertEquals("Receiving audio from bob", voiceBanner(ui))
    }
}
