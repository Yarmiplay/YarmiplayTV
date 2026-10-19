package com.yarmiplaytv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleScaleTest {
    @Test
    fun zAndZStepByATenthAndStayInRange() {
        assertEquals(0.9, SubtitleScale.adjust(1.0, -SubtitleScale.STEP), 0.0001)
        assertEquals(1.1, SubtitleScale.adjust(1.0, SubtitleScale.STEP), 0.0001)
        assertEquals(SubtitleScale.MIN, SubtitleScale.adjust(0.1, -1.0), 0.0001)
        assertEquals(SubtitleScale.MAX, SubtitleScale.adjust(10.0, 1.0), 0.0001)
        assertEquals(SubtitleScale.DEFAULT, SubtitleScale.adjust(Double.NaN, 0.0), 0.0001)
    }

    @Test
    fun theOnScreenTextIsTheSizeInPercent() {
        assertEquals(100, SubtitleScale.percent(1.0))
        assertEquals(110, SubtitleScale.percent(1.1))
        assertEquals("Subtitles: 90%", SubtitleScale.label(0.9))
    }
}
