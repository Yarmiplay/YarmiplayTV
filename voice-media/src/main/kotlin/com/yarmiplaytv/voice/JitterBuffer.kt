package com.yarmiplaytv.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One paced playback frame from [VoiceJitterBuffer.poll].
 * [concealed] is true for FEC/PLC (gap fill) — not real audio from the sender this tick.
 */
data class JitterFrame(
    val pcm: ShortArray,
    val concealed: Boolean,
    val accelerated: Boolean = false,
)

/**
 * Per-sender adaptive jitter buffer (NetEq-style, mono PCM of [frameSamples] per 20 ms tick).
 *
 * - **Expand, don't skip.** When the queue runs dry the expected packet may still be in flight
 *   (voice rides a TCP connection, so a late packet is the normal case and a lost one is rare).
 *   The buffer plays Opus PLC — the codec's own pitch/LPC concealment, which fades naturally —
 *   and holds the sequence number, so the late packet still decodes when it lands. Opus decodes
 *   a real packet after PLC without a seam, so nothing is skipped and nothing buzzes.
 * - **Real losses** (a newer packet is already queued) use in-band FEC from packet `N+1`,
 *   falling back to Opus PLC.
 * - **Latency control.** A queue deeper than the adaptive target is drained with pitch-aligned
 *   WSOLA time compression, preferring quiet frames so speech is not sped up audibly. A large
 *   backlog (after a TCP stall) accelerates every tick until it is back near target.
 * - **Adaptive target** from RFC 3550 inter-arrival jitter and underrun pressure, between
 *   [minFrames] and [maxFrames] (40–160 ms by default), stepping back down after clean runs.
 * - **Talk-spurt resync.** A sender that paused (mute, push-to-talk) resumes with the next
 *   sequence number after a long hold; the buffer re-anchors and briefly re-buffers to target
 *   instead of discarding the new spurt as "late".
 *
 * Every sample — decoded, concealed or time-compressed — flows through one per-sender FIFO,
 * so output is always continuous and in order.
 *
 * Senders that stop delivering packets are pruned after [maxConcealment] consecutive concealed
 * ticks so leave/rejoin watch parties do not keep running Opus PLC forever.
 */
