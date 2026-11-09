package com.yarmiplaytv.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Audio-path checks for the voice polish: jitter concealment, one band-limited resample,
 * Opus VoIP tuning, a single look-ahead limiter, and local mic-test loopback.
 */
class VoicePipelinePolishTest {
    @Test
    fun opusEncoderIsVoipTunedAndAdaptsBitrateToLoss() {
        assertEquals(10, OpusCodec.defaultComplexity(isAndroid = false, cpuCores = 2))
        assertEquals(8, OpusCodec.defaultComplexity(isAndroid = true, cpuCores = 4))
        assertEquals(9, OpusCodec.defaultComplexity(isAndroid = true, cpuCores = 6))
        assertEquals(10, OpusCodec.defaultComplexity(isAndroid = true, cpuCores = 8))

        val codec = OpusCodec(bitrate = 64_000)
        assertEquals(OpusApplication.OPUS_APPLICATION_VOIP, codec.application)
        assertEquals(OpusSignal.OPUS_SIGNAL_VOICE, codec.signalType)
        assertTrue(codec.vbr)
        assertTrue(codec.constrainedVbr)
        assertTrue(codec.inbandFec)
        assertFalse("DTX stays off so the receiver is not asked to conceal holes", codec.dtx)
        assertEquals(10, codec.complexity)
        assertEquals(OpusCodec.INITIAL_FEC_LOSS_PERCENT, codec.packetLossPercent)

        codec.updatePacketLoss(0)
        assertEquals(OpusCodec.MIN_FEC_LOSS_PERCENT, codec.packetLossPercent)
        assertEquals(64_000, codec.currentBitrate)

        codec.updatePacketLoss(20)
        assertEquals(20, codec.packetLossPercent)
        assertTrue("bitrate should step down under loss, was ${codec.currentBitrate}", codec.currentBitrate < 64_000)
        assertTrue(codec.currentBitrate >= OpusCodec.MIN_BITRATE)

        val wide = OpusCodec(bitrate = 128_000, channels = 2)
        assertEquals(128_000, wide.baseBitrate)
        assertEquals(2, wide.channels)
        val mono = VoicePcm.sine(hz = 440.0, frames = 1, amplitude = 0.3)
        val packet = wide.encode(mono)
        assertNotNull(packet)
        val decoded = wide.decode(packet!!)
        assertEquals(960 * 2, decoded!!.size)
        assertTrue(VoicePcm.rms(VoicePcm.interleavedPcm16ToMono(decoded, 2)) > 500)
    }

    @Test
    fun inbandFecThenPrimaryDecodeBothHaveEnergy() {
        val tx = OpusCodec(64_000)
        tx.updatePacketLoss(20)
        val rx = OpusCodec(64_000)
        val pcm = voiced(12)
        val packets = Array(12) { i -> tx.encode(pcm, i * 960)!! }
        assertNotNull(rx.decode(packets[0]))
        val recovered = rx.decodeFec(packets[2])
        val primary = rx.decode(packets[2])
        assertNotNull(recovered)
        assertNotNull(primary)
        assertTrue(
            "FEC frame for the missing packet was silent (rms=${VoicePcm.rms(recovered!!)})",
            VoicePcm.rms(recovered!!) > 200,
        )
        assertTrue(
            "primary decode of the FEC packet was silent (rms=${VoicePcm.rms(primary!!)})",
            VoicePcm.rms(primary!!) > 200,
        )
    }

