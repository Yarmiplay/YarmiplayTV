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
            // Frames before the first decoded picture are black everywhere.
            val frame = withTimeout(5_000) {
                var f: VideoFrame? = null
                while (f == null || listOf(f.luma(80, 45), f.luma(560, 45), f.luma(80, 315)).all { it < 128 }) {
                    delay(20)
                    f = player.renderer.frames.acquire()
                }
                f
            }
            val corners = "top-left ${frame.luma(80, 45)}, top-right ${frame.luma(560, 45)}, bottom-left ${frame.luma(80, 315)}"
            assertTrue("$mode: only the top-left quadrant should be white: $corners",
                frame.luma(80, 45) > 200 && frame.luma(560, 45) < 50 && frame.luma(80, 315) < 50)
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
