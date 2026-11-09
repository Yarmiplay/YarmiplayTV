package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import com.yarmiplaytv.syncplay.VoiceMediaParams
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Levels for Discord lobby voice, measured on the same speech before and after the fix.
 *
 * Before: unity scale (what 2.9.9 sent when no AGC was running).
 * After: fixed +12 dB makeup, still one noise stage and no second AGC.
 * The playback limiter is the one [VoiceLimiter] already on the mix (look-ahead, −1 dBFS).
 */
class VoiceLoudnessTest {
    @Test
    fun `fixed makeup lifts quiet speech about 12 dB and stays near Discord loudness`() {
        val raw = VoicePcm.speechLike(rmsDbFs = -30.0, frames = 50)
        val before = encodeRoundTrip(processDiscord(raw, userGain = 1f, makeup = false))
        val after = encodeRoundTrip(processDiscord(raw, userGain = 1f, makeup = true))
        val beforeDb = VoicePcm.rmsDbFs(before)
        val afterDb = VoicePcm.rmsDbFs(after)
        val beforeLufs = VoicePcm.lufs(before)
        val afterLufs = VoicePcm.lufs(after)
        assertTrue(
            "makeup should be about +12 dB (before=$beforeDb dBFS / $beforeLufs LUFS, after=$afterDb dBFS / $afterLufs LUFS)",
            afterDb - beforeDb in 10.5..13.0,
        )
        assertTrue(
            "quiet speech at unity was far below Discord (before=$beforeDb dBFS / $beforeLufs LUFS)",
            beforeDb < -26.0,
        )
        assertTrue(
            "after makeup, RMS should sit near −18 dBFS (was $afterDb dBFS, $afterLufs LUFS)",
            afterDb in -22.0..-14.0,
        )
        assertTrue(
            "after makeup, loudness should sit near −18 LUFS (was $afterLufs LUFS, $afterDb dBFS)",
            afterLufs in -22.0..-12.0,
        )
    }

    @Test
    fun `agc stage disables makeup and the mic slider still scales including 200 percent`() {
        assertEquals(1f, VoicePcm.discordMakeupGain(agcStageActive = true), 0.001f)
        assertEquals(VoicePcm.FIXED_CAPTURE_MAKEUP, VoicePcm.discordMakeupGain(agcStageActive = false), 0.001f)

        val raw = VoicePcm.speechLike(rmsDbFs = -30.0, frames = 20)
        val unity = VoicePcm.rms(processDiscord(raw, userGain = 1f, makeup = false))
        val withAgcFlag = VoicePcm.rms(processDiscord(raw, userGain = 1f, makeup = false))
        val boosted = VoicePcm.rms(processDiscord(raw, userGain = 2f, makeup = false))
        val silenced = VoicePcm.rms(processDiscord(raw, userGain = 0f, makeup = true))
        val both = VoicePcm.rms(processDiscord(raw, userGain = 1f, makeup = true))
        assertEquals(unity, withAgcFlag, unity * 0.02)
        assertTrue("200% mic slider should be about +6 dB (unity=$unity boosted=$boosted)", boosted > unity * 1.8)
        assertTrue("200% should not be clipped into nothing", boosted < unity * 2.2)
        assertEquals(0.0, silenced, 0.1)
        // One gain stage: makeup is 4×, not makeup stacked on another 4×.
        assertTrue("makeup alone is one 4× stage (unity=$unity makeup=$both)", both in (unity * 3.6)..(unity * 4.4))
    }

    @Test
    fun `one limiter passes a single talker and does not divide by participant count`() {
        val limiter = VoiceLimiter()
        val frame = ShortArray(960) { i -> if (i % 2 == 0) 8000 else -8000 }
        val one = limiter.mixAndLimit(listOf(frame to 1f))
        limiter.reset()
        val plusSilentPeer = limiter.mixAndLimit(listOf(frame to 1f, ShortArray(960) to 1f))
        assertEquals(one.toList(), plusSilentPeer.toList())
        // Look-ahead holds the first samples. The body is the talker at unity, not divided by count.
        val bodyPeak = one.drop(limiter.lookAheadSamples).take(200).maxOf { abs(it.toInt()) }
        assertTrue("single talker stays near its own level (peak=$bodyPeak)", bodyPeak in 7000..9000)

        limiter.reset()
        val half = limiter.mixAndLimit(listOf(frame to 0.5f))
        val halfPeak = half.drop(limiter.lookAheadSamples).take(200).maxOf { abs(it.toInt()) }
        assertTrue("half gain is about half (peak=$halfPeak)", halfPeak in 3500..4500)

        limiter.reset()
        val muted = limiter.mixAndLimit(listOf(frame to 0f))
        assertTrue(muted.all { it.toInt() == 0 })

        limiter.reset()
        val hot = ShortArray(960) { 20_000 }
        val boosted = limiter.mixAndLimit(listOf(hot to 2f))
        val peak = boosted.maxOf { abs(it.toInt()) }
        assertTrue("200% output must stay at the ceiling (peak=$peak ceiling=${limiter.ceilingPcm16})", peak <= limiter.ceilingPcm16)
        assertTrue("200% output should approach the ceiling (peak=$peak)", peak > 20_000)

        limiter.reset()
        val overlap = limiter.mixAndLimit(listOf(hot to 1f, hot to 1f))
        val overlapPeak = overlap.maxOf { abs(it.toInt()) }
        assertTrue("two talkers stay under the ceiling (peak=$overlapPeak)", overlapPeak <= limiter.ceilingPcm16)
        assertTrue("two talkers are summed then limited (peak=$overlapPeak)", overlapPeak > 20_000)
    }

