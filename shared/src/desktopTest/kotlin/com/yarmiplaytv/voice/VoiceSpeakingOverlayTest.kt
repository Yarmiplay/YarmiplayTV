package com.yarmiplaytv.voice

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.VoicePeerPrefs
import com.yarmiplaytv.syncplay.VoicePeer
import com.yarmiplaytv.update.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeakingOverlayTest {
    @Test
    fun audibleSpeakingNamesOmitsMutedSelfAndIdle() {
        val ui = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            participantId = "c1",
            peers = listOf(
                VoicePeer(participantId = "c1", username = "me", speaking = true),
                VoicePeer(participantId = "c2", username = "alice", speaking = true),
                VoicePeer(participantId = "c3", username = "bob", speaking = true),
                VoicePeer(participantId = "c4", username = "carol", speaking = false),
            ),
            speakingIds = setOf("c1", "c2", "c3"),
        )
        val settings = AppSettings(
            voicePeerPrefs = mapOf("bob" to VoicePeerPrefs(muted = true)),
        )
        assertEquals(listOf("alice"), ui.audibleSpeakingNames(settings))
        // Instant clear: empty speakingIds → no names (no fade/linger in the list helper).
        assertTrue(ui.copy(speakingIds = emptySet()).audibleSpeakingNames(settings).isEmpty())
    }

    @Test
    fun installFailedMessageIncludesExitAndDetail() {
        assertEquals("1.2.0", Updates.failedInstallVersion("1.2.0|exit=5|Access denied"))
        assertEquals(Updates.INSTALL_FAILED, Updates.installFailedMessage("1.2.0"))
        assertTrue(Updates.installFailedMessage("1.2.0|exit=5|Access denied").contains("exit code 5"))
        assertTrue(Updates.installFailedMessage("1.2.0|exit=5|Access denied").contains("Access denied"))
    }
}
