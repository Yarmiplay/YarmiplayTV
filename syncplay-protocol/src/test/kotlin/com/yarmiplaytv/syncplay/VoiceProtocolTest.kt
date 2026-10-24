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
    }

    @Test
    fun `capabilities voice and descriptor parse`() {
        val caps = SyncplayClient.parseCapabilities(
            json.parseToJsonElement("""{"fileRelay":true,"voice":true,"roomSettings":true}""").jsonObject,
        )!!
        assertTrue(caps.voice)
        val voice = SyncplayClient.parseVoiceDescriptor(
            json.parseToJsonElement(
                """{"version":1,"codec":"opus","clockRate":48000,"channels":1,"ptime":20,"maxBitrate":32000,"fec":true,"dtx":true,"transport":"opus","udpPort":9000,"tcpPort":9000,"maxParticipants":8}""",
            ),
        )!!
        assertEquals(1, voice.version)
        assertEquals("opus", voice.codec)
        assertEquals(9000, voice.udpPort)
        assertEquals(9000, voice.tcpPort)
        assertEquals("opus", voice.transport)
    }

    @Test
    fun `voice descriptor absent when voice capability off`() {
        assertNull(SyncplayClient.parseVoiceDescriptor(null))
    }

    @Test
    fun `voice closed reasons`() {
        assertEquals(VoiceClosedReason.ROOM, VoiceClosedReason.parse("room"))
        assertEquals(VoiceClosedReason.FULL, VoiceClosedReason.parse("full"))
        assertEquals(VoiceClosedReason.UNKNOWN, VoiceClosedReason.parse("wat"))
    }
}
