package com.yarmiplaytv.player.desktop

import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.yarmiplaytv.player.renderLongEdge
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.lwjgl.system.MemoryUtil
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Timing counters for the benchmark and diagnostics. All times in nanoseconds. */
class RenderStats {
    val frames = AtomicLong()
    val renderTotal = AtomicLong()
    val renderMax = AtomicLong()
    val readbackTotal = AtomicLong()
    val readbackMax = AtomicLong()
    /** From when a frame was due (mpv's render call returned) to the UI drawing it. */
    val latencyTotal = AtomicLong()
    val latencyMax = AtomicLong()
    val latencySamples = AtomicLong()
    /** Frames drawn more than 16 ms after they were due (shown late, but not skipped). */
    val late = AtomicLong()

    internal fun record(total: AtomicLong, max: AtomicLong, value: Long) {
        total.addAndGet(value)
        max.accumulateAndGet(value, ::maxOf)
    }

    fun reset() = listOf(frames, renderTotal, renderMax, readbackTotal, readbackMax, latencyTotal, latencyMax, latencySamples, late).forEach { it.set(0) }
}

/** Renders mpv's video into [frames] on a dedicated thread; the UI draws them with [VideoSurface]. */
abstract class VideoRenderer internal constructor(
    protected val lib: LibMpv,
    protected val mpv: Pointer,
    threadName: String,
) : AutoCloseable {
    abstract val kind: String
    /** What draws the frames, e.g. the GPU's OpenGL renderer name; null when unknown or for the CPU renderer. */
    @Volatile var device: String? = null
        protected set
    /** OpenGL drawn by the CPU (Mesa's llvmpipe in VMs and CI): it works, but reads back black frames now and then. */
    val isSoftwareRasterizer: Boolean
        get() = device?.let { SOFTWARE_RASTERIZERS.containsMatchIn(it) } == true

    val frames = VideoFrames()
    val stats = RenderStats()
    /** Bumped for every published frame; reading it in a draw lambda redraws on new frames. */
    val frameTick: MutableLongState = mutableLongStateOf(0)

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private var pendingUpdate = false
    private var pendingRedraw = false
    private var stopping = false
    private var width = 2
    private var height = 2
    private val started = CountDownLatch(1)
    @Volatile private var startError: Throwable? = null
    private val thread = Thread(::run, threadName).apply { isDaemon = true }

    private val updateCallback = LibMpv.UpdateCallback { signal { pendingUpdate = true } }
    protected var renderContext: Pointer? = null
        private set

    /** Starts the render thread; throws if the renderer can't be set up (the caller may fall back). */
    internal fun start() {
        thread.start()
        started.await()
        startError?.let { throw it }
    }

    /**
     * Longest edge for the GL FBO / PBO readback. 0 = match the view exactly.
     * Capping cuts PCIe/readback cost on mid-range GPUs when the window is 1440p/4K.
     */
    @Volatile var maxRenderEdge: Int = 1920

    private var viewWidth = 2
    private var viewHeight = 2
    /** 0 follows [maxRenderEdge]. Lowered when frames are late, raised again when they are on time. */
    private var adaptiveEdge = 0
    /** When the picture is the audio spectrum, don't read back more pixels than the graph produced. */
    private var visualizerEdge: Int? = null
    private var calmSamples = 0
    private val framePosted = AtomicBoolean(false)

    /**
     * [edge] is the spectrum graph's long edge, or null while a real video is showing.
     * The next frame is rendered at this cap.
     */
    fun setVisualizerEdge(edge: Int?) = signal {
        if (visualizerEdge == edge) return@signal
        visualizerEdge = edge
        applyViewLocked()
    }

    /**
     * Steps the video render size down when the hand-off stays late, and back up once it is comfortable.
     * Ignored while the spectrum cap is on: those frames are already small and must not teach the video size.
     */
    fun adaptToHandoff(ms: Double) = signal {
        if (visualizerEdge != null) return@signal
        val ceiling = maxRenderEdge.let { if (it <= 0) 1920 else it }
        val current = if (adaptiveEdge <= 0) ceiling else adaptiveEdge
        if (ms > 28.0 && current > ADAPT_FLOOR) {
            adaptiveEdge = maxOf(ADAPT_FLOOR, current * 4 / 5)
            calmSamples = 0
            applyViewLocked()
        } else if (ms < 12.0 && current < ceiling) {
            calmSamples++
            if (calmSamples >= 8) {
                adaptiveEdge = minOf(ceiling, maxOf(current + 160, current * 5 / 4))
                calmSamples = 0
                applyViewLocked()
            }
        } else if (ms >= 12.0) {
            calmSamples = 0
        }
    }

    /** The video view's size in pixels (capped for readback); mpv renders at this size. */
    fun setSize(width: Int, height: Int) = signal {
        viewWidth = width.coerceAtLeast(2)
        viewHeight = height.coerceAtLeast(2)
        applyViewLocked()
    }

    private fun applyViewLocked() {
        val long = maxOf(viewWidth, viewHeight)
        val cap = renderLongEdge(
            viewLong = long,
            configured = maxRenderEdge,
            adaptive = if (adaptiveEdge <= 0) maxRenderEdge else adaptiveEdge,
            visualizerEdge = visualizerEdge,
        )
        val scale = if (long > cap) cap.toDouble() / long else 1.0
        val w = (viewWidth * scale).toInt().coerceAtLeast(2)
        val h = (viewHeight * scale).toInt().coerceAtLeast(2)
        if (w != width || h != height) {
            width = w
            height = h
            pendingRedraw = true
        }
    }

    /** Asks the UI thread to draw, at most once until that draw is queued. Never writes Compose state from here. */
    private fun requestDraw() {
        if (!framePosted.compareAndSet(false, true)) return
        try {
            EventQueue.invokeLater {
                framePosted.set(false)
                frameTick.longValue++
            }
        } catch (_: Throwable) {
            framePosted.set(false)
            frameTick.longValue++
        }
    }

    fun requestRedraw() = signal { pendingRedraw = true }

    @Volatile private var handoffEmaNs = 0.0
    /** How long the last readback spent waiting on the GPU. Pipeline depth is not included. */
    @Volatile protected var copyWaitNs = 0L

    /** Recent average time from a frame being due to the UI drawing it, in ms; null before any frame. */
    fun recentHandoffMs(): Double? = handoffEmaNs.takeIf { it > 0 }?.let { it / 1e6 }

    /** Time the last frame spent blocked on the GPU readback, in ms. Used to scale the picture, not the audio delay. */
    fun recentCopyWaitMs(): Double = copyWaitNs / 1e6

    /** UI thread: called when a frame is drawn for the first time. */
    internal fun onPresented(frame: VideoFrame) {
        if (frame.dueAt != 0L) {
            val latency = System.nanoTime() - frame.dueAt
            if (latency > 16_000_000L) stats.late.incrementAndGet()
            handoffEmaNs = if (handoffEmaNs == 0.0) latency.toDouble() else handoffEmaNs * 0.98 + latency * 0.02
            if (latency > SLOW_HANDOFF_NS && System.getProperty("yarmiplaytv.render.trace") != null) {
                System.err.println("render: frame ${frame.serial} drawn %.1f ms after it was due at %d ms".format(latency / 1e6, System.currentTimeMillis()))
            }
            stats.record(stats.latencyTotal, stats.latencyMax, latency)
            stats.latencySamples.incrementAndGet()
            frame.dueAt = 0
        }
    }

    private inline fun signal(block: () -> Unit) = lock.withLock {
        block()
        wake.signal()
    }

    private fun run() {
        try {
            setUp()
            val ref = PointerByReference()
            val error = lib.mpv_render_context_create(ref, mpv, renderParams())
            check(error >= 0) { "mpv_render_context_create ($kind) failed: ${lib.mpv_error_string(error)}" }
            renderContext = ref.value
            lib.mpv_render_context_set_update_callback(ref.value, updateCallback, null)
        } catch (t: Throwable) {
            startError = t
            runCatching { tearDown() }
            started.countDown()
            return
        }
        started.countDown()
        val ctx = renderContext!!
        try {
            loop(ctx)
        } finally {
            lib.mpv_render_context_set_update_callback(ctx, null, null)
            lib.mpv_render_context_free(ctx)
            renderContext = null
            tearDown()
        }
    }

    private fun loop(ctx: Pointer) {
        while (true) {
            var w: Int
            var h: Int
            var redraw: Boolean
            lock.withLock {
                while (!pendingUpdate && !pendingRedraw && !stopping) wake.await()
                if (stopping) return
                pendingUpdate = false
                redraw = pendingRedraw
                pendingRedraw = false
                w = width
                h = height
            }
            val flags = lib.mpv_render_context_update(ctx)
            if (flags and LibMpv.RENDER_UPDATE_FRAME == 0L && !redraw) continue
            val frame = frames.beginWrite(w, h)
            frame.dueAt = render(ctx, frame)
            frames.publish()
            stats.frames.incrementAndGet()
            requestDraw()
            lib.mpv_render_context_report_swap(ctx)
        }
    }

    /** Creates whatever [render] needs, on the render thread. */
    protected abstract fun setUp()
    /** Parameters for mpv_render_context_create. */
    protected abstract fun renderParams(): Pointer
    /**
     * Renders the current video frame into [frame]. Returns System.nanoTime() of when mpv's render call returned,
     * which is when the frame was due (mpv blocks until then).
     */
    protected abstract fun render(ctx: Pointer, frame: VideoFrame): Long
    protected abstract fun tearDown()

    /** Stops the render thread and frees the render context; must happen before mpv_terminate_destroy. */
    override fun close() {
        signal { stopping = true }
        if (thread.isAlive) thread.join(5_000)
        frames.clear()
    }

    companion object {
        private const val SLOW_HANDOFF_NS = 12_000_000L
        private const val ADAPT_FLOOR = 960
        private val SOFTWARE_RASTERIZERS = Regex("llvmpipe|softpipe|swrast|SwiftShader|Basic Render", RegexOption.IGNORE_CASE)

        /**
         * GPU rendering unless [mode] is "sw" or the GPU path can't start (e.g. no OpenGL in a VM).
         * [onFallback] receives the reason when it falls back.
         */
        fun create(
            lib: LibMpv,
            mpv: Pointer,
            mode: String = "auto",
            maxRenderEdge: Int = 1920,
            onFallback: (Throwable) -> Unit = {},
        ): VideoRenderer {
            if (mode != "sw") {
                val gl = GlVideoRenderer(lib, mpv, maxRenderEdge = maxRenderEdge)
                try {
                    gl.start()
                    return gl
                } catch (t: Throwable) {
                    if (mode == "gl") throw t
                    onFallback(t)
                }
            }
            return SoftwareVideoRenderer(lib, mpv).also { it.start() }
        }

        internal fun params(vararg entries: Pair<Int, Pointer?>): Memory {
            val memory = Memory(16L * (entries.size + 1))
            memory.clear()
            entries.forEachIndexed { i, (type, data) ->
                memory.setInt(i * 16L, type)
                memory.setPointer(i * 16L + 8, data)
            }
            return memory
        }

        internal fun cString(value: String): Memory {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return Memory(bytes.size + 1L).apply {
                write(0, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }
    }
}

/**
 * mpv renders on the GPU (hardware decoding, scaling, colour conversion) into a framebuffer sized to the view;
 * the result is read back through a ring of pixel buffer objects and handed to Skia.
 * The GPU fills one buffer while the CPU maps an earlier one, so a frame does not wait on its own readback.
 */
internal class GlVideoRenderer(
    lib: LibMpv,
    mpv: Pointer,
    maxRenderEdge: Int = 1920,
) : VideoRenderer(lib, mpv, "mpv-render-gl") {
    override val kind = "opengl"

    init {
        this.maxRenderEdge = maxRenderEdge
    }

    private var context: GlContext? = null
    private var getProcAddress: LibMpv.GetProcAddress? = null
    private var initParams: LibMpv.OpenGlInitParams? = null
    private val apiType = cString("opengl")
    private val fboParam = Memory(16)
    // No FLIP_Y: mpv draws an FBO top row first, which is the row order glReadPixels returns and Skia expects.
    private val renderParams = params(LibMpv.RENDER_PARAM_OPENGL_FBO to fboParam)

    private var fbo = 0
    private var texture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private val readSlots = Array(3) { ReadSlot() }
    private var writeIndex = 0
    private var completedReads = 0

    private class ReadSlot {
        var pbo = 0
        var fence = 0L
        var dueAt = 0L
        var width = 0
        var height = 0
        var filled = false
    }

    override fun setUp() {
        val ctx = GlContext.create()
        context = ctx
        device = runCatching { GL11.glGetString(GL11.GL_RENDERER) }.getOrNull()
        getProcAddress = LibMpv.GetProcAddress { _, name ->
            val address = name?.let(ctx::getProcAddress) ?: 0L
            if (address == 0L) null else Pointer(address)
        }
        initParams = LibMpv.OpenGlInitParams().apply {
            get_proc_address = getProcAddress
            write()
        }
    }

    override fun renderParams(): Pointer = params(
        LibMpv.RENDER_PARAM_API_TYPE to apiType,
        LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS to initParams!!.pointer,
    )

    private fun ensureTargets(width: Int, height: Int) {
        if (width == targetWidth && height == targetHeight && fbo != 0) return
        deleteTargets()
        texture = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) { "Incomplete framebuffer" }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        targetWidth = width
        targetHeight = height
    }

    override fun render(ctx: Pointer, frame: VideoFrame): Long {
        val w = frame.width
        val h = frame.height
        ensureTargets(w, h)
        fboParam.setInt(0, fbo)
        fboParam.setInt(4, w)
        fboParam.setInt(8, h)
        fboParam.setInt(12, GL11.GL_RGBA8)
        val start = System.nanoTime()
        lib.mpv_render_context_render(ctx, renderParams)
        val rendered = System.nanoTime()

        val written = readSlots[writeIndex]
        queueRead(written, w, h, rendered)
        val stale = readSlots.any { it.filled && it !== written && (it.width != w || it.height != h) }
        if (stale) {
            readSlots.forEach { if (it !== written) it.filled = false }
            completedReads = 0
        }
        val lag = if (stale) 0 else when {
            completedReads >= 2 -> 2
            completedReads >= 1 -> 1
            else -> 0
        }
        writeIndex = (writeIndex + 1) % readSlots.size
        if (completedReads < 3) completedReads++
        val read = readSlots[(writeIndex - 1 - lag + readSlots.size * 2) % readSlots.size]
        val copied = copySlot(read, frame, if (lag == 0) 1_000_000_000L else 2_000_000L)
        if (!copied) {
            readSlots.forEach { if (it !== written) it.filled = false }
            completedReads = 1
            copySlot(written, frame, 1_000_000_000L)
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
        val done = System.nanoTime()
        stats.record(stats.renderTotal, stats.renderMax, rendered - start)
        stats.record(stats.readbackTotal, stats.readbackMax, done - rendered)
        return if (copied) read.dueAt else rendered
    }

    /** Starts an async read of the framebuffer into [slot]. The fence is waited on a later frame. */
    private fun queueRead(slot: ReadSlot, width: Int, height: Int, dueAt: Long) {
        if (slot.pbo == 0) slot.pbo = GL15.glGenBuffers()
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo)
        if (slot.width != width || slot.height != height) {
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, width.toLong() * height * 4, GL15.GL_STREAM_READ)
            slot.width = width
            slot.height = height
        }
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        if (slot.fence != 0L) GL32.glDeleteSync(slot.fence)
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        slot.dueAt = dueAt
        slot.filled = true
    }

    /** Copies [slot] into [frame] once its fence is signaled. False when the slot is the wrong size or unmapped. */
    private fun copySlot(slot: ReadSlot, frame: VideoFrame, quickNs: Long): Boolean {
        if (!slot.filled || slot.pbo == 0 || slot.width != frame.width || slot.height != frame.height) return false
        if (slot.fence != 0L) {
            val waitedFrom = System.nanoTime()
            val status = GL32.glClientWaitSync(slot.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, quickNs)
            if (status == GL32.GL_TIMEOUT_EXPIRED || status == GL32.GL_WAIT_FAILED) {
                GL32.glClientWaitSync(slot.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L)
            }
            copyWaitNs = System.nanoTime() - waitedFrom
        } else {
            copyWaitNs = 0L
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo)
        val size = frame.width.toLong() * frame.height * 4
        val pixels = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, size, GL30.GL_MAP_READ_BIT)
        if (pixels != null) MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), frame.address, size)
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        return pixels != null
    }

    private fun deleteTargets() {
        readSlots.forEach { slot ->
            if (slot.fence != 0L) GL32.glDeleteSync(slot.fence)
            if (slot.pbo != 0) GL15.glDeleteBuffers(slot.pbo)
            slot.pbo = 0
            slot.fence = 0L
            slot.filled = false
            slot.width = 0
            slot.height = 0
        }
        writeIndex = 0
        completedReads = 0
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo)
        if (texture != 0) GL11.glDeleteTextures(texture)
        fbo = 0; texture = 0; targetWidth = 0; targetHeight = 0
    }

    override fun tearDown() {
        context?.let {
            deleteTargets()
            it.destroy()
        }
        context = null
    }
}

