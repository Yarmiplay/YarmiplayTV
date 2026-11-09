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
 * Per-sender adaptive jitter buffer (~20–100 ms) with:
 * - Opus in-band FEC decoding of packet `N+1` when packet `N` is lost
 * - Opus PLC + pitch-synchronous waveform extrapolation fallback on gaps
 * - Underrun wait handling so packets delayed by 30–80 ms jitter are played when they arrive
 *   instead of being skipped as "late"
 * - Pitch-synchronous WSOLA time-compression (accelerate) when queue depth exceeds target,
 *   keeping mouth-to-ear latency low without clicks or robotic artifacts
 * - Receiver loss and inter-arrival jitter telemetry for Opus encoder adaptation
 *
 * Senders that stop delivering packets are pruned after [maxConcealment] consecutive concealed
 * ticks so leave/rejoin watch parties do not keep running Opus PLC forever.
 */
class VoiceJitterBuffer(
    private val frameSamples: Int = VoicePcm.frameSamples,
    private val minFrames: Int = 2, // 40 ms default minimum
    private val maxFrames: Int = 5, // 100 ms default maximum
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
        /** How many concealment frames were already emitted while waiting on an empty queue. */
        var underrunConcealed = 0
        /** Logical 20 ms tick counter incremented on each [poll] and wall-clock arrival tracking. */
        var pollTick: Long = 0L
        var lastPushTick: Long = -1L
        var lastPushNano: Long = 0L
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

        /** Leftover pitch-aligned PCM samples from WSOLA time-compression. */
        var pcmFifo = ShortArray(0)
        /** Recent output history (2 frames = 40 ms) for boundary de-clicking and pitch-synchronous PLC. */
        val history = ShortArray(frameSamples * 2)
        var historyFilled = 0
        var lastSample: Short = 0
        var lastRealRms: Double = 0.0
        var wasConcealed: Boolean = false

        fun appendHistory(frame: ShortArray) {
            if (frame.isEmpty()) return
            val n = min(frame.size, history.size)
            val keep = history.size - n
            if (keep > 0) {
                System.arraycopy(history, history.size - keep, history, 0, keep)
            }
            System.arraycopy(frame, frame.size - n, history, keep, n)
            historyFilled = min(history.size, historyFilled + n)
            lastSample = frame.last()
        }

        fun pushFifo(samples: ShortArray) {
            if (samples.isEmpty()) return
            val next = ShortArray(pcmFifo.size + samples.size)
            System.arraycopy(pcmFifo, 0, next, 0, pcmFifo.size)
            System.arraycopy(samples, 0, next, pcmFifo.size, samples.size)
            pcmFifo = next
        }

        fun popFifoFrame(): ShortArray? {
            if (pcmFifo.size < frameSamples) return null
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
            if (slot.lastPushSeq != Int.MIN_VALUE) {
                val seqDelta = (sequence - slot.lastPushSeq).coerceIn(-16, 16)
                if (seqDelta != 0) {
                    val arrivalFrames = when {
                        arrivalTimeMs >= 0L && slot.lastPushNano >= 0L ->
                            (arrivalTimeMs - slot.lastPushNano) / 20.0
                        slot.pollTick != slot.lastPushTick ->
                            (slot.pollTick - slot.lastPushTick).toDouble()
                        else ->
                            ((nowNano - slot.lastPushNano) / 20_000_000.0).coerceIn(0.0, 10.0)
                    }
                    val transitDiff = abs(arrivalFrames - seqDelta.toDouble())
                    slot.jitterFrames += (transitDiff - slot.jitterFrames) / 8.0
                }
            }
            slot.lastPushSeq = sequence
            slot.lastPushTick = slot.pollTick
            slot.lastPushNano = if (arrivalTimeMs >= 0L) arrivalTimeMs else nowNano

            val next = slot.nextSeq
            if (next != null && sequence < next) {
                // Packet arrived after playout already passed its sequence number.
                if (sequence >= next - maxFrames * 2) {
                    slot.late.incrementAndGet()
                    slot.cleanRun = 0
                }
                return
            }
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
     * Decode the next frame for [sender] into PCM (or FEC/PLC). Returns null if not primed yet,
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
            slot.pollTick++
            if (slot.accelerateCooldown > 0) slot.accelerateCooldown--

            // Adapt target buffer depth from measured jitter and late/underrun pressure.
            val jitterTarget = ceil(slot.jitterFrames + 1.15).toInt().coerceIn(minFrames, maxFrames)
            if (slot.late.get() >= 2) {
                slot.target = min(maxFrames, max(slot.target + 1, jitterTarget))
                slot.late.set(0)
                slot.cleanRun = 0
            } else if (jitterTarget > slot.target) {
                slot.target = jitterTarget
            } else if (slot.cleanRun >= 25 && slot.target > max(minFrames, jitterTarget)) {
                slot.target = max(minFrames, slot.target - 1)
                slot.cleanRun = 0
            }

            var next = slot.nextSeq ?: slot.packets.firstKey().also { slot.nextSeq = it }
            // Prune any stale entries older than next.
            if (slot.packets.isNotEmpty() && slot.packets.firstKey() < next) {
                slot.packets.headMap(next, false).clear()
            }

            // If we previously emitted concealment frames during an empty-queue underrun wait,
            // and the next available packet is ahead of `next`, account for the already-played gap
            // so we don't conceal the same lost packet twice.
            if (slot.underrunConcealed > 0 && slot.packets.isNotEmpty()) {
                val firstAvail = slot.packets.firstKey()
                if (firstAvail > next) {
                    val skipped = min(firstAvail - next, slot.underrunConcealed)
                    slot.recordLost(skipped)
                    next += skipped
                    slot.nextSeq = next
                    slot.underrunConcealed -= skipped
                }
            }

            val packet = slot.packets.remove(next)
            if (packet != null) {
                slot.concealRun = 0
                slot.underrunConcealed = 0
                slot.recordReceived(1)
                slot.cleanRun++
                val pcm1 = decode(packet) ?: silence()
                slot.lastRealRms = VoicePcm.rms(pcm1)

                // Check if latency has built up above target and we can WSOLA-accelerate with next+1.
                val canAccelerate = slot.packets.size > slot.target &&
                    slot.accelerateCooldown == 0 &&
                    slot.packets.containsKey(next + 1)
                if (canAccelerate) {
                    val packet2 = slot.packets.remove(next + 1)
                    if (packet2 != null) {
                        slot.nextSeq = next + 2
                        slot.recordReceived(1)
                        val pcm2 = decode(packet2) ?: silence()
                        slot.lastRealRms = VoicePcm.rms(pcm2)
                        slot.accelerateCooldown = 3
                        slot.acceleratedCount++
                        val combined = combineWithFifo(slot, pcm1, pcm2)
                        val out = smoothBoundaryIfRecovered(slot, combined)
                        slot.wasConcealed = false
                        slot.appendHistory(out)
                        return JitterFrame(out, concealed = false, accelerated = true)
                    }
                }

                slot.nextSeq = next + 1
                slot.pushFifo(pcm1)
                val popped = slot.popFifoFrame() ?: pcm1
                val out = smoothBoundaryIfRecovered(slot, popped)
                slot.wasConcealed = false
                slot.appendHistory(out)
                return JitterFrame(out, concealed = false)
            }

            // `next` is not in `slot.packets`.
            slot.concealRun++
            if (slot.concealRun >= maxConcealment && slot.packets.isEmpty()) {
                peers.remove(sender, slot)
                return null
            }

            val hasNewerPacket = slot.packets.isNotEmpty()
            if (hasNewerPacket) {
                // Genuine gap (packet `next` was lost or reordered behind `next + 1`).
                slot.nextSeq = next + 1
                slot.underrunConcealed = 0
                slot.recordLost(1)
                val following = slot.packets[next + 1]
                if (following != null) {
                    val fecPcm = fec(following)
                    if (fecPcm != null && isUsableConcealment(slot, fecPcm)) {
                        slot.fecRecoveredCount++
                        val out = smoothConcealedFrame(slot, fecPcm)
                        slot.wasConcealed = true
                        slot.appendHistory(out)
                        return JitterFrame(out, concealed = true)
                    }
                }
                slot.plcCount++
                val plcPcm = plc()
                val out = buildConcealedFrame(slot, plcPcm)
                slot.wasConcealed = true
                slot.appendHistory(out)
                return JitterFrame(out, concealed = true)
            } else {
                // Queue is temporarily empty: `next` may still be in flight (jitter delay) or lost.
                // Hold `nextSeq = next` for up to `maxFrames` ticks so a jitter-delayed packet is
                // still played when it arrives, and nudge target depth upward.
                slot.underrunConcealed++
                slot.cleanRun = 0
                if (slot.underrunConcealed == 1 && slot.historyFilled > 0) {
                    slot.late.incrementAndGet()
                }
                if (slot.underrunConcealed > maxFrames) {
                    slot.nextSeq = next + 1
                    slot.underrunConcealed = 0
                    slot.recordLost(1)
                }
                slot.plcCount++
                val plcPcm = plc()
                val out = buildConcealedFrame(slot, plcPcm)
                slot.wasConcealed = true
                slot.appendHistory(out)
                return JitterFrame(out, concealed = true)
            }
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
            val compressed = wsolaCompressTwoFrames(pcm1, pcm2)
            return compressed
        }

        val compressed = wsolaCompressBuffer(concat)
        slot.pushFifo(compressed)
        return slot.popFifoFrame() ?: compressed.copyOf(frameSamples)
    }

    private fun wsolaCompressBuffer(concat: ShortArray): ShortArray {
        val headKeep = frameSamples / 4 // 240 samples (5 ms)
        val overlap = frameSamples / 4 // 240 samples (5 ms)
        val minShift = (frameSamples * 3) / 4 // 720 samples (15 ms)
        val maxShift = min(frameSamples, concat.size - headKeep - overlap - 1)
        if (maxShift <= minShift) {
            return wsolaCompressTwoFrames(
                concat.copyOfRange(0, frameSamples),
                concat.copyOfRange(concat.size - frameSamples, concat.size),
            )
        }

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

    private fun wsolaCompressTwoFrames(frame1: ShortArray, frame2: ShortArray): ShortArray {
        val n = frameSamples
        val out = ShortArray(n)
        val keep = n / 4
        val overlap = n - keep * 2
        System.arraycopy(frame1, 0, out, 0, keep)
        for (i in 0 until overlap) {
            val w = 0.5 * (1.0 - cos(PI * i / overlap.coerceAtLeast(1)))
            val a = frame1[keep + i].toDouble()
            val b = frame2[keep + i].toDouble()
            out[keep + i] = (a * (1.0 - w) + b * w).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        System.arraycopy(frame2, n - keep, out, n - keep, keep)
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

    private fun isUsableConcealment(slot: PeerSlot, candidate: ShortArray): Boolean {
        if (candidate.size < frameSamples) return false
        if (slot.lastRealRms > 250.0) {
            val rms = VoicePcm.rms(candidate)
            if (rms < 2.0) return false
        }
        return true
    }

    /**
     * Build a concealed 20 ms frame from [plcPcm] (or pitch-synchronous WSOLA extrapolation
     * of [PeerSlot.history] when [plcPcm] is empty/silent during active speech).
     */
    private fun buildConcealedFrame(slot: PeerSlot, plcPcm: ShortArray?): ShortArray {
        if (plcPcm != null && plcPcm.size == frameSamples) {
            val hasSignal = plcPcm.any { it != 0.toShort() }
            if (hasSignal || slot.lastRealRms <= 250.0 || slot.historyFilled < frameSamples) {
                return smoothConcealedFrame(slot, plcPcm)
            }
        }
        if (slot.historyFilled >= frameSamples && slot.lastRealRms > 250.0 && slot.concealRun <= 6) {
            val extrapolated = extrapolatePitchPeriod(slot)
            return smoothConcealedFrame(slot, extrapolated)
        }
        return plcPcm?.takeIf { it.size == frameSamples } ?: silence()
    }

    /**
     * Extrapolate one [frameSamples] frame by repeating the detected fundamental pitch period
     * from [PeerSlot.history] with gentle decay so a missing packet during voiced speech has
     * no metallic click or sudden dropout.
     */
    private fun extrapolatePitchPeriod(slot: PeerSlot): ShortArray {
        val hist = slot.history
        val n = slot.historyFilled
        val minPeriod = max(40, frameSamples / 20) // ~1000 Hz max pitch
        val maxPeriod = min(n / 2, (frameSamples * 3) / 4) // ~66 Hz min pitch
        val period = if (maxPeriod > minPeriod) {
            val tailStart = n - minPeriod
            var bestP = frameSamples / 4
            var bestCorr = Double.NEGATIVE_INFINITY
            var p = minPeriod
            while (p <= maxPeriod) {
                val corr = normalizedCorrelation(hist, tailStart - p, tailStart, minPeriod, step = 2)
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestP = p
                }
                p += 2
            }
            bestP
        } else {
            min(n, frameSamples)
        }

        val decay = (0.88 - (slot.concealRun - 1) * 0.12).coerceIn(0.25, 0.88)
        val out = ShortArray(frameSamples)
        val srcBase = n - period
        for (i in 0 until frameSamples) {
            val sample = hist[srcBase + (i % period)]
            out[i] = (sample * decay).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun smoothConcealedFrame(slot: PeerSlot, frame: ShortArray): ShortArray {
        if (slot.historyFilled == 0 || frame.isEmpty()) return frame
        val prev = slot.lastSample.toInt()
        val first = frame[0].toInt()
        if (abs(first - prev) <= 64) return frame
        val out = frame.copyOf()
        val ramp = min(32, out.size)
        for (i in 0 until ramp) {
            val w = 0.5 * (1.0 - cos(PI * (i + 1) / (ramp + 1)))
            out[i] = (prev * (1.0 - w) + out[i] * w).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun smoothBoundaryIfRecovered(slot: PeerSlot, frame: ShortArray): ShortArray {
        if (!slot.wasConcealed || slot.historyFilled == 0 || frame.isEmpty()) return frame
        return smoothConcealedFrame(slot, frame)
    }

    private fun silence() = ShortArray(frameSamples)
}
