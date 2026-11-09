package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordVoiceProfileTest {
    @Test
    fun `discord lobby encodes at Discord voice rate and the relay keeps the seat cap`() {
        val discord = params(provider = "discord-lobby", maxBitrate = 32_000, clockRate = 16_000, channels = 2)
        assertEquals(64_000, VoiceMediaSession.opusBitrate(discord))
        assertEquals(48_000, VoiceMediaSession.opusClock(discord))
        assertEquals(1, VoiceMediaSession.opusChannels(discord))
        assertEquals(32.768, VoiceMediaSession.DISCORD_VAD_RMS, 0.05)

        val relay = params(provider = "yv1", maxBitrate = 32_000, clockRate = 48_000, channels = 1)
        assertEquals(32_000, VoiceMediaSession.opusBitrate(relay))
        assertEquals(48_000, VoiceMediaSession.opusClock(relay))
        assertEquals(1, VoiceMediaSession.opusChannels(relay))
        assertEquals(24_000, VoiceMediaSession.opusBitrate(params(provider = "yv1", maxBitrate = 8_000, clockRate = 48_000, channels = 1)))
        assertEquals(64_000, VoiceMediaSession.opusBitrate(params(provider = "yv1", maxBitrate = 96_000, clockRate = 48_000, channels = 1)))
    }

    @Test
    fun `discord capture does not drop gated silence and the relay still does`() {
        val silence = ShortArray(960)
        assertFalse(shouldDropGatedSilence(discordCapture = true, noiseSuppressionEnabled = true, speech = false, frame = silence))
        assertTrue(shouldDropGatedSilence(discordCapture = false, noiseSuppressionEnabled = true, speech = false, frame = silence))
        assertFalse(shouldDropGatedSilence(discordCapture = false, noiseSuppressionEnabled = true, speech = true, frame = silence))
        assertFalse(shouldDropGatedSilence(discordCapture = false, noiseSuppressionEnabled = false, speech = false, frame = silence))
        val loud = ShortArray(960) { 1000 }
        assertFalse(shouldDropGatedSilence(discordCapture = false, noiseSuppressionEnabled = true, speech = false, frame = loud))
    }

    private fun params(provider: String, maxBitrate: Int, clockRate: Int, channels: Int) = VoiceMediaParams(
        host = "yarmiplay.tv",
        token = "0123456789abcdef",
        key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        provider = provider,
        maxBitrate = maxBitrate,
        clockRate = clockRate,
        channels = channels,
    )
}
