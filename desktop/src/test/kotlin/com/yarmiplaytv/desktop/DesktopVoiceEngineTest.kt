package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.DiscordAudioMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVoiceEngineTest {
    @Test
    fun connectsDiscordLobbyAndMediaSession() = runBlocking {
        val mux = object : SyncplayVoiceMux {
            override fun send(packet: ByteArray) {}
            override fun setOnPacket(handler: ((ByteArray) -> Unit)?) {}
        }
        val params = VoiceMediaParams(
            host = "yarmiplay.tv",
            token = "0123456789abcdef",
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            provider = "discord-lobby",
            appId = "1210325027023753307",
            lobbyId = "987654321012345678",
            lobbySecret = "topsecrettoken",
            syncplayMux = mux,
        )
        val engine = DesktopVoiceEngine()
        val connected = engine.connect(params)
        assertTrue("DesktopVoiceEngine should connect successfully with Discord lobby", connected)

        engine.setInputGain(1.5f)
        engine.setOutputGain(1.5f)
        engine.setNoiseSuppression(false)
        engine.setInputDeviceId("mic-1")
        engine.setVoiceActivityMode(false)
        engine.setPeerVolume("c4", 0.5f)
        val cfg = engine.discordAudioConfig()
        assertEquals(100f, cfg.inputVolumePercent, 0.01f)
        assertEquals(150f, cfg.outputVolumePercent, 0.01f)
        assertEquals("mic-1", cfg.inputDeviceId)
        assertFalse(cfg.noiseSuppression)
        assertTrue(cfg.echoCancellation)
        assertTrue(cfg.automaticGainControl)
        assertFalse(cfg.noiseCancellation)
        assertEquals(DiscordAudioMode.PUSH_TO_TALK, cfg.audioMode)
        assertEquals(64_000, cfg.bitrate)
        assertEquals(50f, cfg.participantVolumePercent["c4"]!!, 0.01f)
        engine.setNoiseSuppression(true)

        engine.setOutputDeviceId("spk-missing")
        assertEquals("", engine.discordAudioConfig().outputDeviceId)

        engine.setInputDeviceId(null)
        engine.setSelfDeaf(true)
        assertFalse(engine.discordAudioConfig().hearsOthers())
        engine.setTransmitting(true)
        engine.setSelfMuted(false)
        assertFalse(engine.capturingSpeech())
        engine.setSelfDeaf(false)
        engine.setSelfMuted(true)
        assertFalse(engine.capturingSpeech())

        engine.disconnect()
    }
}
