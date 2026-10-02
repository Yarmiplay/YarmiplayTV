package com.yarmiplaytv.ui.player

import com.yarmiplaytv.syncplay.Constants
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.Filenames
import kotlin.math.abs

fun formatClock(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun sameFile(a: FileInfo?, b: FileInfo?): Boolean {
    if (a == null || b == null) return false
    val nameOk = Filenames.same(a.name, b.name)
    val sizeOk = a.size == 0L || b.size == 0L || a.size == b.size
    val durationOk = abs(a.duration - b.duration) < Constants.DIFFERENT_DURATION_THRESHOLD
    return nameOk && sizeOk && durationOk
}
