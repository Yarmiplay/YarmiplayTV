package com.yarmiplaytv.ui.mobile

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import kotlin.math.ceil

/**
 * One monitor as Java reports it. [x] and [y] are [GraphicsConfiguration.getBounds]; [width] and [height] are that
 * rectangle's size. [scaleX] and [scaleY] are the configuration's default transform. What those numbers *mean*
 * depends on [PipDrag.ScaleSpace]: Windows keeps the device-pixel origin and stores a scaled size, Linux divides
 * the whole bounds by the scale, and macOS reports one point space whose transform is only the backing scale.
 */
data class PipMonitor(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scaleX: Double,
    val scaleY: Double,
)

/** The pointer, in the user coordinates [MouseInfo] returns, plus the monitor it was on at that moment. */
data class PipScreenPointer(
    val x: Int,
    val y: Int,
    val monitor: PipMonitor,
)

/**
 * Moves the picture-in-picture window with the pointer across monitors whose resolutions and scales differ.
 *
 * [MouseInfo] and [java.awt.Window.getX] are not one continuous pixel space. Each sample is scaled by the monitor
 * the pointer (or the window) is on: Windows as `origin + (device - origin) / scale`, Linux as `device / scale`.
 * A pointer that walks one device pixel onto a 200% monitor is therefore reported hundreds of user pixels backward,
 * and a pointer that stays on that monitor moves half as many user pixels as a window still on a 100% monitor.
 * Adding that user delta to the window's current user position jumps, lags, and — because [java.awt.Window.setLocation]
 * rescales a window when its center changes monitor — resizes it. The next sample then adds another delta to the
 * already-wrong position, so the error accumulates.
 *
 * A drag instead remembers the device-pixel pointer and the device-pixel window at the press. Each move places the
 * window at that window point plus the pointer's device-pixel travel, then converts into the user coordinates of the
 * monitor under the window's center. The device-pixel size from the press is what gets converted, so the window does
 * not change size as the scale changes. The result is pulled back when it would leave every monitor.
 */
object PipDrag {
    /** A window this far onto a monitor, in device pixels, is still on-screen and is not pulled back. */
    const val MIN_VISIBLE_DEVICE_PX = 32

    /** How Java maps device pixels to the coordinates used for the pointer and for [java.awt.Window.setBounds]. */
    enum class ScaleSpace {
        /** Windows, JDK 16 and later: position is scaled relative to the monitor's device-pixel origin. */
        ORIGIN_RELATIVE,
        /** Linux: position and size are the device pixels divided by that monitor's scale. */
        ABSOLUTE,
        /** macOS: pointer and window already share one point space. The transform is the backing store. */
        UNIFIED,
    }

    /** Where the press grabbed the window, in device pixels, and the device-pixel size to keep. */
    data class MoveAnchor(
        val pointerDeviceX: Int,
        val pointerDeviceY: Int,
        val windowDeviceX: Int,
        val windowDeviceY: Int,
        val deviceWidth: Int,
        val deviceHeight: Int,
    )

    /** User-space bounds to pass to [java.awt.Window.setBounds]. */
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    fun scaleSpace(osName: String): ScaleSpace {
        val os = osName.lowercase()
        return when {
            "win" in os -> ScaleSpace.ORIGIN_RELATIVE
            "mac" in os -> ScaleSpace.UNIFIED
            else -> ScaleSpace.ABSOLUTE
        }
    }

    fun systemPointer(): PipScreenPointer? = runCatching {
        val info = MouseInfo.getPointerInfo() ?: return null
        val gc = info.device?.defaultConfiguration ?: return null
        PipScreenPointer(info.location.x, info.location.y, monitorOf(gc))
    }.getOrNull()

