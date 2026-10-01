package com.syncplaytv.player.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.ArrayDeque

/** A BGRA frame in native memory that the render thread writes and the UI wraps as a Skia image without copying. */
class VideoFrame internal constructor(val width: Int, val height: Int) {
    val rowBytes: Int = width * 4
    internal val data: Data = Data.makeUninitialized(rowBytes * height)
    val address: Long = data.writableData()
    var serial: Long = 0
        internal set
    @Volatile internal var dueAt: Long = 0
    private var image: Image? = null

    /** A Skia image over this frame's pixels; valid until the frame is written again. */
    fun image(): Image = image ?: Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), data, rowBytes)
        .also { image = it }

    internal fun invalidateImage() {
        image?.close()
        image = null
    }

    internal fun close() {
        invalidateImage()
        data.close()
    }
}

/**
 * Presentation queue between the render thread and the UI. The render thread writes into a free buffer and
 * queues it; the UI shows queued frames in order, one per display refresh. A brief UI stall therefore delays
 * frames by a refresh or two instead of skipping them (the display refreshes faster than video frames
 * arrive, so the queue drains). Only when every buffer is queued is the oldest undrawn frame dropped.
 */
class VideoFrames(private val capacity: Int = 8) {
    private val lock = Any()
    private val free = ArrayDeque<VideoFrame>()
    private val queued = ArrayDeque<VideoFrame>()
    private var front: VideoFrame? = null
    private var writing: VideoFrame? = null
    private var allocated = 0
    private var nextSerial = 1L

    /** Frames dropped because the UI fell more than [capacity] - 2 frames behind (one is shown, one written). */
    @Volatile var overwritten: Long = 0
        private set
    /** Frames the UI drew (each counted once). */
    @Volatile var presented: Long = 0
        private set
    @Volatile var published: Long = 0
        private set
    /** Size of the newest frame, e.g. "2560x1440"; empty before the first one. */
    @Volatile var lastSize: String = ""
        private set

    /** Render thread: a frame of this size to write into. */
    fun beginWrite(width: Int, height: Int): VideoFrame {
        val reused = synchronized(lock) {
            free.pollFirst() ?: if (allocated < capacity) {
                allocated++
                null
            } else {
                overwritten++
                queued.pollFirst()
            }
        }
        val frame = if (reused != null && reused.width == width && reused.height == height) {
            reused.invalidateImage()
            reused
        } else {
            reused?.close()
            VideoFrame(width, height)
        }
        writing = frame
        return frame
    }

    /** Render thread: queues the frame from [beginWrite] for display. */
    fun publish() {
        synchronized(lock) {
            val frame = writing ?: return
            writing = null
            frame.serial = nextSerial++
            queued.addLast(frame)
            published++
            lastSize = "${frame.width}x${frame.height}"
        }
    }

    /** UI thread: the frame to draw now (the oldest undrawn one, else the current one), or null before the first. */
    fun acquire(): VideoFrame? = synchronized(lock) {
        queued.pollFirst()?.let { next ->
            front?.let { free.addLast(it) }
            front = next
            presented++
        }
        front
    }

    /** UI thread: whether more frames are waiting to be drawn. */
    fun hasQueued(): Boolean = synchronized(lock) { queued.isNotEmpty() }

    fun clear() = synchronized(lock) {
        (free + queued + listOfNotNull(front, writing)).forEach { it.close() }
        free.clear(); queued.clear(); front = null; writing = null; allocated = 0
    }
}
