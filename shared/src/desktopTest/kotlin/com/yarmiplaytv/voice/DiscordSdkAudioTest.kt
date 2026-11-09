package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DiscordSdkAudioTest {
    @Test
    fun `input volume matches the SDK 0 to 100 cap and output matches 0 to 200`() {
        assertEquals(0f, DiscordSdkAudio.inputVolumePercent(0f), 0.01f)
        assertEquals(100f, DiscordSdkAudio.inputVolumePercent(1f), 0.01f)
        assertEquals(50f, DiscordSdkAudio.inputVolumePercent(0.5f), 0.01f)
        // The in-app slider goes to 200%. Discord input does not, so the boost is not passed through.
        assertEquals(100f, DiscordSdkAudio.inputVolumePercent(2f), 0.01f)
        assertEquals(1f, DiscordSdkAudio.amplitudeFromPercent(100f), 0.01f)
        assertEquals(0.5f, DiscordSdkAudio.amplitudeFromPercent(50f), 0.01f)

        assertEquals(0f, DiscordSdkAudio.outputVolumePercent(0f), 0.01f)
        assertEquals(100f, DiscordSdkAudio.outputVolumePercent(1f), 0.01f)
        assertEquals(150f, DiscordSdkAudio.outputVolumePercent(1.5f), 0.01f)
        assertEquals(200f, DiscordSdkAudio.outputVolumePercent(2f), 0.01f)
        assertEquals(200f, DiscordSdkAudio.outputVolumePercent(4f), 0.01f)
        assertEquals(1.5f, DiscordSdkAudio.amplitudeFromPercent(150f), 0.01f)
        assertEquals(2f, DiscordSdkAudio.amplitudeFromPercent(200f), 0.01f)
    }

    @Test
    fun `mute deafen and voice activity match Call semantics`() {
        assertTrue(DiscordSdkAudio.transmits(selfMute = false, selfDeaf = false, wantTransmit = true))
        assertFalse(DiscordSdkAudio.transmits(selfMute = true, selfDeaf = false, wantTransmit = true))
        assertFalse(DiscordSdkAudio.transmits(selfMute = false, selfDeaf = true, wantTransmit = true))
        assertFalse(DiscordSdkAudio.transmits(selfMute = false, selfDeaf = false, wantTransmit = false))
        assertTrue(DiscordSdkAudio.hearsOthers(selfDeaf = false))
        assertFalse(DiscordSdkAudio.hearsOthers(selfDeaf = true))
    }

    @Test
    fun `vad default is minus 60 dBFS and krisp is unavailable`() {
        val rms = DiscordSdkAudio.rmsAtDbFs(-60f)
        assertEquals(32.768, rms, 0.01)
        assertEquals(32768.0, DiscordSdkAudio.rmsAtDbFs(50f), 1.0)
        assertFalse(DiscordSdkAudio.KRISP_AVAILABLE)
        assertFalse(DiscordSdkAudio.preferredCaptureRates().contains(DiscordSdkAudio.NARROWBAND_FALLBACK_RATE))
        assertEquals(48_000, DiscordSdkAudio.preferredCaptureRates().first())
        assertTrue(DiscordSdkAudio.playbackSharesVideoStream(isTv = true))
        assertFalse(DiscordSdkAudio.playbackSharesVideoStream(isTv = false))
        assertTrue(abs(DiscordSdkAudio.rmsAtDbFs(-100f) - 32768.0 * Math.pow(10.0, -5.0)) < 0.01)
    }

    @Test
    fun `engine records the SDK config and keeps mute off playback`() = runBlocking {
        val engine = DiscordVoiceEngine()
        engine.setInputDeviceId("mic-a")
        engine.setOutputDeviceId("")
        engine.setInputGain(1.75f)
        engine.setOutputGain(1.25f)
        engine.setPeerVolume("c9", 1.5f)
        engine.setNoiseSuppression(false)
        engine.setNoiseCancellation(true)
        engine.setVadThreshold(automatic = false, thresholdDb = -30f)
        engine.setVoiceActivityMode(activity = false)

        val connected = engine.connect(discordParams())
        assertTrue(connected)
        val cfg = engine.audioConfig()
        assertEquals("mic-a", cfg.inputDeviceId)
        assertEquals("", cfg.outputDeviceId)
        assertEquals(100f, cfg.inputVolumePercent, 0.01f)
        assertEquals(1f, engine.inputAmplitude(), 0.01f)
        assertEquals(125f, cfg.outputVolumePercent, 0.01f)
        assertEquals(1.25f, engine.outputAmplitude(), 0.01f)
        assertEquals(150f, cfg.participantVolumePercent["c9"]!!, 0.01f)
        assertEquals(1.5f, engine.peerAmplitude("c9"), 0.01f)
        assertEquals(1f, engine.peerAmplitude("c-missing"), 0.01f)
        assertFalse(cfg.noiseSuppression)
        assertTrue(cfg.echoCancellation)
        assertTrue(cfg.automaticGainControl)
        assertFalse(cfg.noiseCancellation)
        assertTrue(cfg.noiseCancellationRequested)
        assertFalse(cfg.vadAutomatic)
        assertEquals(-30f, cfg.vadThresholdDb, 0.01f)
        assertEquals(DiscordAudioMode.PUSH_TO_TALK, cfg.audioMode)
        assertEquals(48_000, cfg.sampleRate)
        assertEquals(1, cfg.channels)
        assertEquals(20, cfg.frameMs)
        assertEquals(64_000, cfg.bitrate)

        engine.setVoiceActivityMode(activity = false)
        engine.setTransmitting(true)
        assertTrue(engine.audioConfig().pttActive)
        engine.onUserSpeaking("c9", true)
        assertEquals(setOf("c9"), engine.audibleParticipantIds())
        engine.onLocalSpeaking(true, level = 0.8f)
        assertEquals(0.8f, engine.captureLevel(), 0.01f)

        engine.setSelfMuted(true)
        assertFalse(engine.capturingSpeech())
        assertEquals(0f, engine.captureLevel(), 0.01f)
        assertEquals(setOf("c9"), engine.audibleParticipantIds())
        assertFalse(engine.audioConfig().selfDeaf)

        engine.setSelfMuted(false)
        engine.setTransmitting(true)
        engine.onLocalSpeaking(true, level = 0.8f)
        assertTrue(engine.capturingSpeech())
        assertTrue(engine.captureLevel() > 0f)

        engine.setSelfDeaf(true)
        assertFalse(engine.audioConfig().hearsOthers())
        assertTrue(engine.audibleParticipantIds().isEmpty())
        assertFalse(engine.capturingSpeech())
        assertEquals(0f, engine.captureLevel(), 0.01f)
        engine.setTransmitting(true)
        assertFalse(engine.audioConfig().pttActive)
        engine.setSelfDeaf(false)
        assertTrue(engine.audioConfig().selfMute.not())
        engine.setTransmitting(true)
        engine.onLocalSpeaking(true, 0.4f)
        assertTrue(engine.capturingSpeech())

        engine.setInputDeviceId(null)
        assertEquals("", engine.audioConfig().inputDeviceId)
        engine.disconnect()
        assertFalse(engine.audioConfig().selfDeaf)
    }

    @Test
    fun `manual vad clamps and automatic restores the default`() {
        val engine = DiscordVoiceEngine()
        engine.setVadThreshold(false, -140f)
        assertEquals(-100f, engine.audioConfig().vadThresholdDb, 0.01f)
        engine.setVadThreshold(false, 12f)
        assertEquals(0f, engine.audioConfig().vadThresholdDb, 0.01f)
        engine.setVadThreshold(true, -10f)
        assertTrue(engine.audioConfig().vadAutomatic)
        assertEquals(-60f, engine.audioConfig().vadThresholdDb, 0.01f)
    }

    private fun discordParams() = VoiceMediaParams(
        host = "yarmiplay.tv",
        token = "0123456789abcdef",
        key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        provider = "discord-lobby",
        appId = "1210325027023753307",
        lobbyId = "987654321012345678",
        lobbySecret = "topsecrettoken",
    )
}
