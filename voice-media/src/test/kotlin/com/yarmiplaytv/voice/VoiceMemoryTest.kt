package com.yarmiplaytv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Long-session memory / allocation behaviour for voice receive.
 * Accelerated: one poll = one 20 ms tick without sleeping.
 */
class VoiceMemoryTest {
    @Test
    fun abandonedPeerIsPrunedInsteadOfEndlessPlc() {
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4, maxConcealment = 75)
        repeat(3) { i -> jb.push(1L, i + 1, byteArrayOf((i + 1).toByte())) }
        // Drain the real packets.
        repeat(3) {
            val f = jb.poll(1L, decode = { ShortArray(960) { 200 } }, plc = { ShortArray(960) }, fec = { null })
            assertTrue(f != null && !f!!.concealed)
        }
        assertEquals(listOf(1L), jb.primedSenders())
        var plcTicks = 0
        // ~1.5 s of silence at 20 ms → peer must leave primedSenders.
        repeat(80) {
            val f = jb.poll(1L, decode = { ShortArray(960) }, plc = { plcTicks++; ShortArray(960) }, fec = { null })
            if (f == null) return@repeat
        }
        assertTrue("expected prune after concealment budget, still have ${jb.primedSenders()}", jb.primedSenders().isEmpty())
        assertTrue("PLC should stop once pruned (ran $plcTicks)", plcTicks in 70..76)
        assertEquals(0, jb.senderCount())
    }

    @Test
    fun thirtyMinuteAbandonedPeerChurnDoesNotRetainSlots() {
        // Simulate a 30 min watch party where 20 guests each talk briefly then leave.
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4, maxConcealment = 75)
        val ticksPerGuest = 100 // 2 s talk + prune
        val guests = 20
        val elapsed = measureTimeMillis {
            for (g in 1L..guests) {
                repeat(3) { i -> jb.push(g, i + 1, byteArrayOf(1)) }
                repeat(ticksPerGuest) {
                    jb.primedSenders().forEach { sender ->
                        jb.poll(sender, decode = { ShortArray(960) { 100 } }, plc = { ShortArray(960) }, fec = { null })
                    }
                }
            }
            // Extra ticks so the last guest prunes.
            repeat(80) {
                jb.primedSenders().forEach { sender ->
                    jb.poll(sender, decode = { ShortArray(960) }, plc = { ShortArray(960) }, fec = { null })
                }
            }
        }
        assertEquals("slots must not stick after leave/churn", 0, jb.senderCount())
        assertTrue("churn sim should finish quickly, took ${elapsed}ms", elapsed < 5_000)
        println("VoiceMemoryTest churn: guests=$guests elapsedMs=$elapsed residualSenders=${jb.senderCount()}")
    }

    @Test
    fun withoutPruneWouldRetainAbandonedSender() {
        // Documents the pre-2.8.10 failure mode: maxConcealment = Int.MAX_VALUE keeps the slot.
        val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4, maxConcealment = Int.MAX_VALUE)
        repeat(3) { i -> jb.push(9L, i + 1, byteArrayOf(1)) }
        repeat(3) { jb.poll(9L, decode = { ShortArray(960) }, plc = { ShortArray(960) }, fec = { null }) }
        repeat(200) { jb.poll(9L, decode = { ShortArray(960) }, plc = { ShortArray(960) }, fec = { null }) }
        assertEquals(listOf(9L), jb.primedSenders())
    }

    @Test
    fun thirtyMinuteSimulatedPlcFrameCountBoundedWithPrune() {
        // 30 min @ 20 ms = 90_000 ticks. One guest talks 1 s then leaves; without prune we'd PLC for ~89k ticks.
        val ticks = 90_000
        fun run(maxConcealment: Int): Int {
            val jb = VoiceJitterBuffer(frameSamples = 960, minFrames = 2, maxFrames = 4, maxConcealment = maxConcealment)
            var plcFrames = 0
            repeat(3) { i -> jb.push(1L, i + 1, byteArrayOf(1)) }
            repeat(ticks) {
                jb.primedSenders().forEach { sender ->
                    jb.poll(
                        sender,
                        decode = { ShortArray(960) { 50 } },
                        plc = { plcFrames++; ShortArray(960) },
                        fec = { null },
                    )
                }
            }
            return plcFrames
        }
        val withPrune = run(75)
        val without = run(Int.MAX_VALUE)
        println("VoiceMemoryTest 30min-sim: plcWithPrune=$withPrune plcWithoutPrune=$without")
        assertTrue("with prune PLC should be ~talk+conceal (~50-80), was $withPrune", withPrune in 40..90)
        assertTrue("without prune PLC ≈ full session ($without)", without > 80_000)
        // Rough retained pressure: each PLC ShortArray(960) ≈ 1.9 KB; without prune ≈ 170 MB of short-lived allocs.
        val approxMbWithout = without * 960 * 2L / (1024 * 1024)
        assertTrue("document ~${approxMbWithout}MB of PLC alloc churn without prune", approxMbWithout > 100)
    }
}
