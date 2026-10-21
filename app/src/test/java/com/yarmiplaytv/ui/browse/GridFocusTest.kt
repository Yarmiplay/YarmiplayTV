package com.yarmiplaytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridFocusTest {
    @Test
    fun `down and up stay in the same column`() {
        assertEquals(7, sameColumnTarget(focused = 2, columns = 5, rowsDelta = 1, count = 20))
        assertEquals(2, sameColumnTarget(focused = 7, columns = 5, rowsDelta = -1, count = 20))
        // Last column of a 6-wide grid, three rows down: 5 → 11 → 17 → 23.
        var index = 5
        repeat(3) { index = sameColumnTarget(index, columns = 6, rowsDelta = 1, count = 40)!! }
        assertEquals(23, index)
    }

    @Test
    fun `a step off the grid is left to the header`() {
        assertNull(sameColumnTarget(focused = 2, columns = 5, rowsDelta = -1, count = 20))
        assertNull(sameColumnTarget(focused = 17, columns = 5, rowsDelta = 1, count = 20))
        // Column 3 has no poster on a last row that only filled column 0.
        assertNull(sameColumnTarget(focused = 8, columns = 5, rowsDelta = 1, count = 11))
        assertEquals(10, sameColumnTarget(focused = 5, columns = 5, rowsDelta = 1, count = 11))
    }

    @Test
    fun `a grid that is not laid out yet does not move`() {
        assertNull(sameColumnTarget(focused = 0, columns = 0, rowsDelta = 1, count = 20))
        assertNull(sameColumnTarget(focused = 0, columns = 5, rowsDelta = 0, count = 20))
        assertNull(sameColumnTarget(focused = -1, columns = 5, rowsDelta = 1, count = 20))
    }
}
