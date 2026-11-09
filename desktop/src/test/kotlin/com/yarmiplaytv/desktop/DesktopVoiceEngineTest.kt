package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.AecBackend
import com.yarmiplaytv.voice.AgcBackend
import com.yarmiplaytv.voice.DiscordAudioMode
import com.yarmiplaytv.voice.NsBackend
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

        // Verify v3.0.0 desktop processing chain toggles and single-stage mutual exclusion.
        val defaultChain = engine.activeProcessingChain()
        assertEquals(AecBackend.WEBRTC, defaultChain.aec)
        assertEquals(AgcBackend.WEBRTC, defaultChain.agc)
        assertEquals(NsBackend.WEBRTC, defaultChain.ns)
        assertEquals("voice chain: AEC=webrtc AGC=webrtc NS=webrtc", defaultChain.logLine())

        // Enabling AI noise suppression switches NS to DeepFilterNet3 and disables WebRTC NS.
        engine.setAiNoiseSuppression(true)
        val aiChain = engine.activeProcessingChain()
        assertEquals(AecBackend.WEBRTC, aiChain.aec)
        assertEquals(AgcBackend.WEBRTC, aiChain.agc)
        assertEquals(NsBackend.DEEPFILTERNET, aiChain.ns)
        assertFalse(aiChain.webrtcNsEnabled)
        assertTrue(aiChain.deepFilterNetEnabled)
        assertEquals("voice chain: AEC=webrtc AGC=webrtc NS=deepfilternet", aiChain.logLine())

        // AEC and AGC stay governed by their own toggles while DeepFilterNet is on.
        engine.setEchoCancellation(false)
        engine.setAutoGainControl(false)
        val dfnOnlyChain = engine.activeProcessingChain()
        assertEquals(AecBackend.NONE, dfnOnlyChain.aec)
        assertEquals(AgcBackend.NONE, dfnOnlyChain.agc)
        assertEquals(NsBackend.DEEPFILTERNET, dfnOnlyChain.ns)
        assertEquals("voice chain: AEC=none AGC=none NS=deepfilternet", dfnOnlyChain.logLine())

        // Higher quality options: bitrate up to 128 kbps and stereo.
        engine.setPreferredBitrate(128_000)
        engine.setPreferredStereo(true)
        assertEquals(128_000, engine.discordAudioConfig().bitrate)
        assertEquals(2, engine.discordAudioConfig().channels)

        engine.disconnect()
    }
}
