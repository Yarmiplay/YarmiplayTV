package com.yarmiplaytv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class VoiceQualityTest {
    @Test
    fun streamingResamplerKeepsPhaseAcrossChunks() {
        val rateIn = 16_000
        val rateOut = 48_000
        val totalIn = rateIn // 1 s
        val sine = ShortArray(totalIn) { i ->
            (sin(2.0 * Math.PI * 440.0 * i / rateIn) * 16_000).toInt().toShort()
        }
        val oneShot = VoicePcm.resampleMono(sine, rateIn, rateOut)
        val streaming = StreamingResampler(rateIn, rateOut)
        val chunked = ArrayList<Short>()
        var offset = 0
        while (offset < sine.size) {
            val end = minOf(sine.size, offset + 320)
            chunked += streaming.push(sine.copyOfRange(offset, end)).toList()
            offset = end
        }
        // Length within one output sample of the one-shot path; samples stay close (no clicks).
        assertTrue(abs(chunked.size - oneShot.size) <= 2)
        val n = minOf(chunked.size, oneShot.size, rateOut / 2)
        var mismatch = 0
        for (i in 0 until n) {
            if (abs(chunked[i] - oneShot[i]) > 2_000) mismatch++
        }
        assertTrue("streaming vs one-shot mismatches=$mismatch/$n", mismatch < n / 20)
    }

    @Test
    fun jitterBufferPrimesThenPacesWithPlcOnGap() {
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4)
        val pkt = ByteArray(20) { it.toByte() }
        // Prime with 10, 12, 13 — leave 11 missing so the second poll uses PLC (FEC returns null).
        jb.push(1L, 10, pkt)
        jb.push(1L, 12, pkt)
        jb.push(1L, 13, pkt)
        assertEquals(listOf(1L), jb.primedSenders())
        var decoded = 0
        var plc = 0
        val a = jb.poll(1L, decode = { decoded++; ShortArray(960) { 100 } }, plc = { plc++; ShortArray(960) }, fec = { null })
        assertEquals(960, a!!.size)
        assertEquals(1, decoded)
        val b = jb.poll(1L, decode = { decoded++; ShortArray(960) { 100 } }, plc = { plc++; ShortArray(960) { 1 } }, fec = { null })
        assertEquals(960, b!!.size)
        assertEquals(1, plc)
    }

    @Test
    fun noiseSuppressorGatesSilenceAndPassesSpeech() {
        val ns = VoiceNoiseSuppressor()
        // Warm noise floor with quiet frames.
        repeat(8) {
            ns.process(ShortArray(VoiceNoiseSuppressor.FRAME) { ((it % 7) - 3).toShort() })
        }
        val quiet = ns.process(ShortArray(VoiceNoiseSuppressor.FRAME) { ((it % 5) - 2).toShort() })
        assertTrue(quiet.all { it == 0.toShort() } || !ns.lastSpeech)
        // Loud speech-like tone.
        val speech = ShortArray(VoiceNoiseSuppressor.FRAME) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / 48_000) * 12_000).toInt().toShort()
        }
        repeat(6) { ns.process(speech) }
        assertTrue(ns.lastSpeech)
        ns.setEnabled(false)
        val passthrough = ns.process(speech)
        assertEquals(speech.toList(), passthrough.toList())
    }
}
