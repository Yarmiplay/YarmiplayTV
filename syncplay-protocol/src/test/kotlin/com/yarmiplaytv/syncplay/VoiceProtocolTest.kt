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
        assertEquals("opus", voice.codec)
        assertEquals(9000, voice.udpPort)
        assertEquals(9000, voice.tcpPort)
        assertEquals("yv1", voice.transport)
        assertEquals("/yarmiplay/voice", voice.wsPath)
        assertEquals(64, voice.fingerprint.length)
    }

    @Test
    fun `descriptor parse yv1-syncplay version 2`() {
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement(
                """{"version":2,"codec":"opus","clockRate":48000,"channels":1,"ptime":20,"maxBitrate":32000,"fec":true,"dtx":true,"transport":"yv1-syncplay","udpPort":0,"tcpPort":0,"wsPath":"/yarmiplay/voice","maxParticipants":8,"fingerprint":""}""",
            ),
        )!!
        assertEquals(2, voice.version)
        assertEquals("yv1-syncplay", voice.transport)
        assertEquals(0, voice.udpPort)
        assertEquals(0, voice.tcpPort)
        assertEquals("", voice.fingerprint)
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
}
