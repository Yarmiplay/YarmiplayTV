package com.yarmiplaytv.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * One paced playback frame from [VoiceJitterBuffer.poll].
 * [concealed] is true for FEC/PLC (gap fill) — not real audio from the sender this tick.
 */
data class JitterFrame(val pcm: ShortArray, val concealed: Boolean)

/**
 * Per-sender adaptive jitter buffer (~40–80 ms) with Opus FEC / PLC on gaps.
 * Packets are enqueued by sequence; [poll] returns the next 20 ms PCM frame on the playback clock.
 *
 * Senders that stop delivering packets are pruned after [maxConcealment] consecutive concealed
 * ticks so leave/rejoin watch parties do not keep running Opus PLC forever.
 */
class VoiceJitterBuffer(
    private val frameSamples: Int = VoicePcm.frameSamples,
    private val minFrames: Int = 2, // 40 ms
    private val maxFrames: Int = 4, // 80 ms
    /** Drop a primed peer after this many PLC/FEC ticks with an empty queue (~1.5 s at 20 ms). */
    private val maxConcealment: Int = 75,
) {
    private class PeerSlot {
        val packets = java.util.TreeMap<Int, ByteArray>()
        var nextSeq: Int? = null
        var primed = false
        var target = 3 // 60 ms start
        val late = AtomicInteger(0)
        val missing = AtomicInteger(0)
        /** Consecutive concealed polls since the last real packet. */
        var concealRun = 0
    }

    private val peers = ConcurrentHashMap<Long, PeerSlot>()

    fun clear() = peers.clear()

    fun remove(sender: Long) {
        peers.remove(sender)
    }

    fun senderCount(): Int = peers.size

    fun push(sender: Long, sequence: Int, opus: ByteArray) {
        if (opus.isEmpty()) return
        val slot = peers.getOrPut(sender) { PeerSlot() }
        synchronized(slot) {
            // Drop absurdly late / duplicate.
            val next = slot.nextSeq
            if (next != null && sequence < next - maxFrames * 2) return
            if (slot.packets.size >= 32) {
                // Overrun: drop oldest.
                slot.packets.pollFirstEntry()
            }
            slot.packets[sequence] = opus.copyOf()
            slot.concealRun = 0
            if (!slot.primed && slot.packets.size >= slot.target) {
                slot.primed = true
                slot.nextSeq = slot.packets.firstKey()
            }
        }
    }

    /**
     * Decode the next frame for [sender] into PCM (or PLC). Returns null if not primed yet,
     * or if the peer was pruned for prolonged silence.
     * [decode] should decode an Opus packet; [plc] generates concealment; [fec] tries FEC from the
     * next packet when the expected one is missing.
     */
    fun poll(
        sender: Long,
        decode: (ByteArray) -> ShortArray?,
        plc: () -> ShortArray?,
        fec: (ByteArray) -> ShortArray?,
    ): JitterFrame? {
        val slot = peers[sender] ?: return null
        synchronized(slot) {
            if (!slot.primed) return null
            var next = slot.nextSeq ?: slot.packets.firstKey().also { slot.nextSeq = it }
            // Adapt target toward 40–80 ms from late/missing pressure.
            if (slot.late.get() > 4) {
                slot.target = min(maxFrames, slot.target + 1)
                slot.late.set(0)
            }
            if (slot.missing.get() == 0 && slot.packets.size > slot.target + 1) {
                slot.target = max(minFrames, slot.target - 1)
            }

            val packet = slot.packets.remove(next)
            slot.nextSeq = next + 1
            if (packet != null) {
                slot.concealRun = 0
                return JitterFrame(decode(packet) ?: silence(), concealed = false)
            }
            slot.missing.incrementAndGet()
            slot.concealRun++
            if (slot.concealRun >= maxConcealment && slot.packets.isEmpty()) {
                peers.remove(sender, slot)
                return null
            }
            // Try FEC using the following packet if present.
            val following = slot.packets[next + 1]
            if (following != null) {
                val concealed = fec(following)
                if (concealed != null) return JitterFrame(concealed, concealed = true)
            }
            return JitterFrame(plc() ?: silence(), concealed = true)
        }
    }

    /** All primed senders that should be mixed this tick. */
    fun primedSenders(): List<Long> = peers.filter { it.value.primed }.keys.toList()

    private fun silence() = ShortArray(frameSamples)
}
