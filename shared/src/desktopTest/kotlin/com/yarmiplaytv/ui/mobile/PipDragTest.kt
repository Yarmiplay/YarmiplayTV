package com.yarmiplaytv.ui.mobile

import com.yarmiplaytv.ui.mobile.PipDrag.Bounds
import com.yarmiplaytv.ui.mobile.PipDrag.ScaleSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PipDragTest {
    private val windows = ScaleSpace.ORIGIN_RELATIVE
    private val linux = ScaleSpace.ABSOLUTE
    private val mac = ScaleSpace.UNIFIED

    @Test
    fun scaleSpaceFollowsThePlatform() {
        assertEquals(windows, PipDrag.scaleSpace("Windows 11"))
        assertEquals(mac, PipDrag.scaleSpace("Mac OS X"))
        assertEquals(linux, PipDrag.scaleSpace("Linux"))
    }

    @Test
    fun oneMonitorKeepsTheGrabOffsetAndTheSize() {
        val screen = monitor(0, 0, 1920, 1080, 1.0)
        val anchor = anchor(pointer(1700, 450, screen), window = Bounds(1600, 400, 400, 225), screen, linux)
        val moved = PipDrag.moved(anchor, pointer(1760, 470, screen), listOf(screen), linux)
        assertEquals(Bounds(1660, 420, 400, 225), moved)
        // Back at the press point, from the same anchor: nothing a previous sample did can stick.
        assertEquals(Bounds(1600, 400, 400, 225), PipDrag.moved(anchor, pointer(1700, 450, screen), listOf(screen), linux))
    }

    @Test
    fun fractionalScaleOnOneMonitorTracksThePointerInUserPixels() {
        val screen = monitor(0, 0, 1280, 720, 1.5)
        for (space in listOf(linux, windows)) {
            val anchor = anchor(pointer(120, 90, screen), Bounds(100, 80, 400, 225), screen, space)
            assertEquals(Bounds(130, 100, 400, 225), PipDrag.moved(anchor, pointer(150, 110, screen), listOf(screen), space))
        }
    }

    @Test
    fun windowsMonitorWithANonZeroOriginAndItsOwnScaleStillTracksUserPixels() {
        // 200% monitor whose device origin is 1920. User x 2000 is 80 device pixels in, so 160 device pixels from the origin.
        val screen = monitor(1920, 0, 960, 540, 2.0)
        val anchor = anchor(pointer(2020, 200, screen), Bounds(2000, 180, 400, 225), screen, windows)
        val moved = PipDrag.moved(anchor, pointer(2035, 210, screen), listOf(screen), windows)
        assertEquals(Bounds(2015, 190, 400, 225), moved)
    }

    @Test
    fun linuxPointerJumpAtTheBoundaryMovesByTheDevicePixelsAndKeepsTheDeviceSize() {
        val primary = monitor(0, 0, 1920, 1080, 1.0)
        // A 1920×1080 device-pixel monitor at x=1920, scale 2, reported as user bounds divided by that scale.
        val scaled = monitor(960, 0, 960, 540, 2.0)
        val anchor = anchor(pointer(1700, 450, primary), Bounds(1600, 400, 400, 225), primary, linux)
        // Device x 1940 is user 970 on this monitor: 730 user pixels backward from the press, 240 device pixels forward.
        val moved = PipDrag.moved(anchor, pointer(970, 230, scaled), listOf(primary, scaled), linux)
        assertEquals(Bounds(920, 205, 200, 112), moved)
        assertEquals(1840, PipDrag.toDeviceX(moved.x, scaled, linux))
        assertEquals(410, PipDrag.toDeviceY(moved.y, scaled, linux))
        assertEquals(400, deviceSize(moved.width, 2.0))
        assertTrue(abs(225 - deviceSize(moved.height, 2.0)) <= 1)
        // The user-space center is inside the target monitor's device bounds, which is the point Windows (and a
        // nearest-monitor lookup) uses to choose the scale. 920+100 = 1020, and this monitor's device x starts at 1920.
        // Linux's user center is device/scale, so check the device center instead: it sits on the scaled monitor.
        val centerX = PipDrag.toDeviceX(moved.x, scaled, linux) + 400 / 2
        assertTrue(centerX in 1920 until 1920 + 1920)
    }

    @Test
    fun windowsCrossScaleDragFollowsDevicePixelsInsteadOfTheSlowerUserDelta() {
        val primary = monitor(0, 0, 1920, 1080, 1.0)
        val scaled = monitor(1920, 0, 960, 540, 2.0)
        val anchor = anchor(pointer(1700, 450, primary), Bounds(1600, 400, 400, 225), primary, windows)
        // Device (1940, 460) is user (1930, 230) here. The raw user delta is +230, the device delta is +240.
        val moved = PipDrag.moved(anchor, pointer(1930, 230, scaled), listOf(primary, scaled), windows)
        assertEquals(Bounds(1880, 205, 200, 112), moved)
        assertEquals(1840, PipDrag.toDeviceX(moved.x, scaled, windows))
        assertEquals(410, PipDrag.toDeviceY(moved.y, scaled, windows))
        val centerX = moved.x + moved.width / 2
        val centerY = moved.y + moved.height / 2
        assertTrue(centerX in 1920 until 1920 + 1920)
        assertTrue(centerY in 0 until 1080)
    }

    @Test
    fun differentResolutionsAtTheSameScaleAreOneContinuousDesktop() {
        val laptop = monitor(0, 0, 1920, 1080, 1.0)
        val external = monitor(1920, 0, 2560, 1440, 1.0)
        val anchor = anchor(pointer(1800, 200, laptop), Bounds(1700, 100, 400, 225), laptop, windows)
        val moved = PipDrag.moved(anchor, pointer(2200, 230, external), listOf(laptop, external), windows)
        assertEquals(Bounds(2100, 130, 400, 225), moved)
    }

    @Test
    fun macIgnoresTheBackingScaleAndKeepsThePointSize() {
        val builtIn = monitor(0, 0, 1512, 982, 2.0)
        val external = monitor(1512, 0, 1920, 1080, 1.0)
        val anchor = anchor(pointer(1400, 400, builtIn), Bounds(1300, 300, 400, 225), builtIn, mac)
        val moved = PipDrag.moved(anchor, pointer(1700, 420, external), listOf(builtIn, external), mac)
        assertEquals(Bounds(1600, 320, 400, 225), moved)
    }

    @Test
    fun aDragPastTheDesktopKeepsAVisibleStripAndTheSameSize() {
        val screen = monitor(0, 0, 1920, 1080, 1.0)
        val anchor = anchor(pointer(150, 150, screen), Bounds(100, 100, 400, 225), screen, linux)
        val moved = PipDrag.moved(anchor, pointer(5050, 150, screen), listOf(screen), linux)
        val visible = PipDrag.MIN_VISIBLE_DEVICE_PX
        assertEquals(Bounds(1920 - visible, 100, 400, 225), moved)
    }

    @Test
    fun aWindowBetweenMonitorsIsPulledOntoTheNearestOne() {
        val left = monitor(0, 0, 1000, 1000, 1.0)
        val right = monitor(2000, 0, 1000, 1000, 1.0)
        val anchor = anchor(pointer(120, 120, left), Bounds(100, 100, 100, 100), left, linux)
        val moved = PipDrag.moved(anchor, pointer(1220, 120, left), listOf(left, right), linux)
        assertEquals(Bounds(1000 - PipDrag.MIN_VISIBLE_DEVICE_PX, 100, 100, 100), moved)
    }

    @Test
    fun straddlingTwoMonitorsIsLeftWhereThePointerPutIt() {
        val left = monitor(0, 0, 1000, 1000, 1.0)
        val right = monitor(1000, 0, 1000, 1000, 1.0)
        val anchor = anchor(pointer(150, 150, left), Bounds(100, 100, 200, 100), left, windows)
        assertEquals(Bounds(900, 100, 200, 100), PipDrag.moved(anchor, pointer(950, 150, right), listOf(left, right), windows))
    }

    @Test
    fun aMonitorToTheLeftClampsInNegativeCoordinates() {
        val left = monitor(-1920, 0, 1920, 1080, 1.0)
        val primary = monitor(0, 0, 1920, 1080, 1.0)
        val anchor = anchor(pointer(150, 150, primary), Bounds(100, 100, 400, 225), primary, windows)
        val moved = PipDrag.moved(anchor, pointer(-3150, 150, left), listOf(left, primary), windows)
        assertEquals(Bounds(-1920 + PipDrag.MIN_VISIBLE_DEVICE_PX - 400, 100, 400, 225), moved)
    }

    @Test
    fun aBrokenScaleDoesNotThrowTheWindow() {
        val screen = monitor(0, 0, 1920, 1080, Double.NaN)
        val anchor = anchor(pointer(20, 20, screen), Bounds(10, 10, 400, 225), screen, linux)
        assertEquals(Bounds(40, 10, 400, 225), PipDrag.moved(anchor, pointer(50, 20, screen), listOf(screen), linux))
    }

    @Test
    fun theResizeGripUsesDeviceTravelSoAMonitorBoundaryDoesNotChangeTheSizeByTheJump() {
        val primary = monitor(0, 0, 1920, 1080, 1.0)
        val scaled = monitor(960, 0, 960, 540, 2.0)
        val start = pointer(1910, 400, primary)
        // Device (1940, 400) is user (970, 200) on the 200% monitor: x jumps backward, y is halved.
        val current = pointer(970, 200, scaled)
        // Raw user delta is (970 - 1910, 200 - 400) = (-940, -200). Device travel is (30, 0).
        assertEquals(30 to 0, PipDrag.userDelta(start, current, 1.0, 1.0, linux))
        val resized = PipDrag.resizedBounds(1600, 400, 430, 242, primary, listOf(primary, scaled), linux)
        assertEquals(Bounds(1600, 400, 430, 242), resized)
    }

    @Test
    fun resizingOntoAnotherScaleKeepsTheDeviceSizeOfTheNewUserSize() {
        val primary = monitor(0, 0, 1920, 1080, 1.0)
        val scaled = monitor(1920, 0, 960, 540, 2.0)
        // The corner grew the window to 800×450 user pixels on the 100% monitor. Its center is on the 200% one.
        val resized = PipDrag.resizedBounds(1700, 100, 800, 450, primary, listOf(primary, scaled), windows)
        assertEquals(400, resized.width)
        assertEquals(225, resized.height)
        assertEquals(1700, PipDrag.toDeviceX(resized.x, scaled, windows))
        assertEquals(800, deviceSize(resized.width, 2.0))
    }

    private fun monitor(x: Int, y: Int, width: Int, height: Int, scale: Double) =
        PipMonitor(x, y, width, height, scale, scale)

    private fun pointer(x: Int, y: Int, monitor: PipMonitor) = PipScreenPointer(x, y, monitor)

    private fun anchor(pointer: PipScreenPointer, window: Bounds, windowMonitor: PipMonitor, space: ScaleSpace) =
        PipDrag.moveAnchor(pointer, window.x, window.y, window.width, window.height, windowMonitor, space)

    private fun deviceSize(user: Int, scale: Double): Int = kotlin.math.ceil(user * scale - 0.5).toInt()
}