class VoiceJitterBuffer(
    private val frameSamples: Int = VoicePcm.frameSamples,
    private val minFrames: Int = 2, // 40 ms default minimum
    private val maxFrames: Int = 8, // 160 ms default maximum
    /** Drop a primed peer after this many PLC/FEC ticks with an empty queue (~1.5 s at 20 ms). */
    private val maxConcealment: Int = 75,
) {
    private inner class PeerSlot {
        val packets = java.util.TreeMap<Int, ByteArray>()
        var nextSeq: Int? = null
        var primed = false
        var target: Int = minFrames.coerceIn(1, maxFrames)
        val late = AtomicInteger(0)
        val missing = AtomicInteger(0)
        /** Consecutive concealed polls since the last real packet. */
        var concealRun = 0
        /** PLC frames emitted while holding [nextSeq] on an empty queue (expand). */
        var underrunConcealed = 0
        /**
         * True after a hold ran past [maxFrames] and the stream is treated as stopped. Further
         * empty polls advance the sequence; the next packet re-anchors the stream.
         */
        var inLossBurst = false
        /** Ticks left to wait for the queue to refill to target after a resync. */
        var rebufferTicks = 0
        var playedReal = false
        /** Logical 20 ms tick counter incremented on each [poll] and wall-clock arrival tracking. */
        var pollTick: Long = 0L
        var lastPushTick: Long = -1L
        var lastWallNano: Long = 0L
        var lastArrivalMs: Long = Long.MIN_VALUE
        var lastPushSeq: Int = Int.MIN_VALUE
        /** RFC 3550 smoothed inter-arrival jitter in 20 ms frame units. */
        var jitterFrames: Double = 0.0
        /** Consecutive polls without missing/late packets (for target step-down). */
        var cleanRun: Int = 0
        /** Cooldown ticks between WSOLA accelerate operations so cadence stays natural. */
        var accelerateCooldown: Int = 0
        var acceleratedCount: Int = 0
        var fecRecoveredCount: Int = 0
        var plcCount: Int = 0

        /** Sliding window loss counters (decayed periodically). */
        var windowReceived: Int = 0
        var windowLost: Int = 0

        /** Decoded PCM not yet played (time compression can leave a partial frame here). */
        var pcmFifo = ShortArray(0)
        var lastSample: Short = 0
        /** RMS of the last frame decoded from a real packet (hole detection). */
        var lastRealRms: Double = 0.0
        /** The last 40 ms of real (non-concealed) audio played, newest at the end (pitch fill source). */
        val lastOut = ShortArray(frameSamples * 2)
        /** True after a pitch fill: the next real frame is ramped in from the last sample. */
        var discontinuity = false

        fun pushFifo(samples: ShortArray) {
            if (samples.isEmpty()) return
            val next = ShortArray(pcmFifo.size + samples.size)
            System.arraycopy(pcmFifo, 0, next, 0, pcmFifo.size)
            System.arraycopy(samples, 0, next, pcmFifo.size, samples.size)
            pcmFifo = next
        }

        /** Exactly one [frameSamples] frame; zero-pads if a short decode left the FIFO thin. */
        fun popFifoFrame(): ShortArray {
            if (pcmFifo.size < frameSamples) {
                val out = ShortArray(frameSamples)
                System.arraycopy(pcmFifo, 0, out, 0, pcmFifo.size)
                pcmFifo = ShortArray(0)
                return out
            }
            val out = pcmFifo.copyOfRange(0, frameSamples)
            pcmFifo = pcmFifo.copyOfRange(frameSamples, pcmFifo.size)
            return out
        }

        fun recordReceived(count: Int = 1) {
            windowReceived += count
            decayLossWindowIfNeeded()
        }

        fun recordLost(count: Int = 1) {
            missing.addAndGet(count)
            windowLost += count
            cleanRun = 0
            decayLossWindowIfNeeded()
        }

        private fun decayLossWindowIfNeeded() {
            if (windowReceived + windowLost > 200) {
                windowReceived = (windowReceived + 1) shr 1
                windowLost = windowLost shr 1
            }
        }
    }

    private val peers = ConcurrentHashMap<Long, PeerSlot>()

    fun clear() = peers.clear()

    fun remove(sender: Long) {
        peers.remove(sender)
    }

    fun senderCount(): Int = peers.size

    /** Current adaptive target buffer delay in frames for [sender] (or across active peers). */
    fun targetFrames(sender: Long? = null): Int {
        if (sender != null) return peers[sender]?.let { synchronized(it) { it.target } } ?: minFrames
        val active = peers.values.filter { it.primed }
        if (active.isEmpty()) return minFrames
        return active.maxOf { synchronized(it) { it.target } }
    }

    /** Packets currently queued for [sender] (0 when unknown). */
    fun queuedFrames(sender: Long): Int = peers[sender]?.let { synchronized(it) { it.packets.size } } ?: 0

    /** Estimated inter-arrival jitter in milliseconds across active peers. */
    fun jitterMs(sender: Long? = null): Double {
        if (sender != null) return peers[sender]?.let { synchronized(it) { it.jitterFrames * 20.0 } } ?: 0.0
        val active = peers.values.filter { it.primed }
        if (active.isEmpty()) return 0.0
        return active.maxOf { synchronized(it) { it.jitterFrames * 20.0 } }
    }

    /** Measured packet-loss percentage (`0..100`) across active senders for Opus FEC feedback. */
    fun lossPercent(sender: Long? = null): Int {
        var rec = 0
        var lost = 0
        if (sender != null) {
            peers[sender]?.let {
                synchronized(it) {
                    rec += it.windowReceived
                    lost += it.windowLost
                }
            }
        } else {
            for (slot in peers.values) {
                synchronized(slot) {
                    rec += slot.windowReceived
                    lost += slot.windowLost
                }
            }
        }
        val total = rec + lost
        if (total < 5) return 0
        return ((lost * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    fun push(sender: Long, sequence: Int, opus: ByteArray, arrivalTimeMs: Long = -1L) {
        if (opus.isEmpty()) return
        val slot = peers.getOrPut(sender) { PeerSlot() }
        val nowNano = System.nanoTime()
        synchronized(slot) {
            // Measure inter-arrival jitter using explicit arrivalTimeMs, logical poll ticks, or wall clock.
            // Arrival timestamps and nanoTime stay in separate fields so a test clock cannot be
            // subtracted from a wall-clock reading.
            if (slot.lastPushSeq != Int.MIN_VALUE) {
                val seqDelta = (sequence - slot.lastPushSeq).coerceIn(-16, 16)
                if (seqDelta != 0) {
                    val arrivalFrames = when {
                        arrivalTimeMs >= 0L && slot.lastArrivalMs != Long.MIN_VALUE ->
                            (arrivalTimeMs - slot.lastArrivalMs) / 20.0
                        slot.pollTick != slot.lastPushTick ->
                            (slot.pollTick - slot.lastPushTick).toDouble()
                        slot.lastWallNano > 0L ->
                            ((nowNano - slot.lastWallNano) / 20_000_000.0).coerceIn(0.0, 10.0)
                        else -> seqDelta.toDouble()
                    }
                    // A talk-spurt gap is not jitter: only count arrivals within a few frames.
                    if (arrivalFrames < 12.0) {
                        val transitDiff = abs(arrivalFrames - seqDelta.toDouble())
                        slot.jitterFrames += (transitDiff - slot.jitterFrames) / 16.0
                    }
                }
            }
            slot.lastPushSeq = sequence
            slot.lastPushTick = slot.pollTick
            slot.lastWallNano = nowNano
            if (arrivalTimeMs >= 0L) slot.lastArrivalMs = arrivalTimeMs

            val next = slot.nextSeq
            if (next != null && sequence < next) {
                if (slot.inLossBurst && slot.packets.isEmpty()) {
                    // The sender paused and resumed: re-anchor instead of discarding the spurt.
                    resync(slot, sequence)
                } else {
                    // Packet arrived after playout already passed its sequence number.
                    if (sequence >= next - maxFrames * 2) {
                        slot.late.incrementAndGet()
                        slot.cleanRun = 0
                    }
                    return
                }
            } else if (next != null && slot.inLossBurst && slot.packets.isEmpty() && sequence - next > maxFrames * 4) {
                // Far ahead after a long silence (sender restarted its counter upward): re-anchor.
                resync(slot, sequence)
            }
            if (slot.packets.size >= 64) {
                // Hard overrun: drop oldest.
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

    private fun resync(slot: PeerSlot, sequence: Int) {
        slot.nextSeq = sequence
        slot.inLossBurst = false
        slot.underrunConcealed = 0
        slot.rebufferTicks = slot.target
    }

    /**
     * Produce the next 20 ms frame for [sender]. Returns null if not primed yet, or if the peer
     * was pruned for prolonged silence.
     * [decode] decodes an Opus packet; [plc] runs Opus packet-loss concealment; [fec] decodes
     * the in-band FEC copy of the missing frame carried by the following packet.
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
            slot.pollTick++
            if (slot.accelerateCooldown > 0) slot.accelerateCooldown--
            adaptTarget(slot)

            var next = slot.nextSeq ?: slot.packets.firstKey().also { slot.nextSeq = it }
            // Prune any stale entries older than next.
            if (slot.packets.isNotEmpty() && slot.packets.firstKey() < next) {
                slot.packets.headMap(next, false).clear()
            }

            // After a resync, let the queue refill to target before playing the new spurt so the
            // first jitter blip of a sentence does not immediately underrun.
            if (slot.rebufferTicks > 0) {
                if (slot.packets.size < slot.target) {
                    slot.rebufferTicks--
                    return conceal(slot, plc)
                }
                slot.rebufferTicks = 0
            }

            // Expand already covered the time slots of the sequences we were holding for. If the
            // next available packet is ahead of `next`, those held sequences were lost: skip them
            // (the Opus decoder already advanced through PLC) instead of adding more delay.
            if (slot.underrunConcealed > 0 && slot.packets.isNotEmpty()) {
                val firstAvail = slot.packets.firstKey()
                if (firstAvail > next) {
                    val skipped = min(firstAvail - next, slot.underrunConcealed)
                    slot.recordLost(skipped)
                    next += skipped
                    slot.nextSeq = next
                }
            }

            val packet = slot.packets.remove(next)
            if (packet != null) {
                slot.concealRun = 0
                slot.underrunConcealed = 0
                slot.inLossBurst = false
                slot.playedReal = true
                slot.recordReceived(1)
                slot.cleanRun++
                val pcm1 = decode(packet) ?: silence()
                slot.lastRealRms = VoicePcm.rms(pcm1)
                slot.nextSeq = next + 1

                // Latency above target: compress two packets into one tick with WSOLA.
                val excess = slot.packets.size - slot.target
                val canAccelerate = excess > 0 &&
                    slot.accelerateCooldown == 0 &&
                    slot.packets.containsKey(next + 1) &&
                    (excess >= 2 || VoicePcm.rms(pcm1) < QUIET_RMS)
                if (canAccelerate) {
                    val packet2 = slot.packets.remove(next + 1)!!
                    slot.nextSeq = next + 2
                    slot.recordReceived(1)
                    val pcm2 = decode(packet2) ?: silence()
                    // Far behind (e.g. a TCP stall delivered a burst): drain every tick.
                    slot.accelerateCooldown = if (excess >= 4) 0 else 2
                    slot.acceleratedCount++
                    return emit(slot, combineWithFifo(slot, pcm1, pcm2), concealed = false, accelerated = true)
                }

                slot.pushFifo(pcm1)
                return emit(slot, slot.popFifoFrame(), concealed = false)
            }

            // `next` is not in `slot.packets`.
            slot.concealRun++
            if (slot.concealRun >= maxConcealment && slot.packets.isEmpty()) {
                peers.remove(sender, slot)
                return null
            }

            if (slot.packets.isNotEmpty()) {
                // Genuine gap (packet `next` was lost; a newer one is already queued).
                // FEC from packet N+1 recovers N without consuming N+1. PLC is the fallback.
                slot.inLossBurst = false
                slot.nextSeq = next + 1
                slot.underrunConcealed = 0
                slot.recordLost(1)
                // One decoder call per gap: a FEC decode that finds no LBRR copy has already run
                // the codec's PLC internally, and a second PLC on top comes out silent.
                val following = slot.packets[next + 1]
                val pcm = if (following != null) {
                    slot.fecRecoveredCount++
                    fec(following) ?: plc()
                } else {
                    slot.plcCount++
                    plc()
                }
                return emitConcealment(slot, pcm)
            }

            // Queue is empty. The packet may still be in flight or the sender stopped.
            // Expand with Opus PLC while holding `nextSeq`, so a late packet still plays.
            slot.cleanRun = 0
            if (!slot.inLossBurst) {
                slot.underrunConcealed++
                if (slot.underrunConcealed == 1 && slot.playedReal) {
                    slot.late.incrementAndGet()
                }
                if (slot.underrunConcealed > maxFrames) {
                    slot.inLossBurst = true
                    slot.underrunConcealed = 0
                }
            }
            if (slot.inLossBurst) {
                // The stream stopped (mute, push-to-talk). Not counted as loss: that figure
                // drives the Opus encoder's bitrate and FEC, and a pause must not degrade them.
                slot.nextSeq = next + 1
            }
            return conceal(slot, plc)
        }
    }

    private fun conceal(slot: PeerSlot, plc: () -> ShortArray?): JitterFrame {
        slot.plcCount++
        return emitConcealment(slot, plc())
    }

    /**
     * Queue a concealment frame. Opus PLC/FEC is used as-is unless it came out near-silent in the
     * middle of speech (no LBRR copy, or a second loss in a row — Concentus fades consecutive PLC
     * very fast); then up to [MAX_PITCH_FILL_TICKS] concealed ticks extend the last real pitch
     * period with a fade instead of opening a hole.
     */
    private fun emitConcealment(slot: PeerSlot, codecPcm: ShortArray?): JitterFrame {
        val pcm = codecPcm?.takeIf { it.size >= frameSamples } ?: silence()
        val holeInSpeech = slot.lastRealRms >= 250.0 &&
            slot.concealRun <= MAX_PITCH_FILL_TICKS &&
            VoicePcm.rms(pcm) < max(60.0, slot.lastRealRms * 0.1)
        val chosen = if (holeInSpeech) pitchFill(slot) ?: pcm else pcm
        slot.pushFifo(chosen)
        val out = slot.popFifoFrame()
        slot.discontinuity = holeInSpeech
        return emit(slot, out, concealed = true)
    }

    private fun emit(slot: PeerSlot, frame: ShortArray, concealed: Boolean, accelerated: Boolean = false): JitterFrame {
        if (!concealed && slot.discontinuity) {
            // The decoder's state did not produce the frame just played; ease into real audio.
            rampFrom(slot.lastSample.toInt(), frame)
            slot.discontinuity = false
        }
        if (frame.isNotEmpty()) slot.lastSample = frame.last()
        if (frame.isNotEmpty() && !concealed) {
            val keep = min(frame.size, slot.lastOut.size)
            System.arraycopy(slot.lastOut, keep, slot.lastOut, 0, slot.lastOut.size - keep)
            System.arraycopy(frame, frame.size - keep, slot.lastOut, slot.lastOut.size - keep, keep)
        }
        return JitterFrame(frame, concealed = concealed, accelerated = accelerated)
    }

    /** Repeat the newest pitch period of the last real audio, fading 0.85 → 0.4 over one frame. */
    private fun pitchFill(slot: PeerSlot): ShortArray? {
        val hist = slot.lastOut
        val minPeriod = frameSamples / 20 // 2.5 ms (400 Hz)
        val maxPeriod = min(hist.size / 2, frameSamples * 3 / 4) // 15 ms (~66 Hz)
        if (maxPeriod <= minPeriod) return null
        val ref = minPeriod * 2
        val tailStart = hist.size - ref
        var bestP = 0
        var bestCorr = Double.NEGATIVE_INFINITY
        var p = minPeriod
        while (p <= maxPeriod) {
            val corr = normalizedCorrelation(hist, tailStart - p, tailStart, ref, step = 2)
            if (corr > bestCorr) {
                bestCorr = corr
                bestP = p
            }
            p += 2
        }
        if (bestP == 0 || VoicePcm.rms(hist) < 60.0) return null
        val out = ShortArray(frameSamples)
        val base = hist.size - bestP
        val runGain = Math.pow(0.7, (slot.concealRun - 1).coerceAtLeast(0).toDouble())
        for (i in 0 until frameSamples) {
            val g = (0.85 - 0.45 * i / frameSamples) * runGain
            out[i] = (hist[base + (i % bestP)] * g).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        rampFrom(slot.lastSample.toInt(), out)
        return out
    }

    private fun rampFrom(prev: Int, frame: ShortArray) {
        if (frame.isEmpty() || abs(frame[0] - prev) <= 64) return
        val ramp = min(RAMP_SAMPLES, frame.size)
        for (i in 0 until ramp) {
            val w = 0.5 * (1.0 - cos(PI * (i + 1) / (ramp + 1)))
            frame[i] = (prev * (1.0 - w) + frame[i] * w).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun adaptTarget(slot: PeerSlot) {
        // Adapt target buffer depth from measured jitter and late/underrun pressure.
        val jitterTarget = ceil(slot.jitterFrames * 2.0 + 1.0).toInt().coerceIn(minFrames, maxFrames)
        if (slot.late.get() >= 2) {
            slot.target = min(maxFrames, max(slot.target + 1, jitterTarget))
            slot.late.set(0)
            slot.cleanRun = 0
        } else if (jitterTarget > slot.target) {
            slot.target = jitterTarget
        } else if (slot.cleanRun >= TARGET_STEP_DOWN_TICKS && slot.target > max(minFrames, jitterTarget)) {
            slot.target = max(minFrames, slot.target - 1)
            slot.cleanRun = 0
        }
    }

    /** All primed senders that should be mixed this tick. */
    fun primedSenders(): List<Long> = peers.filter { it.value.primed }.keys.toList()

    /**
     * Pitch-synchronous WSOLA compression: takes [pcm1] and [pcm2] (plus any [PeerSlot.pcmFifo]
     * samples), removes one pitch-aligned period group (~720–960 samples), and pops one
     * [frameSamples] frame while keeping the remaining samples in [PeerSlot.pcmFifo].
     */
    private fun combineWithFifo(slot: PeerSlot, pcm1: ShortArray, pcm2: ShortArray): ShortArray {
        val totalLen = slot.pcmFifo.size + pcm1.size + pcm2.size
        val concat = ShortArray(totalLen)
        var pos = 0
        if (slot.pcmFifo.isNotEmpty()) {
            System.arraycopy(slot.pcmFifo, 0, concat, 0, slot.pcmFifo.size)
            pos += slot.pcmFifo.size
            slot.pcmFifo = ShortArray(0)
        }
        System.arraycopy(pcm1, 0, concat, pos, pcm1.size)
        pos += pcm1.size
        System.arraycopy(pcm2, 0, concat, pos, pcm2.size)

        if (totalLen < frameSamples * 2) {
            slot.pushFifo(concat)
            return slot.popFifoFrame()
        }
        slot.pushFifo(wsolaCompressBuffer(concat))
        return slot.popFifoFrame()
    }

    private fun wsolaCompressBuffer(concat: ShortArray): ShortArray {
        val headKeep = frameSamples / 4 // 240 samples (5 ms)
        val overlap = frameSamples / 4 // 240 samples (5 ms)
        val minShift = (frameSamples * 3) / 4 // 720 samples (15 ms)
        val maxShift = min(frameSamples, concat.size - headKeep - overlap - 1)
        if (maxShift <= minShift) return concat
        val bestShift = findBestCorrelationShift(concat, headKeep, overlap, minShift, maxShift)
        val outLen = concat.size - bestShift
        val out = ShortArray(outLen)
        System.arraycopy(concat, 0, out, 0, headKeep)
        for (i in 0 until overlap) {
            val w = 0.5 * (1.0 - cos(PI * i / overlap))
            val a = concat[headKeep + i].toDouble()
            val b = concat[headKeep + bestShift + i].toDouble()
            out[headKeep + i] = (a * (1.0 - w) + b * w).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        val tailSrc = headKeep + bestShift + overlap
        val tailDst = headKeep + overlap
        val tailLen = outLen - tailDst
        if (tailLen > 0) {
            System.arraycopy(concat, tailSrc, out, tailDst, tailLen)
        }
        return out
    }

    private fun findBestCorrelationShift(
        buf: ShortArray,
        refStart: Int,
        overlap: Int,
        minShift: Int,
        maxShift: Int,
    ): Int {
        var bestShift = maxShift
        var bestScore = Double.NEGATIVE_INFINITY
        // Coarse search by 2 samples, then refine ±1 around best.
        var s = minShift
        while (s <= maxShift) {
            val score = normalizedCorrelation(buf, refStart, refStart + s, overlap, step = 2)
            if (score > bestScore) {
                bestScore = score
                bestShift = s
            }
            s += 2
        }
        for (refine in max(minShift, bestShift - 1)..min(maxShift, bestShift + 1)) {
            val score = normalizedCorrelation(buf, refStart, refStart + refine, overlap, step = 1)
            if (score > bestScore) {
                bestScore = score
                bestShift = refine
            }
        }
        return bestShift
    }

    private fun normalizedCorrelation(
        buf: ShortArray,
        aStart: Int,
        bStart: Int,
        len: Int,
        step: Int,
    ): Double {
        var dot = 0.0
        var eA = 0.0
        var eB = 0.0
        var i = 0
        while (i < len) {
            val a = buf[aStart + i].toDouble()
            val b = buf[bStart + i].toDouble()
            dot += a * b
            eA += a * a
            eB += b * b
            i += step
        }
        val denom = kotlin.math.sqrt(eA * eB)
        return if (denom < 1e-6) 0.0 else dot / denom
    }

    private fun silence() = ShortArray(frameSamples)

    private companion object {
        /** Frames below this RMS (~−45 dBFS) are compressed first: pauses absorb the speed-up. */
        const val QUIET_RMS = 180.0
        /** Clean ticks before the target steps down one frame (~2 s). */
        const val TARGET_STEP_DOWN_TICKS = 100
        /** ~0.7 ms raised-cosine ease used at a synthetic/real seam. */
        const val RAMP_SAMPLES = 32
        /** Pitch fill covers at most 80 ms of a hole, each tick 30% quieter, then silence. */
        const val MAX_PITCH_FILL_TICKS = 4
    }
}
