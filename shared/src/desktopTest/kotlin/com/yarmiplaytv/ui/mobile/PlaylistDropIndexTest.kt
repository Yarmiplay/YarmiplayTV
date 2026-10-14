package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDropIndexTest {
    private fun row(index: Int, offset: Int, size: Int = 40) = object : LazyListItemInfo {
        override val index = index
        override val key: Any = index
        override val offset = offset
        override val size = size
    }

    @Test
    fun `files go before the row whose upper half is under the pointer`() {
        val rows = listOf(row(0, 0), row(1, 40), row(2, 80))
        assertEquals(0, playlistDropIndex(5f, 400, rows, 3))
        assertEquals(1, playlistDropIndex(25f, 400, rows, 3))
        assertEquals(2, playlistDropIndex(99f, 400, rows, 3))
        assertEquals(3, playlistDropIndex(101f, 400, rows, 3))
        assertEquals(3, playlistDropIndex(300f, 400, rows, 3))
    }

    @Test
    fun `a scrolled list counts from the rows it shows`() {
        val rows = listOf(row(10, -15), row(11, 25), row(12, 65), row(13, 105))
        assertEquals(10, playlistDropIndex(0f, 120, rows, 30))
        assertEquals(11, playlistDropIndex(10f, 120, rows, 30))
        assertEquals(13, playlistDropIndex(119f, 120, rows, 30))
        assertEquals(13, playlistDropIndex(119f, 120, rows.dropLast(1), 30))
    }

    @Test
    fun `outside the list or an empty list appends`() {
        val rows = listOf(row(0, 0), row(1, 40))
        assertEquals(2, playlistDropIndex(-10f, 400, rows, 2))
        assertEquals(2, playlistDropIndex(401f, 400, rows, 2))
        assertEquals(0, playlistDropIndex(50f, 400, emptyList(), 0))
    }

    @Test
    fun `a dragged row lands on the row its middle is over, however tall the rows are`() {
        // Row 1's long name takes three lines.
        val rows = listOf(row(0, 0), row(1, 40, size = 100), row(2, 140), row(3, 180))
        assertEquals(0, playlistDragTarget(0, 15f, rows, 3))
        assertEquals(1, playlistDragTarget(0, 30f, rows, 3))
        assertEquals(1, playlistDragTarget(0, 110f, rows, 3))
        assertEquals(2, playlistDragTarget(0, 130f, rows, 3))
        assertEquals(0, playlistDragTarget(3, -190f, rows, 3))
        assertEquals(3, playlistDragTarget(0, 900f, rows, 3))
        assertEquals(2, playlistDragTarget(2, 0f, rows, 3))
    }
}

class LastClickTest {
    @Test
    fun `a second click on the same row soon after is a double click`() {
        var now = 1_000L
        val clicks = LastClick { now }
        assertFalse(clicks.isSecond(2, 300))
        now += 200
        assertTrue(clicks.isSecond(2, 300))
        // A third click starts a new pair.
        now += 100
        assertFalse(clicks.isSecond(2, 300))
        now += 400
        assertFalse("too slow", clicks.isSecond(2, 300))
        now += 100
        assertFalse("another row", clicks.isSecond(3, 300))
    }
}
