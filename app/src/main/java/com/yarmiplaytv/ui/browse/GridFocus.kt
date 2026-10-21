package com.yarmiplaytv.ui.browse

/**
 * The item one row of [rowsDelta] away from [focused] in the same column.
 * Null when that step leaves the grid, so Down on the last row and Up on the first
 * can move focus out of the grid (to the header) instead of wrapping.
 */
internal fun sameColumnTarget(focused: Int, columns: Int, rowsDelta: Int, count: Int): Int? {
    if (columns < 1 || count < 1 || focused !in 0 until count || rowsDelta == 0) return null
    val target = focused.toLong() + rowsDelta.toLong() * columns.toLong()
    if (target !in 0 until count.toLong()) return null
    return target.toInt()
}
