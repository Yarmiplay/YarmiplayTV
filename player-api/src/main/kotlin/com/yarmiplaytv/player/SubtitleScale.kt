package com.yarmiplaytv.player

import kotlin.math.roundToInt

/**
 * mpv's `sub-scale`. mpv's G and F add or subtract [STEP], which is 10% of the normal size.
 * z and Z adjust subtitle delay, not this.
 */
object SubtitleScale {
    const val DEFAULT = 1.0
    const val STEP = 0.1
    const val MIN = 0.1
    const val MAX = 10.0

    fun adjust(current: Double, delta: Double): Double {
        val base = current.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT
        return (base + delta).coerceIn(MIN, MAX)
    }

    fun percent(scale: Double): Int = (scale * 100.0).roundToInt()

    fun label(scale: Double): String = "Subtitles: ${percent(scale)}%"
}
