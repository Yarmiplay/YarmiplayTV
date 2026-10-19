package com.yarmiplaytv.player

import kotlin.math.roundToInt

/**
 * mpv's `sub-scale`. The default keyboard bindings add or subtract [STEP] (`z` smaller, `Z` larger), which is
 * 10% of the normal size.
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
