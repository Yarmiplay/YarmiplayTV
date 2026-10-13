package com.yarmiplaytv.tracking.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An audio fingerprint in the manner of Chromaprint (AcoustID's, MIT): mono [SAMPLE_RATE] Hz sound is cut into
 * overlapping frames, each frame's spectrum is folded into the 12 notes of the octave, and 16 classifiers that look at
 * a few seconds of those notes give one 32-bit number per [HOP_SECONDS]. The same sound gives the same numbers, or
 * numbers a few bits apart, whatever the file it is in. Only the classifiers' constants are Chromaprint's; the numbers
 * are compared only with this app's own, so they needn't match fpcalc's bit for bit.
 *
 * Feed it with [consume] as the sound is decoded, then take the fingerprint with [finish].
 */
class Fingerprinter {
    private val fft = Fft(FRAME_SIZE)
    private val frame = DoubleArray(FRAME_SIZE)
    private var filled = 0
    private val windowed = DoubleArray(FRAME_SIZE)
    private val power = DoubleArray(FRAME_SIZE / 2 + 1)
    /** The last [FILTER.size] frames' chroma, oldest first, for the smoothing filter. */
    private val recent = ArrayDeque<DoubleArray>()
    /** The smoothed and normalized chroma of every frame so far, [BANDS] values each. */
    private val rows = ArrayList<DoubleArray>()

    /** Adds the first [count] samples of [pcm] (mono, [SAMPLE_RATE] Hz). */
    fun consume(pcm: ShortArray, count: Int = pcm.size) {
        var i = 0
        while (i < count) {
            val n = minOf(FRAME_SIZE - filled, count - i)
            for (k in 0 until n) frame[filled + k] = pcm[i + k].toDouble()
            filled += n
            i += n
            if (filled == FRAME_SIZE) {
                processFrame()
                System.arraycopy(frame, HOP, frame, 0, FRAME_SIZE - HOP)
                filled = FRAME_SIZE - HOP
            }
        }
    }

    /** The fingerprint of everything consumed: one value per [HOP_SECONDS], the first for the first frame. */
    fun finish(): IntArray {
        if (rows.size < MAX_WIDTH) return IntArray(0)
        val image = IntegralImage(rows)
        return IntArray(rows.size - MAX_WIDTH + 1) { x ->
            var bits = 0
            for (c in CLASSIFIERS) bits = (bits shl 2) or GRAY[c.classify(image, x)]
            bits
        }
    }

    private fun processFrame() {
        for (i in 0 until FRAME_SIZE) windowed[i] = frame[i] * WINDOW[i]
        fft.powerSpectrum(windowed, power)
        val chroma = DoubleArray(BANDS)
        for (i in MIN_INDEX until MAX_INDEX) chroma[NOTES[i]] += power[i]
        recent.addLast(chroma)
        if (recent.size > FILTER.size) recent.removeFirst()
        if (recent.size < FILTER.size) return
        val smoothed = DoubleArray(BANDS)
        recent.forEachIndexed { j, row -> for (b in 0 until BANDS) smoothed[b] += FILTER[j] * row[b] }
        val norm = sqrt(smoothed.sumOf { it * it })
        if (norm < 0.01) smoothed.fill(0.0) else for (b in 0 until BANDS) smoothed[b] /= norm
        rows += smoothed
    }

    /** Sums of rectangles of the chroma rows: time from row x, notes from column y. */
    private class IntegralImage(rows: List<DoubleArray>) {
        private val width = BANDS + 1
        private val sums = DoubleArray((rows.size + 1) * width)

        init {
            for (r in rows.indices) {
                var line = 0.0
                for (c in 0 until BANDS) {
                    line += rows[r][c]
                    sums[(r + 1) * width + c + 1] = sums[r * width + c + 1] + line
                }
            }
        }

        fun area(x1: Int, y1: Int, x2: Int, y2: Int): Double =
            sums[x2 * width + y2] - sums[x1 * width + y2] - sums[x2 * width + y1] + sums[x1 * width + y1]
    }

    /** One of the 16 two-bit features: a [type] of rectangle comparison over [width] frames and [height] notes from note [y]. */
    private class Classifier(
        val type: Int, val y: Int, val height: Int, val width: Int,
        val t0: Double, val t1: Double, val t2: Double,
    ) {
        fun classify(image: IntegralImage, x: Int): Int {
            val v = when (type) {
                0 -> cmp(image.area(x, y, x + width, y + height), 0.0)
                1 -> {
                    val h2 = height / 2
                    cmp(image.area(x, y + h2, x + width, y + height), image.area(x, y, x + width, y + h2))
                }
                2 -> {
                    val w2 = width / 2
                    cmp(image.area(x + w2, y, x + width, y + height), image.area(x, y, x + w2, y + height))
                }
                3 -> {
                    val w2 = width / 2
                    val h2 = height / 2
                    cmp(
                        image.area(x, y + h2, x + w2, y + height) + image.area(x + w2, y, x + width, y + h2),
                        image.area(x, y, x + w2, y + h2) + image.area(x + w2, y + h2, x + width, y + height),
                    )
                }
                4 -> {
                    val h3 = height / 3
                    cmp(
                        image.area(x, y + h3, x + width, y + 2 * h3),
                        image.area(x, y, x + width, y + h3) + image.area(x, y + 2 * h3, x + width, y + height),
                    )
                }
                else -> {
                    val w3 = width / 3
                    cmp(
                        image.area(x + w3, y, x + 2 * w3, y + height),
                        image.area(x, y, x + w3, y + height) + image.area(x + 2 * w3, y, x + width, y + height),
                    )
                }
            }
            return if (v < t1) (if (v < t0) 0 else 1) else (if (v < t2) 2 else 3)
        }

        private fun cmp(a: Double, b: Double): Double = ln((1.0 + a) / (1.0 + b))
    }

    companion object {
        const val SAMPLE_RATE = 11025
        private const val FRAME_SIZE = 4096
        private const val HOP = FRAME_SIZE - FRAME_SIZE * 2 / 3
        /** Seconds between fingerprint values. */
        const val HOP_SECONDS = HOP.toDouble() / SAMPLE_RATE
        private const val BANDS = 12
        private const val MIN_FREQ = 28.0
        private const val MAX_FREQ = 3520.0

        private val WINDOW = DoubleArray(FRAME_SIZE) { (0.54 - 0.46 * cos(it * 2.0 * PI / (FRAME_SIZE - 1))) / Short.MAX_VALUE }
        private val MIN_INDEX = maxOf(1, (FRAME_SIZE * MIN_FREQ / SAMPLE_RATE).roundToInt())
        private val MAX_INDEX = minOf(FRAME_SIZE / 2, (FRAME_SIZE * MAX_FREQ / SAMPLE_RATE).roundToInt())
        /** Which of the 12 notes each spectrum bin falls in (A at 440 Hz is note 0). */
        private val NOTES = IntArray(FRAME_SIZE / 2 + 1) { i ->
            if (i < MIN_INDEX || i >= MAX_INDEX) 0 else {
                val octave = ln(i.toDouble() * SAMPLE_RATE / FRAME_SIZE / (440.0 / 16.0)) / ln(2.0)
                (BANDS * (octave - floor(octave))).toInt().coerceIn(0, BANDS - 1)
            }
        }
        private val FILTER = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)
        private val GRAY = intArrayOf(0, 1, 3, 2)

        /** Chromaprint's "test2" classifiers (its default algorithm): type, y, height, width, then the quantizer's thresholds. */
        private val CLASSIFIERS = listOf(
            Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
            Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
            Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
            Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
            Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
            Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
            Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
            Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
            Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
            Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
            Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
            Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
            Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
            Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
            Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
            Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
        )
        private val MAX_WIDTH = CLASSIFIERS.maxOf { it.width }

        /** The value silence gets (no notes at all); it says nothing about which sound this is. */
        val SILENCE: Int = run {
            val image = IntegralImage(List(MAX_WIDTH) { DoubleArray(BANDS) })
            var bits = 0
            for (c in CLASSIFIERS) bits = (bits shl 2) or GRAY[c.classify(image, 0)]
            bits
        }
    }
}