/** mpv's CPU renderer writing straight into the frame memory; for machines without usable OpenGL. */
internal class SoftwareVideoRenderer(lib: LibMpv, mpv: Pointer) : VideoRenderer(lib, mpv, "mpv-render-sw") {
    override val kind = "software"

    private val apiType = cString("sw")
    private val format = cString("bgr0")
    private val size = Memory(8)
    private val stride = Memory(8)

    override fun setUp() = Unit

    override fun renderParams(): Pointer = params(LibMpv.RENDER_PARAM_API_TYPE to apiType)

    override fun render(ctx: Pointer, frame: VideoFrame): Long {
        size.setInt(0, frame.width)
        size.setInt(4, frame.height)
        stride.setLong(0, frame.rowBytes.toLong())
        val start = System.nanoTime()
        lib.mpv_render_context_render(
            ctx,
            params(
                LibMpv.RENDER_PARAM_SW_SIZE to size,
                LibMpv.RENDER_PARAM_SW_FORMAT to format,
                LibMpv.RENDER_PARAM_SW_STRIDE to stride,
                LibMpv.RENDER_PARAM_SW_POINTER to Pointer(frame.address),
            ),
        )
        val rendered = System.nanoTime()
        stats.record(stats.renderTotal, stats.renderMax, rendered - start)
        return rendered
    }

    override fun tearDown() = Unit
}