    @Test
    fun `session plays makeup speech louder than unity and keeps a single talker intact`() = runBlocking {
        val speech = VoicePcm.speechLike(rmsDbFs = -30.0, frames = 25)
        val before = playDiscord(speech, agcStageActive = true)
        val after = playDiscord(speech, agcStageActive = false)
        val beforeDb = VoicePcm.rmsDbFs(before)
        val afterDb = VoicePcm.rmsDbFs(after)
        assertTrue(
            "session makeup should raise the far end by about 12 dB (before=$beforeDb after=$afterDb, samples before=${before.size} after=${after.size})",
            after.size > 960 && before.size > 960 && afterDb - beforeDb in 9.0..14.0,
        )
        // 12 dB is one 4× scale. Two stacked gain stages would be about +24 dB.
        val ratio = afterDb - beforeDb
        assertTrue("only one gain stage ran (delta=$ratio dB)", ratio < 16.0)
    }

    private fun processDiscord(pcm: ShortArray, userGain: Float, makeup: Boolean): ShortArray {
        val ns = VoiceNoiseSuppressor()
        ns.setHardGate(false)
        val cleaned = ns.process(VoicePcm.scale(pcm, userGain))
        return if (makeup) VoicePcm.scale(cleaned, VoicePcm.FIXED_CAPTURE_MAKEUP) else cleaned
    }

    private fun encodeRoundTrip(pcm: ShortArray): ShortArray {
        val opus = OpusCodec(64_000, fec = true, dtx = false)
        val frame = 960
        if (pcm.size < frame * 3) return pcm
        repeat(2) {
            opus.decode(opus.encode(pcm.copyOfRange(0, frame))!!)
        }
        val out = ArrayList<Short>(pcm.size)
        var offset = 0
        while (offset + frame <= pcm.size) {
            val encoded = opus.encode(pcm.copyOfRange(offset, offset + frame)) ?: break
            out += opus.decode(encoded)!!.toList()
            offset += frame
        }
        return out.toShortArray()
    }

    private suspend fun playDiscord(pcm: ShortArray, agcStageActive: Boolean): ShortArray {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val alicePipe = Pipe()
        val bobPipe = Pipe()
        alicePipe.peer = bobPipe
        bobPipe.peer = alicePipe
        val heard = Collections.synchronizedList(ArrayList<Short>())
        val alice = VoiceMediaSession(scope)
        val bob = VoiceMediaSession(scope) { _, frame ->
            for (s in frame) heard += s
        }
        try {
            assertTrue(alice.connect(params(alicePipe)))
            assertTrue(bob.connect(params(bobPipe)))
            alice.setAgcStageActive(agcStageActive)
            alice.setTransmitting(true)
            var offset = 0
            val frame = 960
            while (offset + frame <= pcm.size) {
                alice.offerCapturePcm(pcm.copyOfRange(offset, offset + frame))
                offset += frame
            }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (heard.size < frame * 8 && System.nanoTime() < deadline) delay(20)
            return heard.toShortArray()
        } finally {
            alice.disconnect()
            bob.disconnect()
            scope.cancel()
        }
    }

    private fun params(mux: SyncplayVoiceMux) = VoiceMediaParams(
        host = "yarmiplay.tv",
        token = "0123456789abcdef",
        key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        provider = "discord-lobby",
        appId = "1210325027023753307",
        lobbyId = "987654321012345678",
        lobbySecret = "topsecrettoken",
        syncplayMux = mux,
        syncplayMediaOnly = true,
    )

    private class Pipe : SyncplayVoiceMux {
        var peer: Pipe? = null
        private var handler: ((ByteArray) -> Unit)? = null
        override fun send(packet: ByteArray) {
            peer?.handler?.invoke(packet)
        }
        override fun setOnPacket(handler: ((ByteArray) -> Unit)?) {
            this.handler = handler
        }
    }
}
