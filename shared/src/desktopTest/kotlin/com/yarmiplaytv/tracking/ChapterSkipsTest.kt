package com.yarmiplaytv.tracking

import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterSkipsTest {
    private fun opening(start: Double, end: Double) = SkipSegment(SkipSegment.Kind.OPENING, start, end)
    private fun ending(start: Double, end: Double) = SkipSegment(SkipSegment.Kind.ENDING, start, end)

    @Test
    fun `the usual chapter names mark the opening and ending, each up to the next chapter`() {
        val chapters = listOf(Chapter("Intro", 0.0), Chapter("Part A", 90.0), Chapter("Ending Credits", 1330.0), Chapter("Next Episode", 1420.0))
        assertEquals(listOf(opening(0.0, 90.0), ending(1330.0, 1420.0)), ChapterSkips.from(chapters, 1440.0))
        for (name in listOf("OP", "Opening", "OP2", "Opening Song", "opening-theme", "オープニング")) {
            assertEquals(name, listOf(opening(30.0, 120.0)), ChapterSkips.from(listOf(Chapter("Avant", 0.0), Chapter(name, 30.0), Chapter("Part A", 120.0)), 1440.0))
        }
        assertEquals("an ending at the very end runs to the end of the file", listOf(ending(1350.0, 1440.0)), ChapterSkips.from(listOf(Chapter("Part B", 700.0), Chapter("ED", 1350.0)), 1440.0))
    }

    @Test
    fun `other chapters, and ones too short or too long to be an opening, aren't taken`() {
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.from(listOf(Chapter("Chapter 1", 0.0), Chapter("Chapter 2", 600.0)), 1440.0))
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.from(listOf(Chapter("Opening", 0.0), Chapter("Part A", 4.0)), 1440.0))
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.from(listOf(Chapter("Opening", 0.0), Chapter("Part A", 900.0)), 1440.0))
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.from(listOf(Chapter(null, 0.0), Chapter("Opening of the war", 30.0), Chapter("x", 120.0)), 1440.0))
    }
}
