package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordVoiceEngineTest {

    @Test
    fun `connects to discord lobby and handles speech states`() = runBlocking {
        val sounds = mutableListOf<VoiceSound>()
        val engine = DiscordVoiceEngine(playSoundDelegate = { sound, _ -> sounds.add(sound) })

        val params = VoiceMediaParams(
            host = "yarmiplay.tv",
            token = "0123456789abcdef",
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            provider = "discord-lobby",
            appId = "1210325027023753307",
            lobbyId = "987654321012345678",
            lobbySecret = "topsecrettoken",
        )

        assertTrue(params.isDiscordLobby)
        val connected = engine.connect(params)
        assertTrue(connected)
        assertTrue(engine.isConnected)
        assertEquals("1210325027023753307", engine.appId)
        assertEquals("987654321012345678", engine.lobbyId)
        assertEquals(listOf(VoiceSound.JOIN), sounds)

        // Remote speaking
        engine.onUserSpeaking("c1", true)
        assertEquals(setOf("c1"), engine.audibleParticipantIds())

        // Remote peer mute
        engine.setPeerMuted("c1", true)
        assertTrue(engine.audibleParticipantIds().isEmpty())
        engine.setPeerMuted("c1", false)
        assertEquals(setOf("c1"), engine.audibleParticipantIds())

        // Remote user stops speaking
        engine.onUserSpeaking("c1", false)
        assertTrue(engine.audibleParticipantIds().isEmpty())

        // Local transmit and mute
        engine.setTransmitting(true)
        assertTrue(sounds.contains(VoiceSound.MIC_ON))
        engine.onLocalSpeaking(true, level = 0.8f)
        assertTrue(engine.capturingSpeech())
        assertTrue(engine.captureLevel() > 0f)

        engine.setSelfMuted(true)
        assertFalse(engine.capturingSpeech())
        assertEquals(0f, engine.captureLevel())

        // Disconnect
        engine.disconnect()
        assertFalse(engine.isConnected)
        assertTrue(sounds.contains(VoiceSound.LEAVE))
    }
}
