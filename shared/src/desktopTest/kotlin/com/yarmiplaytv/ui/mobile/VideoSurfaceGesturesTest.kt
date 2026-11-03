package com.yarmiplaytv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSurfaceGesturesTest {
    @Test
    fun primaryClickDistinguishesSingleFromDouble() {
        assertEquals(VideoPrimaryClick.SingleClick, videoPrimaryClickAction(0L, 1_000L, 300L))
        assertEquals(VideoPrimaryClick.DoubleClick, videoPrimaryClickAction(1_000L, 1_200L, 300L))
        assertEquals(VideoPrimaryClick.SingleClick, videoPrimaryClickAction(1_000L, 1_400L, 300L))
    }
}
