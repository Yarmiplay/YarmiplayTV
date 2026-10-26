package com.yarmiplaytv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic Android-capture → Opus → desktop-decode interop.
 *
 * Reproduces the phone garble bug: hardware often delivers 44.1 kHz (or 16 kHz on the
 * VOICE_COMMUNICATION path) while Opus is configured for 48 kHz. Encoding those samples
 * as if they were 48 kHz shifts pitch/timing and sounds unusable on PC/TV/web peers.
 */
class AndroidDesktopVoiceInteropTest {

    @Test
    fun `440 Hz sine at 48 kHz survives Opus encode decode on desktop path`() {
        val opus = OpusCodec(32_000, fec = true, dtx = false, sampleRate = VoicePcm.SAMPLE_RATE, channels = 1)
        val pcm = VoicePcm.sine(hz = 440.0, rate = VoicePcm.SAMPLE_RATE, frames = 50)
        // Warm codec
        repeat(3) { opus.decode(opus.encode(pcm.copyOfRange(0, opus.frameSamples))!!) }
        val decoded = ArrayList<Short>()
        var offset = 0
        while (offset + opus.frameSamples <= pcm.size) {
            val frame = pcm.copyOfRange(offset, offset + opus.frameSamples)
            val encoded = opus.encode(frame)!!
            decoded += opus.decode(encoded)!!.toList()
            offset += opus.frameSamples
        }
        val out = decoded.toShortArray()
        val hz = VoicePcm.estimateHz(out)
        val rms = VoicePcm.rms(out)
        assertTrue("decoded RMS audible, was $rms", rms > 500.0)
        assertTrue("frequency near 440 Hz, was $hz", abs(hz - 440.0) < 40.0)
        assertTrue(
            "duration ~1s at 48 kHz (got ${out.size})",
            abs(out.size - VoicePcm.SAMPLE_RATE) < VoicePcm.SAMPLE_RATE / 10,
        )
    }

    @Test
    fun `440 Hz sine captured at 44100 must be resampled before 48 kHz Opus or pitch is wrong`() {
        val raw441 = VoicePcm.sine(hz = 440.0, rate = 44_100, frames = 50)
        // Bug path: feed 44.1 kHz samples into 48 kHz Opus without resampling.
        val buggy = OpusCodec(32_000, fec = true, dtx = false, sampleRate = 48_000, channels = 1)
        val buggyDecoded = encodeDecode(buggy, raw441.copyOf(buggy.frameSamples * 40))
        val buggyHz = VoicePcm.estimateHz(buggyDecoded, rate = 48_000)
        // ~440 * 44100/48000 ≈ 404 Hz — clearly wrong, evidences the garble root cause.
        assertTrue(
            "unsresampled 44.1k into 48k Opus shifts pitch (got $buggyHz, expect ~404)",
            abs(buggyHz - 404.0) < 50.0 || abs(buggyHz - 440.0) > 25.0,
        )

        // Fixed path: resample to 48 kHz then encode (AndroidVoiceEngine after 2.8.0).
        val fixed = VoicePcm.resampleMono(raw441, inRate = 44_100, outRate = 48_000)
        val good = OpusCodec(32_000, fec = true, dtx = false, sampleRate = 48_000, channels = 1)
        val goodDecoded = encodeDecode(good, fixed)
        val goodHz = VoicePcm.estimateHz(goodDecoded, rate = 48_000)
        val goodRms = VoicePcm.rms(goodDecoded)
        assertTrue("fixed path RMS audible, was $goodRms", goodRms > 500.0)
        assertTrue("fixed path frequency near 440 Hz, was $goodHz", abs(goodHz - 440.0) < 45.0)
    }

    @Test
    fun `16 kHz VOICE_COMMUNICATION capture resampled to 48 kHz Opus stays near 440 Hz`() {
        val raw16 = VoicePcm.sine(hz = 440.0, rate = 16_000, frames = 50)
        val at48 = VoicePcm.resampleMono(raw16, inRate = 16_000, outRate = 48_000)
        val opus = OpusCodec(32_000, fec = true, dtx = false, sampleRate = 48_000, channels = 1)
        val decoded = encodeDecode(opus, at48)
        val hz = VoicePcm.estimateHz(decoded)
        assertTrue("16k→48k path near 440 Hz, was $hz", abs(hz - 440.0) < 55.0)
        assertTrue("16k→48k audible", VoicePcm.rms(decoded) > 400.0)
    }

    @Test
    fun `frame accumulator yields exact 960-sample Opus frames from partial reads`() {
        val acc = VoicePcm.FrameAccumulator(960)
        val chunk = VoicePcm.sine(frames = 1) // 960 samples
        // Simulate AudioRecord partial reads of 300+300+360.
        val parts = listOf(chunk.copyOfRange(0, 300), chunk.copyOfRange(300, 600), chunk.copyOfRange(600, 960))
        val frames = parts.flatMap { acc.push(it) }
        assertEquals(1, frames.size)
        assertEquals(960, frames[0].size)
        assertTrue(frames[0].contentEquals(chunk))
    }

    private fun encodeDecode(opus: OpusCodec, pcm: ShortArray): ShortArray {
        repeat(3) {
            val warm = pcm.copyOfRange(0, minOf(opus.frameSamples, pcm.size))
            if (warm.size == opus.frameSamples) opus.decode(opus.encode(warm)!!)
        }
        val out = ArrayList<Short>()
        var offset = 0
        while (offset + opus.frameSamples <= pcm.size) {
            val frame = pcm.copyOfRange(offset, offset + opus.frameSamples)
            out += opus.decode(opus.encode(frame)!!)!!.toList()
            offset += opus.frameSamples
        }
        return out.toShortArray()
    }
}
