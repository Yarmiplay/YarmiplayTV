package com.yarmiplaytv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudibleSpeakingTest {
    @Test
    fun senderParticipantRoundTrip() {
        assertEquals(17L, participantSenderId("c17"))
        assertEquals("c17", senderParticipantId(17L))
        assertEquals(null, participantSenderId("x17"))
    }

    @Test
    fun concealedFramesAreMarkedForInstantSpeakClear() {
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4)
        jb.push(1L, 1, byteArrayOf(1))
        jb.push(1L, 2, byteArrayOf(2))
        jb.push(1L, 4, byteArrayOf(4)) // gap at seq 3
        val a = jb.poll(1L, decode = { ShortArray(960) { 400 } }, plc = { ShortArray(960) }, fec = { null })
        assertFalse(a!!.concealed)
        val b = jb.poll(1L, decode = { ShortArray(960) { 400 } }, plc = { ShortArray(960) }, fec = { null })
        assertFalse(b!!.concealed)
        // Missing seq 3 → PLC/FEC path must be concealed so speaking UI drops this tick.
        val gap = jb.poll(1L, decode = { ShortArray(960) { 400 } }, plc = { ShortArray(960) { 400 } }, fec = { null })
        assertTrue(gap!!.concealed)
    }
}
