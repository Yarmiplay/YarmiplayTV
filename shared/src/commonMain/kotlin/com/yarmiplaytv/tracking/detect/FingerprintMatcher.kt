package com.yarmiplaytv.tracking.detect

import kotlin.math.roundToInt

/** A stretch of sound two fingerprints share: points [aStart] until [aEnd] of one are [bStart] until [bEnd] of the other. */
data class SharedRun(val aStart: Int, val aEnd: Int, val bStart: Int, val bEnd: Int) {
    val length: Int get() = aEnd - aStart
}

/**
 * Finds the sound two episodes share, such as their opening song. Values that are equal or nearly so in both
 * fingerprints vote for how far one is shifted against the other; along each well-voted shift, points a few bits apart
 * count as the same, and a run of them may skip short stretches that differ (a sound effect over the song, a line of
 * dialogue). Silence is never counted: every silence looks the same.
 */
object FingerprintMatcher {
    /** Points at most this many bits apart are the same sound. */
    const val MAX_BIT_ERRORS = 6
    /**
     * A run carries on over this long a stretch that doesn't match: a point hears about 2.7 s, so a 2 s sound over the
     * song spoils about 4.5 s of points.
     */
    const val MAX_GAP_SECONDS = 5.0
    /** At most this many shifts are walked, the best voted first. */
    private const val SHIFTS = 24
    /** A value found this often in the other fingerprint (a held note, a hum) says too little to vote. */
    private const val COMMON = 64
    /** Of a run's points, at least this share match. */
    private const val MIN_DENSITY = 0.4
    /** A run's first and last match have at least [MIN_SUPPORT] matches among the [SUPPORT] points from them inward. */
    private const val SUPPORT = 8
    private const val MIN_SUPPORT = 4

    /**
     * The shared runs from [minPoints] to [maxPoints] long, the longest first, none overlapping another in [a]; at
     * most [limit].
     */
    fun runs(a: IntArray, b: IntArray, minPoints: Int, maxPoints: Int, limit: Int = 3): List<SharedRun> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val index = HashMap<Int, MutableList<Int>>()
        for (j in b.indices) if (b[j] != Fingerprinter.SILENCE) index.getOrPut(b[j]) { ArrayList(2) } += j
        val votes = HashMap<Int, Int>()
        for (i in a.indices) {
            if (a[i] == Fingerprinter.SILENCE) continue
            for (d in -2..2) {
                val at = index[a[i] + d] ?: continue
                if (at.size > COMMON) continue
                for (j in at) votes.merge(j - i, 1, Int::plus)
            }
        }
        val gap = (MAX_GAP_SECONDS / Fingerprinter.HOP_SECONDS).roundToInt()
        val found = votes.entries.asSequence().filter { it.value >= 2 }.sortedByDescending { it.value }.take(SHIFTS)
            .flatMap { runsAt(a, b, it.key, gap) }
            .filter { it.length in minPoints..maxPoints }
            .sortedByDescending { it.length }
            .toList()
        val kept = ArrayList<SharedRun>()
        for (run in found) {
            if (kept.none { overlap(it, run) > 0 }) kept += run
            if (kept.size == limit) break
        }
        return kept
    }

    /**
     * Every run along [shift] (b's index minus a's). Its ends are matches with others close by: a lone chance match
     * a few seconds off doesn't stretch it.
     */
    private fun runsAt(a: IntArray, b: IntArray, shift: Int, gap: Int): List<SharedRun> {
        val from = maxOf(0, -shift)
        val n = minOf(a.size, b.size - shift) - from
        if (n <= 0) return emptyList()
        val hits = IntArray(n + 1)
        for (k in 0 until n) {
            val x = a[from + k]
            val y = b[from + k + shift]
            val hit = x != Fingerprinter.SILENCE && y != Fingerprinter.SILENCE && Integer.bitCount(x xor y) <= MAX_BIT_ERRORS
            hits[k + 1] = hits[k] + if (hit) 1 else 0
        }
        fun hit(k: Int) = hits[k + 1] > hits[k]
        fun count(lo: Int, hi: Int) = hits[hi.coerceIn(0, n)] - hits[lo.coerceIn(0, n)]
        val runs = ArrayList<SharedRun>()
        var k = 0
        while (k < n) {
            if (!hit(k)) {
                k++
                continue
            }
            var last = k
            var next = k + 1
            while (next < n && next - last <= gap) {
                if (hit(next)) last = next
                next++
            }
            var start = k
            var end = last
            while (start <= end && (!hit(start) || count(start, start + SUPPORT) < MIN_SUPPORT)) start++
            while (end >= start && (!hit(end) || count(end - SUPPORT + 1, end + 1) < MIN_SUPPORT)) end--
            if (end >= start && count(start, end + 1) >= (end + 1 - start) * MIN_DENSITY) {
                runs += SharedRun(from + start, from + end + 1, from + start + shift, from + end + 1 + shift)
            }
            k = last + 1
        }
        return runs
    }

    /**
     * Seconds into the fingerprinted sound at which a run starting at [point] starts: a point hears about 2.7 s from its
     * own time, so it matches a little before the shared sound begins.
     */
    fun startSeconds(point: Int): Double = point * Fingerprinter.HOP_SECONDS + START_OFFSET_SECONDS

    /** Seconds into the fingerprinted sound at which a run ending before [point] ends: a point hears a few seconds on. */
    fun endSeconds(point: Int): Double = point * Fingerprinter.HOP_SECONDS + END_OFFSET_SECONDS

    private const val START_OFFSET_SECONDS = 1.1
    private const val END_OFFSET_SECONDS = 0.95

    private fun overlap(x: SharedRun, y: SharedRun): Int = minOf(x.aEnd, y.aEnd) - maxOf(x.aStart, y.aStart)
}