    fun systemMonitors(): List<PipMonitor> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
            device.defaultConfiguration?.let { monitorOf(it) }
        }
    }.getOrDefault(emptyList())

    fun monitorOf(gc: GraphicsConfiguration): PipMonitor {
        val bounds = gc.bounds
        val transform = gc.defaultTransform
        return PipMonitor(bounds.x, bounds.y, bounds.width, bounds.height, transform.scaleX, transform.scaleY)
    }

    fun moveAnchor(
        pointer: PipScreenPointer,
        windowX: Int,
        windowY: Int,
        windowWidth: Int,
        windowHeight: Int,
        windowMonitor: PipMonitor,
        space: ScaleSpace,
    ): MoveAnchor = MoveAnchor(
        pointerDeviceX = toDeviceX(pointer.x, pointer.monitor, space),
        pointerDeviceY = toDeviceY(pointer.y, pointer.monitor, space),
        windowDeviceX = toDeviceX(windowX, windowMonitor, space),
        windowDeviceY = toDeviceY(windowY, windowMonitor, space),
        deviceWidth = toDeviceSize(windowWidth, windowMonitor.scaleX, space),
        deviceHeight = toDeviceSize(windowHeight, windowMonitor.scaleY, space),
    )

    /** The window's user-space bounds for this pointer sample, keeping [anchor]'s device size. */
    fun moved(anchor: MoveAnchor, pointer: PipScreenPointer, monitors: List<PipMonitor>, space: ScaleSpace): Bounds {
        val x = add(anchor.windowDeviceX, deviceTravel(anchor.pointerDeviceX, toDeviceX(pointer.x, pointer.monitor, space)))
        val y = add(anchor.windowDeviceY, deviceTravel(anchor.pointerDeviceY, toDeviceY(pointer.y, pointer.monitor, space)))
        return placeDevice(x, y, anchor.deviceWidth, anchor.deviceHeight, pointer.monitor, monitors, space)
    }

    /**
     * How far the pointer traveled since the press, in the user pixels of a window whose scale is [scaleX] by
     * [scaleY]. A jump in the raw user coordinates at a monitor boundary is not part of this distance.
     */
    fun userDelta(start: PipScreenPointer, current: PipScreenPointer, scaleX: Double, scaleY: Double, space: ScaleSpace): Pair<Int, Int> {
        val dx = deviceTravel(toDeviceX(start.x, start.monitor, space), toDeviceX(current.x, current.monitor, space))
        val dy = deviceTravel(toDeviceY(start.y, start.monitor, space), toDeviceY(current.y, current.monitor, space))
        return userDelta(dx, scaleX, space) to userDelta(dy, scaleY, space)
    }

    /**
     * Top-left stays at the device point from the press (so a monitor boundary does not throw the window) and the
     * size is [userWidth] by [userHeight] in [sizeMonitor]'s user pixels. On that same monitor the user size is kept
     * exactly; on another scale it is converted so the device-pixel size does not jump.
     */
    fun resizedBounds(
        deviceX: Int,
        deviceY: Int,
        userWidth: Int,
        userHeight: Int,
        sizeMonitor: PipMonitor,
        monitors: List<PipMonitor>,
        space: ScaleSpace,
    ): Bounds {
        val width = userWidth.coerceAtLeast(1)
        val height = userHeight.coerceAtLeast(1)
        val deviceW = toDeviceSize(width, sizeMonitor.scaleX, space)
        val deviceH = toDeviceSize(height, sizeMonitor.scaleY, space)
        val clamped = clampDevice(deviceX, deviceY, deviceW, deviceH, monitors, space)
        val target = monitorUnder(clamped.x + deviceW / 2, clamped.y + deviceH / 2, monitors, sizeMonitor, space)
        return Bounds(
            toUser(clamped.x, target.x, target.scaleX, space),
            toUser(clamped.y, target.y, target.scaleY, space),
            if (sameMonitor(target, sizeMonitor)) width else toUserSize(deviceW, target.scaleX, space),
            if (sameMonitor(target, sizeMonitor)) height else toUserSize(deviceH, target.scaleY, space),
        )
    }

    fun toDeviceX(user: Int, monitor: PipMonitor, space: ScaleSpace): Int = toDevice(user, monitor.x, monitor.scaleX, space)

    fun toDeviceY(user: Int, monitor: PipMonitor, space: ScaleSpace): Int = toDevice(user, monitor.y, monitor.scaleY, space)

    private fun placeDevice(
        deviceX: Int,
        deviceY: Int,
        deviceWidth: Int,
        deviceHeight: Int,
        fallback: PipMonitor,
        monitors: List<PipMonitor>,
        space: ScaleSpace,
    ): Bounds {
        val width = deviceWidth.coerceAtLeast(1)
        val height = deviceHeight.coerceAtLeast(1)
        val clamped = clampDevice(deviceX, deviceY, width, height, monitors, space)
        val target = monitorUnder(clamped.x + width / 2, clamped.y + height / 2, monitors, fallback, space)
        return Bounds(
            toUser(clamped.x, target.x, target.scaleX, space),
            toUser(clamped.y, target.y, target.scaleY, space),
            toUserSize(width, target.scaleX, space),
            toUserSize(height, target.scaleY, space),
        )
    }

    private fun clampDevice(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        monitors: List<PipMonitor>,
        space: ScaleSpace,
    ): DeviceRect {
        val window = DeviceRect(x, y, width, height)
        val screens = monitors.map { it.deviceBounds(space) }.filter { it.width > 0 && it.height > 0 }
        if (screens.isEmpty() || screens.any { visibleOn(window, it) }) return window
        val nearest = screens.minWith(compareBy({ gap(window, it) }, { it.x }, { it.y }))
        return slideOnto(window, nearest)
    }

    private fun monitorUnder(deviceX: Int, deviceY: Int, monitors: List<PipMonitor>, fallback: PipMonitor, space: ScaleSpace): PipMonitor {
        val hits = monitors.filter { it.deviceBounds(space).contains(deviceX, deviceY) }
        if (hits.size == 1) return hits.first()
        if (hits.size > 1) {
            return hits.minBy { val b = it.deviceBounds(space); distance2(b.x + b.width / 2, b.y + b.height / 2, deviceX, deviceY) }
        }
        if (monitors.isEmpty()) return fallback
        return monitors.minWith(compareBy({ gap(DeviceRect(deviceX, deviceY, 1, 1), it.deviceBounds(space)) }, { it.x }, { it.y }))
    }

    private fun PipMonitor.deviceBounds(space: ScaleSpace): DeviceRect = when (space) {
        ScaleSpace.UNIFIED -> DeviceRect(x, y, width.coerceAtLeast(0), height.coerceAtLeast(0))
        ScaleSpace.ABSOLUTE -> DeviceRect(
            clipRound(x.toDouble() * scale(scaleX)),
            clipRound(y.toDouble() * scale(scaleY)),
            clipRound(width.toDouble() * scale(scaleX)).coerceAtLeast(0),
            clipRound(height.toDouble() * scale(scaleY)).coerceAtLeast(0),
        )
        ScaleSpace.ORIGIN_RELATIVE -> DeviceRect(
            x,
            y,
            clipRound(width.toDouble() * scale(scaleX)).coerceAtLeast(0),
            clipRound(height.toDouble() * scale(scaleY)).coerceAtLeast(0),
        )
    }

    private fun toDevice(user: Int, origin: Int, scale: Double, space: ScaleSpace): Int {
        val s = scale(scale)
        return when (space) {
            ScaleSpace.UNIFIED -> user
            ScaleSpace.ABSOLUTE -> clipRound(user.toDouble() * s)
            ScaleSpace.ORIGIN_RELATIVE -> add(origin, clipRound((user.toLong() - origin).toDouble() * s))
        }
    }

    private fun toUser(device: Int, origin: Int, scale: Double, space: ScaleSpace): Int {
        val s = scale(scale)
        return when (space) {
            ScaleSpace.UNIFIED -> device
            ScaleSpace.ABSOLUTE -> clipRound(device.toDouble() / s)
            ScaleSpace.ORIGIN_RELATIVE -> add(origin, clipRound((device.toLong() - origin).toDouble() / s))
        }
    }

    private fun toDeviceSize(user: Int, scale: Double, space: ScaleSpace): Int = when (space) {
        ScaleSpace.UNIFIED -> user.coerceAtLeast(1)
        else -> clipRound(user.toDouble() * scale(scale)).coerceAtLeast(1)
    }

    private fun toUserSize(device: Int, scale: Double, space: ScaleSpace): Int = when (space) {
        ScaleSpace.UNIFIED -> device.coerceAtLeast(1)
        else -> clipRound(device.toDouble() / scale(scale)).coerceAtLeast(1)
    }

    private fun userDelta(deviceDelta: Int, scale: Double, space: ScaleSpace): Int = when (space) {
        ScaleSpace.UNIFIED -> deviceDelta
        else -> clipRound(deviceDelta.toDouble() / scale(scale))
    }

    private fun sameMonitor(a: PipMonitor, b: PipMonitor): Boolean =
        a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height && a.scaleX == b.scaleX && a.scaleY == b.scaleY

    private fun scale(scale: Double): Double = if (scale.isFinite() && scale > 0.0) scale else 1.0

    /** Matches the JDK's `ClipRound`: half goes toward negative infinity. */
    private fun clipRound(value: Double): Int {
        if (!value.isFinite()) return 0
        val rounded = ceil(value - 0.5)
        if (rounded >= Int.MAX_VALUE) return Int.MAX_VALUE
        if (rounded <= Int.MIN_VALUE) return Int.MIN_VALUE
        return rounded.toInt()
    }

    private fun add(a: Int, b: Int): Int = (a.toLong() + b).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun deviceTravel(from: Int, to: Int): Int =
        (to.toLong() - from).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private data class DeviceRect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height
    }

    private fun visibleOn(window: DeviceRect, screen: DeviceRect): Boolean {
        val overlapW = overlap(window.x, window.width, screen.x, screen.width)
        val overlapH = overlap(window.y, window.height, screen.y, screen.height)
        if (overlapW <= 0 || overlapH <= 0) return false
        val minW = minOf(MIN_VISIBLE_DEVICE_PX, window.width, screen.width)
        val minH = minOf(MIN_VISIBLE_DEVICE_PX, window.height, screen.height)
        return overlapW >= minW && overlapH >= minH
    }

    private fun slideOnto(window: DeviceRect, screen: DeviceRect): DeviceRect {
        val minW = minOf(MIN_VISIBLE_DEVICE_PX, window.width, screen.width).coerceAtLeast(1)
        val minH = minOf(MIN_VISIBLE_DEVICE_PX, window.height, screen.height).coerceAtLeast(1)
        return DeviceRect(
            slide(window.x, window.width, screen.x, screen.width, minW),
            slide(window.y, window.height, screen.y, screen.height, minH),
            window.width,
            window.height,
        )
    }

    private fun slide(position: Int, size: Int, screenStart: Int, screenSize: Int, minVisible: Int): Int {
        val min = screenStart + minVisible - size
        val max = screenStart + screenSize - minVisible
        return if (min <= max) position.coerceIn(min, max) else screenStart
    }

    private fun overlap(a: Int, aSize: Int, b: Int, bSize: Int): Int {
        val start = maxOf(a, b)
        val end = minOf(a + aSize, b + bSize)
        return (end - start).coerceAtLeast(0)
    }

    private fun gap(window: DeviceRect, screen: DeviceRect): Long {
        val dx = axisGap(window.x, window.width, screen.x, screen.width)
        val dy = axisGap(window.y, window.height, screen.y, screen.height)
        return dx * dx + dy * dy
    }

    private fun axisGap(a: Int, aSize: Int, b: Int, bSize: Int): Long = when {
        a + aSize <= b -> (b - (a + aSize)).toLong()
        b + bSize <= a -> (a - (b + bSize)).toLong()
        else -> 0L
    }

    private fun distance2(ax: Int, ay: Int, bx: Int, by: Int): Long {
        val dx = ax.toLong() - bx
        val dy = ay.toLong() - by
        return dx * dx + dy * dy
    }
}
