package com.yarmiplaytv.tracking.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An in-place radix-2 FFT of [size] points (a power of two), for the power spectrum of real samples. */
internal class Fft(private val size: Int) {
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Writes |X(k)|² of [input] (length [size]) for k = 0..size/2 into [power]. */
    fun powerSpectrum(input: DoubleArray, power: DoubleArray) {
        for (i in 0 until size) {
            re[reversed[i]] = input[i]
            im[reversed[i]] = 0.0
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
        for (k in 0..size / 2) power[k] = re[k] * re[k] + im[k] * im[k]
    }
}
