package com.yarmiplaytv.desktop

import com.yarmiplaytv.desktop.PipPlacement.DEFAULT_WIDTH
import com.yarmiplaytv.desktop.PipPlacement.MARGIN
import com.yarmiplaytv.desktop.PipPlacement.MIN_WIDTH
import com.yarmiplaytv.desktop.PipPlacement.resizedWidth
import com.yarmiplaytv.desktop.PipPlacement.bounds as miniBounds
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Rectangle

class PipPlacementTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A 1920×1080 screen with a 40 px taskbar at the bottom. */
    private val work = Rectangle(0, 0, 1920, 1040)

    @Test
    fun firstTimeItSitsInTheBottomRightCornerShapedLikeTheVideo() {
        val b = miniBounds(work, 16.0 / 9.0, null)
        assertEquals(DEFAULT_WIDTH, b.width)
        assertEquals(225, b.height)
        assertEquals(1920 - DEFAULT_WIDTH - MARGIN, b.x)
        assertEquals(1040 - 225 - MARGIN, b.y)
    }

    @Test
    fun anUnknownAspectIsSixteenByNineAndOddOnesAreKeptWithinReason() {
        assertEquals(miniBounds(work, 16.0 / 9.0, null), miniBounds(work, 0.0, null))
        assertEquals(170, miniBounds(work, 2.35, null).height)
        // A portrait phone video: as tall as half the screen at most, the width following.
        val portrait = miniBounds(work, 9.0 / 16.0, null)
        assertEquals(520, portrait.height)
        assertEquals(293, portrait.width)
    }

    @Test
    fun itComesBackWhereAndAsWideAsItWasLeft() {
        val b = miniBounds(work, 16.0 / 9.0, MiniBounds(100, 120, 600))
        assertEquals(Rectangle(100, 120, 600, 338), b)
    }

    @Test
    fun itStaysOnTheScreenAndWithinItsLimits() {
        val offRight = miniBounds(work, 16.0 / 9.0, MiniBounds(1900, 1000, 400))
        assertEquals(1920 - 400, offRight.x)
        assertEquals(1040 - 225, offRight.y)
        assertEquals(MIN_WIDTH, miniBounds(work, 16.0 / 9.0, MiniBounds(0, 0, 50)).width)
        // At most half the screen: half its width would be too tall, so half its height decides.
        assertEquals(Rectangle(0, 0, 924, 520), miniBounds(work, 16.0 / 9.0, MiniBounds(0, 0, 5000)))
        // A screen to the left of the main one, with negative coordinates.
        val left = Rectangle(-1280, 0, 1280, 1000)
        val b = miniBounds(left, 16.0 / 9.0, null)
        assertTrue(left.contains(b))
        assertEquals(-MARGIN - DEFAULT_WIDTH, b.x)
    }

    @Test
    fun thePictureInPictureWindowsPlaceIsKeptWithTheWindowBounds() {
        val file = tmp.root.resolve("window.properties")
        WindowBounds.save(file, WindowState(size = DpSize(1000.dp, 700.dp)), MiniBounds(-300, 40, 480))
        assertEquals(MiniBounds(-300, 40, 480), WindowBounds.load(file).mini)
        // Saving without one keeps none.
        WindowBounds.save(file, WindowState())
        assertEquals(null, WindowBounds.load(file).mini)
    }

    @Test
    fun theCornerGripResizesByTheLargerMoveAndNeverBelowTheMinimum() {
        // Dragging right by 40 (or down by 30, which is 53 in width at 16:9): the larger wins.
        assertEquals(440, resizedWidth(400, 40, 10, 16.0 / 9.0))
        assertEquals(453, resizedWidth(400, 10, 30, 16.0 / 9.0))
        assertEquals(360, resizedWidth(400, -40, 0, 16.0 / 9.0))
        assertEquals(MIN_WIDTH, resizedWidth(300, -500, 0, 16.0 / 9.0))
    }
}
