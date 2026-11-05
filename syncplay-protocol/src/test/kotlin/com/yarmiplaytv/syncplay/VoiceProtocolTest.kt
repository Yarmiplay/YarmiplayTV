package com.yarmiplaytv.syncplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `capabilities voice defaults false on older servers`() {
        val caps = SyncplayClient.parseCapabilities(json.parseToJsonElement("""{"fileRelay":true,"https":false}""").jsonObject)
        assertNotNull(caps)
        assertFalse(caps!!.voice)
        assertFalse(caps.voiceDisabled)
    }

    @Test
    fun `capabilities voiceDisabled parses when host turned voice off`() {
        val caps = SyncplayClient.parseCapabilities(
            json.parseToJsonElement("""{"fileRelay":true,"voice":false,"voiceDisabled":true}""").jsonObject,
        )!!
        assertFalse(caps.voice)
        assertTrue(caps.voiceDisabled)
    }

    @Test
    fun `disabled voice object carries exact server message`() {
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement("""{"enabled":false,"message":"voice disabled on this server"}"""),
        )!!
        assertFalse(voice.enabled)
        assertEquals("voice disabled on this server", voice.message)
        assertFalse(voice.usesSyncplayMux)
    }

    @Test
    fun `capabilities voice and descriptor parse yv1`() {
        val caps = SyncplayClient.parseCapabilities(
            json.parseToJsonElement("""{"fileRelay":true,"voice":true,"roomSettings":true}""").jsonObject,
        )!!
        assertTrue(caps.voice)
        assertFalse(caps.voiceDisabled)
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement(
                """{"version":1,"codec":"opus","clockRate":48000,"channels":1,"ptime":20,"maxBitrate":32000,"fec":true,"dtx":true,"transport":"yv1","udpPort":9000,"tcpPort":9000,"wsPath":"/yarmiplay/voice","maxParticipants":8,"fingerprint":"${"ab".repeat(32)}"}""",
            ),
        )!!
        assertEquals(1, voice.version)
        assertTrue(voice.enabled)
        assertEquals("opus", voice.codec)
        assertEquals(9000, voice.udpPort)
        assertEquals(9000, voice.tcpPort)
        assertEquals("yv1", voice.transport)
        assertNull(voice.mux)
        assertEquals("/yarmiplay/voice", voice.wsPath)
        assertEquals(64, voice.fingerprint.length)
        assertFalse(voice.usesSyncplayMux)
    }

    @Test
    fun `descriptor parse syncplay mux`() {
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement(
                """{"version":1,"enabled":true,"codec":"opus","clockRate":48000,"channels":1,"ptime":20,"maxBitrate":32000,"fec":true,"dtx":true,"transport":"yv1","mux":"syncplay","port":8999,"wsPath":"/yarmiplay/voice","maxParticipants":8}""",
            ),
        )!!
        assertEquals(1, voice.version)
        assertEquals("syncplay", voice.mux)
        assertEquals(8999, voice.port)
        assertTrue(voice.usesSyncplayMux)
    }

    @Test
    fun `descriptor parse discord lobby`() {
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement(
                """{"version":1,"enabled":true,"provider":"discord-lobby","appId":"1210325027023753307","codec":"opus","clockRate":48000,"channels":1,"ptime":20,"maxBitrate":32000,"fec":true,"dtx":true,"transport":"yv1","mux":"syncplay","port":8999,"maxParticipants":8}""",
            ),
        )!!
        assertEquals(1, voice.version)
        assertEquals("discord-lobby", voice.provider)
        assertEquals("1210325027023753307", voice.appId)
        assertTrue(voice.isDiscordLobby)
    }

    @Test
    fun `voice descriptor absent when voice capability off`() {
        assertNull(SyncplayClient.parseVoiceDescriptor(null))
    }

    @Test
    fun `voice closed reasons`() {
        assertEquals(VoiceClosedReason.ROOM, VoiceClosedReason.parse("room"))
        assertEquals(VoiceClosedReason.FULL, VoiceClosedReason.parse("full"))
        assertEquals(VoiceClosedReason.DISABLED, VoiceClosedReason.parse("disabled"))
        assertEquals(VoiceClosedReason.VANILLA, VoiceClosedReason.parse("vanilla"))
        assertEquals(VoiceClosedReason.LEFT, VoiceClosedReason.parse("left"))
        assertEquals(VoiceClosedReason.REPLACED, VoiceClosedReason.parse("replaced"))
        assertEquals(VoiceClosedReason.VERSION, VoiceClosedReason.parse("version"))
        assertEquals(VoiceClosedReason.UNKNOWN, VoiceClosedReason.parse("wat"))
    }

    @Test
    fun `mux voice record framing`() {
        val packet = ByteArray(56) { i -> if (i < 2) if (i == 0) 0x59 else 0x56 else 0 }
        val framed = ByteArray(3 + packet.size)
        framed[0] = VOICE_MUX_MARKER.toByte()
        framed[1] = 0
        framed[2] = 56
        packet.copyInto(framed, 3)
        assertEquals(VOICE_MUX_MARKER, framed[0].toInt() and 0xff)
        assertEquals(56, ((framed[1].toInt() and 0xff) shl 8) or (framed[2].toInt() and 0xff))
    }
}
