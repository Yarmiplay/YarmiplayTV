package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.runBlocking
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

        engine.setTransmitting(true)
        engine.setSelfMuted(false)
        assertFalse(engine.capturingSpeech())

        engine.disconnect()
    }
}
