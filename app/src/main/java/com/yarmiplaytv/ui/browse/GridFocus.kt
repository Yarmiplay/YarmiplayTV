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

/**
 * How far to scroll (positive = down) so an item of [itemHeight] with top [itemTop] lies fully
 * inside the viewport. [edgePadding] keeps a little air around a focused, scaled tile.
 * When the item is taller than the viewport, pin it to the start (or end when it overshoots below).
 */
internal fun scrollDeltaToShowFully(
    itemTop: Int,
    itemHeight: Int,
    viewportStart: Int,
    viewportEnd: Int,
    edgePadding: Int = 0,
): Int {
    if (itemHeight <= 0) return 0
    val start = viewportStart + edgePadding
    val end = viewportEnd - edgePadding
    if (end <= start) return 0
    val itemBottom = itemTop + itemHeight
    val viewH = end - start
    if (itemHeight >= viewH) {
        return when {
            itemTop > start -> itemTop - start
            itemBottom < end -> itemBottom - end
            else -> 0
        }
    }
    if (itemTop < start) return itemTop - start
    if (itemBottom > end) return itemBottom - end
    return 0
}
