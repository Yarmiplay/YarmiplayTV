package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.assertEquals
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
}