    @Test
    fun jitterHoldPlaysALatePacketWithoutCallingPlc() {
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 5)
        var decoded = 0
        var plc = 0
        val loud = { ShortArray(960) { 4_000 } }
        jb.push(1L, 0, byteArrayOf(1))
        jb.push(1L, 1, byteArrayOf(2))
        repeat(2) {
            val played = jb.poll(1L, decode = { decoded++; loud() }, plc = { plc++; loud() }, fec = { null })
            assertFalse(played!!.concealed)
        }
        // 80 ms of waiting (4 ticks) for seq 2. Opus PLC must not run while the packet can still arrive.
        repeat(4) {
            val held = jb.poll(1L, decode = { decoded++; loud() }, plc = { plc++; loud() }, fec = { null })
            assertTrue(held!!.concealed)
        }
        assertEquals(0, plc)
        assertEquals(2, decoded)
        jb.push(1L, 2, byteArrayOf(3))
        val late = jb.poll(1L, decode = { decoded++; loud() }, plc = { plc++; loud() }, fec = { null })
        assertNotNull(late)
        assertFalse("late packet should play, not be skipped", late!!.concealed)
        assertEquals(3, decoded)
        assertEquals(0, plc)
    }

    @Test
    fun timeStretchKeepsEnergyWhileAPacketIsLate() {
        val tx = OpusCodec(64_000)
        tx.updatePacketLoss(10)
        val rx = OpusCodec(64_000)
        val pcm = voiced(8, hz = 140.0)
        val packets = Array(8) { i -> tx.encode(pcm, i * 960)!! }
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 5)
        jb.push(1L, 0, packets[0])
        jb.push(1L, 1, packets[1])
        val played = ArrayList<ShortArray>()
        repeat(2) {
            played += jb.poll(1L, { rx.decode(it) }, { rx.decodePlc() }, { rx.decodeFec(it) })!!.pcm
        }
        val before = VoicePcm.rms(played.last())
        assertTrue(before > 400)
        val held = jb.poll(1L, { rx.decode(it) }, { rx.decodePlc() }, { rx.decodeFec(it) })!!
        assertTrue(held.concealed)
        val during = VoicePcm.rms(held.pcm)
        assertTrue("stretch during jitter went quiet (rms=$during, previous=$before)", during > before * 0.25)
    }

    @Test
    fun deepQueueAcceleratesInsteadOfGrowingLatency() {
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 5)
        repeat(12) { i -> jb.push(1L, i, byteArrayOf((i + 1).toByte())) }
        var accelerated = 0
        var decoded = 0
        repeat(6) {
            val frame = jb.poll(
                1L,
                decode = { decoded++; ShortArray(960) { 1_000 } },
                plc = { ShortArray(960) },
                fec = { null },
            )
            if (frame?.accelerated == true) accelerated++
        }
        assertTrue("expected WSOLA accelerate while the queue was above target", accelerated > 0)
        assertTrue("accelerate should consume more than one packet per tick (decoded=$decoded)", decoded > 6)
    }

    @Test
    fun lossAndJitterDoNotOpenSilentGaps() {
        for (loss in doubleArrayOf(0.05, 0.10)) {
            for (jitterMs in intArrayOf(40, 80)) {
                for (seed in longArrayOf(1L, 7L, 21L)) {
                    val gaps = simulate(loss, jitterMs, seed)
                    assertEquals(
                        "audible gaps under loss=${(loss * 100).toInt()}% jitter=${jitterMs}ms seed=$seed: $gaps trace=${gaps.trace}",
                        0,
                        gaps.silentGaps,
                    )
                    assertTrue("too little audio survived: $gaps", gaps.audibleFrames > 40)
                }
            }
        }
    }

    @Test
    fun equalRatesDoNotResampleAndDownsampleRejectsAliases() {
        val source = ShortArray(64) { (it * 30).toShort() }
        val same = StreamingResampler(48_000, 48_000).push(source)
        assertEquals(source.toList(), same.toList())
        assertEquals(0, VoiceDeviceClock.appResampleStages(48_000))
        assertEquals(1, VoiceDeviceClock.appResampleStages(44_100))
        assertEquals(44_100, VoiceDeviceClock.openRate(44_100))
        assertEquals(48_000, VoiceDeviceClock.openRate(8_000))
        val buffer = VoiceDeviceClock.lowLatencyBufferBytes(minBytes = 100, frameBytes = 1_920, burstFrames = 240)
        assertTrue(buffer >= 1_920 * 2)
        assertTrue(buffer <= maxOf(100, 1_920 * 4))

        val inRate = 48_000
        val outRate = 16_000
        val hz = 12_000.0
        val n = inRate
        val sine = ShortArray(n) { i ->
            (sin(2.0 * PI * hz * i / inRate) * 16_000.0).roundToInt().toShort()
        }
        val sinc = StreamingResampler(inRate, outRate).push(sine)
        val linear = linearDown(sine, inRate, outRate)
        val sincRms = middleRms(sinc)
        val linearRms = middleRms(linear)
        assertTrue("sinc should reject 12 kHz into 16 kHz (rms=$sincRms)", sincRms < 400)
        assertTrue("sinc rms=$sincRms should be well under linear rms=$linearRms", sincRms < linearRms * 0.15)

        val voice = VoicePcm.sine(hz = 440.0, rate = 16_000, frames = 50, amplitude = 0.4)
        val up = StreamingResampler(16_000, 48_000).push(voice)
        val hzOut = VoicePcm.estimateHz(up.copyOfRange(up.size / 4, up.size / 2), 48_000)
        assertTrue("upsampled 440 Hz drifted to $hzOut", abs(hzOut - 440.0) < 15.0)
    }

    @Test
    fun mixSumsInFloatAndOneLimiterHoldsTheCeiling() {
        val quiet = ShortArray(960) { 4_000 }
        val one = VoiceLimiter()
        var single = quiet
        repeat(4) { single = one.mixAndLimit(listOf(quiet to 1f)) }
        val singleAvg = single.copyOfRange(200, 800).map { abs(it.toInt()) }.average()
        assertTrue("a single quiet talker was changed (avg=$singleAvg)", singleAvg in 3_500.0..4_500.0)

        val loud = ShortArray(960) { 20_000 }
        val alone = VoiceLimiter()
        var aloneOut = loud
        repeat(4) { aloneOut = alone.mixAndLimit(listOf(loud to 1f)) }
        val alonePeak = aloneOut.maxOf { abs(it.toInt()) }

        val both = VoiceLimiter()
        var bothOut = loud
        repeat(4) { bothOut = both.mixAndLimit(listOf(loud to 1f, loud to 1f)) }
        val bothPeak = bothOut.maxOf { abs(it.toInt()) }
        val ceiling = VoiceLimiter().ceilingPcm16
        assertTrue("two full talkers exceeded -1 dBFS ($bothPeak > $ceiling)", bothPeak <= ceiling)
        assertTrue("two talkers were divided down (peak=$bothPeak, one=$alonePeak)", bothPeak > alonePeak)
        assertTrue("two talkers should sit near the ceiling, peak=$bothPeak", bothPeak > 24_000)
        assertTrue("one talker below the knee should stay near 20000, peak=$alonePeak", alonePeak in 18_000..22_000)
    }

    @Test
    fun micTestLoopbackIsAudibleAndIsNotSent() = runBlocking {
        val session = VoiceMediaSession(this)
        try {
            assertTrue(session.startStandaloneMicTest())
            session.setNoiseSuppression(false)
            val sine = VoicePcm.sine(hz = 440.0, frames = 6, amplitude = 0.45)
            var offset = 0
            while (offset < sine.size) {
                val n = minOf(960, sine.size - offset)
                assertTrue(session.offerCapturePcm(sine.copyOfRange(offset, offset + n)))
                offset += n
            }
            assertTrue("expected encoded loopback frames, got ${session.loopbackPacketsCount}", session.loopbackPacketsCount >= 4)
            assertEquals(0, session.sentPacketsCount)
            assertFalse(session.capturingSpeech())
            val out = ShortArray(960)
            val n = session.pollMixedPcm(out)
            assertEquals(960, n)
            assertTrue("loopback rms ${VoicePcm.rms(out)}", VoicePcm.rms(out) > 400)
            val hz = VoicePcm.estimateHz(out)
            assertTrue("loopback pitch $hz", abs(hz - 440.0) < 40.0)
        } finally {
            session.disconnect()
        }
    }

    private data class SimResult(val silentGaps: Int, val audibleFrames: Int, val trace: String) {
        override fun toString(): String = "silentGaps=$silentGaps audibleFrames=$audibleFrames"
    }

    private fun simulate(lossRate: Double, jitterMs: Int, seed: Long): SimResult {
        val frameCount = 80
        val tx = OpusCodec(64_000)
        tx.updatePacketLoss((lossRate * 100).roundToInt().coerceIn(5, 30))
        val pcm = voiced(frameCount)
        val packets = Array(frameCount) { i -> tx.encode(pcm, i * 960)!! }
        val rng = Random(seed)
        val jitterFrames = jitterMs / 20
        val loss = BooleanArray(frameCount) { i -> i >= 6 && rng.nextDouble() < lossRate }
        val arrival = IntArray(frameCount) { i ->
            if (loss[i]) Int.MAX_VALUE else i + if (jitterFrames == 0) 0 else rng.nextInt(jitterFrames + 1)
        }
        val jb = VoiceJitterBuffer()
        val rx = OpusCodec(64_000)
        val pushed = BooleanArray(frameCount)
        val outputs = ArrayList<ShortArray>(frameCount)
        var tick = 0
        while (tick < frameCount + jitterFrames + 10 && outputs.size < frameCount) {
            for (i in 0 until frameCount) {
                if (!loss[i] && !pushed[i] && arrival[i] <= tick) {
                    jb.push(7L, i, packets[i], arrivalTimeMs = arrival[i] * 20L)
                    pushed[i] = true
                }
            }
            if (7L in jb.primedSenders()) {
                val frame = jb.poll(
                    7L,
                    decode = { rx.decode(it) ?: ShortArray(960) },
                    plc = { rx.decodePlc() },
                    fec = { rx.decodeFec(it) },
                )
                if (frame != null) outputs += frame.pcm
            }
            tick++
        }
        val rms = outputs.map { VoicePcm.rms(it) }
        var gaps = 0
        var audible = 0
        val dips = ArrayList<String>()
        for (i in 4 until rms.size - 4) {
            if (rms[i] > 250) audible++
            val neighbors = (rms[i - 1] + rms[i + 1]) / 2.0
            if (neighbors > 600 && rms[i] < 80) {
                gaps++
                dips += "$i:${rms[i].toInt()}~${neighbors.toInt()}"
            }
        }
        return SimResult(gaps, audible, dips.joinToString(" "))
    }

    private fun voiced(frames: Int, hz: Double = 180.0): ShortArray {
        val n = 960 * frames
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / 48_000.0
            var s = 0.0
            for (k in 1..6) s += (1.0 / k) * sin(2.0 * PI * hz * k * t)
            val env = 0.65 + 0.35 * sin(2.0 * PI * 3.0 * t)
            out[i] = (s / 2.45 * 0.40 * 32_767.0 * env).roundToInt()
                .coerceIn(-32_767, 32_767)
                .toShort()
        }
        return out
    }

    private fun linearDown(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        val step = inRate.toDouble() / outRate.toDouble()
        val n = (input.size / step).toInt()
        return ShortArray(n) { i ->
            val pos = i * step
            val base = pos.toInt().coerceAtMost(input.lastIndex)
            val frac = pos - base
            val a = input[base].toDouble()
            val b = input[(base + 1).coerceAtMost(input.lastIndex)].toDouble()
            (a * (1.0 - frac) + b * frac).roundToInt().toShort()
        }
    }

    private fun middleRms(pcm: ShortArray): Double {
        if (pcm.size < 8) return VoicePcm.rms(pcm)
        val from = pcm.size / 5
        val to = pcm.size * 4 / 5
        return VoicePcm.rms(pcm.copyOfRange(from, to))
    }
}
