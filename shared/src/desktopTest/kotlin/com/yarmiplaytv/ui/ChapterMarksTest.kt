package com.yarmiplaytv.ui

import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.ui.mobile.chapterBoundaries
import com.yarmiplaytv.ui.mobile.chapterTitleAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMarksTest {
    @Test
    fun `a file with no chapters has no marks`() {
        assertTrue(chapterBoundaries(emptyList(), 1440.0).isEmpty())
        assertTrue(chapterBoundaries(listOf(Chapter("Cold open", 0.0)), 0.0).isEmpty())
        assertTrue(chapterBoundaries(listOf(Chapter("Cold open", 0.0)), Double.NaN).isEmpty())
        assertNull(chapterTitleAt(emptyList(), 10.0))
    }

    @Test
    fun `out of range and untitled chapters`() {
        val marks = chapterBoundaries(
            listOf(
                Chapter("Later", 80.0),
                Chapter("Early", 10.0),
                Chapter("Before", -1.0),
                Chapter("After", 150.0),
                Chapter("Bad", Double.NaN),
                Chapter("Infinite", Double.POSITIVE_INFINITY),
                Chapter("   ", 40.0),
                Chapter(null, 40.02),
            ),
            100.0,
        )
        assertEquals(listOf(10.0, 40.0, 80.0), marks.map { it.time })
        assertEquals(listOf("Early", "Chapter 2", "Later"), marks.map { it.label })
        assertNull("before the first chapter", chapterTitleAt(marks, 9.0))
        assertEquals("Early", chapterTitleAt(marks, 10.0))
        assertEquals("Early", chapterTitleAt(marks, 39.0))
        assertEquals("Chapter 2", chapterTitleAt(marks, 40.0))
        assertEquals("Later", chapterTitleAt(marks, 100.0))
    }

    @Test
    fun `duplicate starts share one mark and keep both names`() {
        val marks = chapterBoundaries(
            listOf(
                Chapter("Intro", 10.0),
                Chapter("Intro", 10.02),
                Chapter("", 10.03),
                Chapter("Cold open", 10.04),
                Chapter("Part A", 30.0),
            ),
            100.0,
        )
        assertEquals(listOf(10.0, 30.0), marks.map { it.time })
        assertEquals("Intro · Cold open", marks[0].label)
        assertEquals("Part A", marks[1].label)
    }

    @Test
    fun `replacing the file's chapters does not keep the previous names`() {
        val first = chapterBoundaries(listOf(Chapter("Old", 1.0), Chapter("Older", 5.0)), 10.0)
        val next = chapterBoundaries(listOf(Chapter("New", 2.0)), 10.0)
        assertEquals("Old", chapterTitleAt(first, 3.0))
        assertEquals("New", chapterTitleAt(next, 3.0))
        assertNull(chapterTitleAt(chapterBoundaries(emptyList(), 10.0), 3.0))
    }
}
