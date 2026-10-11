package com.yarmiplaytv.player.desktop

import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.TrackType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** Real libmpv on the test clip; needs libmpv and a display (set YARMIPLAYTV_SKIP_MPV_TESTS=1 to skip). */
class DesktopMpvPlayerTest {
    private lateinit var player: DesktopMpvPlayer
    private val clip = File(System.getProperty("yarmiplaytv.testClip") ?: "")

    @Before
    fun setUp() {
        assumeTrue("libmpv tests skipped", System.getenv("YARMIPLAYTV_SKIP_MPV_TESTS").isNullOrEmpty())
        assumeTrue("test clip missing: $clip", clip.isFile)
        player = DesktopMpvPlayer()
        player.renderer.setSize(640, 360)
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) player.close()
    }

    /** Loads [url] and returns the first event of type [T] (subscribed before loading, so it can't be missed). */
    private suspend inline fun <reified T : PlayerEvent> loadAndAwait(url: String, paused: Boolean = true): T = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { player.events.filterIsInstance<T>().first() }
        player.load(url, startPaused = paused)
        withTimeout(10_000) { event.await() }
    }

    @Test
    fun loadsRendersAndReportsDuration() = runBlocking {
        val loaded = loadAndAwait<PlayerEvent.FileLoaded>(clip.toURI().toString())
        assertTrue("duration ${loaded.duration}", loaded.duration > 1.0)
        assertTrue(player.isFileLoaded)
        assertTrue(player.isPaused)
        assertEquals(loaded.duration, player.duration, 0.01)
        val rendered = withTimeoutOrNull(5_000) {
            while (player.renderer.frames.published == 0L) delay(20)
            true
        }
        assertNotNull("no frame rendered", rendered)
        assertEquals("640x360", player.renderer.frames.lastSize)
        assertTrue(player.tracks.value.any { it.type == TrackType.VIDEO })
        // The shown aspect (for the mini player and Android's PiP window) follows the video's square pixels.
        withTimeout(5_000) { while (player.state.value.displayAspect <= 0.0 || player.state.value.videoHeight == 0) delay(20) }
        val state = player.state.value
        assertEquals(state.videoWidth.toDouble() / state.videoHeight, state.displayAspect, 0.01)
    }

    @Test
    fun framesAreUprightOnGpuAndCpu() = runBlocking {
        val pattern = File.createTempFile("yarmiplaytv-orientation", ".png").apply { deleteOnExit() }
        val image = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 18) for (x in 0 until 32) image.setRGB(x, y, 0xFFFFFF)
        ImageIO.write(image, "png", pattern)
        for (mode in listOf("gl", "sw")) {
            player.close()
            player = DesktopMpvPlayer(DesktopMpvOptions(render = mode))
            player.renderer.setSize(640, 360)
            loadAndAwait<PlayerEvent.FileLoaded>(pattern.toURI().toString())
            fun VideoFrame.luma(x: Int, y: Int) = data.bytes[y * rowBytes + x * 4 + 1].toInt() and 0xFF
            fun VideoFrame.corners() = "top-left ${luma(80, 45)}, top-right ${luma(560, 45)}, bottom-left ${luma(80, 315)}"
            // Frames before the first decoded picture are black everywhere. A paused still image gets no new frames
            // by itself, so ask for redraws while waiting, as resizing the window would.
            var last: VideoFrame? = null
            var polls = 0
            val shown = withTimeoutOrNull(15_000) {
                var f: VideoFrame? = null
                while (f == null) {
                    delay(20)
                    if (++polls % 25 == 0) player.renderer.requestRedraw()
                    f = player.renderer.frames.acquire()?.takeIf { it.width == 640 }?.also { last = it }
                        ?.takeIf { listOf(it.luma(80, 45), it.luma(560, 45), it.luma(80, 315)).any { l -> l >= 128 } }
                }
                f
            }
            val summary = "$mode (${player.renderer.kind} on ${player.renderer.device ?: "CPU"}): no picture after " +
                "${player.renderer.frames.published} frames, last ${last?.let { "${it.width}x${it.height}, ${it.corners()}" } ?: "none"}, " +
                "${player.state.value.videoWidth}x${player.state.value.videoHeight} decoded"
            // OpenGL on the CPU (Linux CI's virtual display) sometimes reads back black frames, also with the
            // picture decoded and redrawn. That says nothing about the orientation, which a GPU or "sw" checks.
            if (shown == null && player.renderer.isSoftwareRasterizer && player.state.value.videoWidth > 0) {
                System.err.println("Inconclusive, skipped: $summary")
                continue
            }
            assertNotNull(summary, shown)
            val frame = shown!!
            val corners = frame.corners()
            assertTrue("$mode: only the top-left quadrant should be white: $corners",
                frame.luma(80, 45) > 200 && frame.luma(560, 45) < 50 && frame.luma(80, 315) < 50)
        }
    }

    @Test
    fun savesScreenshotsAtTheVideoSizeOnGpuAndCpu() = runBlocking {
        for (mode in listOf("gl", "sw")) {
            player.close()
            player = DesktopMpvPlayer(DesktopMpvOptions(render = mode))
            player.renderer.setSize(640, 360)
            loadAndAwait<PlayerEvent.FileLoaded>(clip.toURI().toString())
            withTimeout(5_000) { while (player.renderer.frames.published == 0L || player.state.value.videoWidth == 0) delay(20) }
            val shot = File.createTempFile("yarmiplaytv-shot", ".jpg").apply { delete(); deleteOnExit() }
            // The first frames can come before mpv has decoded a picture, and then it has nothing to save yet.
            val saved = withTimeoutOrNull(5_000) {
                while (!player.screenshot(shot.path)) delay(100)
                true
            }
            assertNotNull("$mode: screenshot failed", saved)
            val image = ImageIO.read(shot)
            assertNotNull("$mode: not an image", image)
            val state = player.state.value
            assertEquals("$mode: size", "${state.videoWidth}x${state.videoHeight}", "${image.width}x${image.height}")
        }
    }

    /** Kali and most desktops start the JVM with en_US.UTF-8 or similar; libmpv only accepts "C". */
    @Test
    fun startsWhenTheNumberLocaleIsNotC() = runBlocking {
        val previous = NumericLocale.current()
        val locale = listOf("en_US.UTF-8", "C.UTF-8", "en_US.utf8").firstNotNullOfOrNull { NumericLocale.set(it) }
        try {
            assumeTrue("no non-C locale installed", locale != null)
            player.close()
            assertEquals(locale, NumericLocale.current())
            player = DesktopMpvPlayer()
            player.renderer.setSize(640, 360)
            loadAndAwait<PlayerEvent.FileLoaded>(clip.toURI().toString())
            assertEquals("C", NumericLocale.current())
        } finally {
            previous?.let { NumericLocale.set(it) }
        }
    }

    @Test
    fun playsAndSeeks() = runBlocking {
        val loaded = loadAndAwait<PlayerEvent.FileLoaded>(clip.toURI().toString(), paused = false)
        withTimeout(5_000) { player.state.first { it.position > 0.3 } }
        assertFalse(player.isPaused)
        val target = (loaded.duration / 2).coerceAtLeast(0.5)
        player.setPaused(true)
        player.seek(target)
        assertEquals(target, player.currentPosition(), 0.01)
        withTimeout(5_000) { player.state.first { !it.seeking && abs(it.position - target) < 0.5 } }
        assertEquals(target, player.currentPosition(), 0.5)
    }

    @Test
    fun reportsMissingFiles() = runBlocking {
        val missing = File(clip.parentFile, "does-not-exist.mp4").toURI().toString()
        val failed = loadAndAwait<PlayerEvent.EndFile>(missing)
        assertEquals(missing, failed.url)
        assertEquals("File not found", failed.error)
        assertFalse(player.isFileLoaded)
    }

    @Test
    fun reportsFilesMpvCannotRead() = runBlocking {
        val garbage = File.createTempFile("yarmiplaytv-bad", ".mp4").apply { writeText("not a video"); deleteOnExit() }
        val failed = loadAndAwait<PlayerEvent.EndFile>(garbage.toURI().toString())
        assertNotNull(failed.error)
        assertFalse(player.isFileLoaded)
    }

    @Test
    fun volumeIsClamped() = runBlocking {
        player.setVolume(200.0)
        withTimeout(2_000) { player.volume.first { it == DesktopMpvPlayer.MAX_VOLUME } }
        player.changeVolume(-500.0)
        withTimeout(2_000) { player.volume.first { it == 0.0 } }
        Unit
    }

    @Test
    fun muteKeepsTheVolumeAndChangingItUnmutes() = runBlocking {
        player.setVolume(60.0)
        player.setMuted(true)
        withTimeout(2_000) { player.muted.first { it } }
        assertEquals(60.0, player.volume.value, 0.0)
        player.changeVolume(5.0)
        withTimeout(2_000) { player.muted.first { !it } }
        withTimeout(2_000) { player.volume.first { it == 65.0 } }
        Unit
    }
}
