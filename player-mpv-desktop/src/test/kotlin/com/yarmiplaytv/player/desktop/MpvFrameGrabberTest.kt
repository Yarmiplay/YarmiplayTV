package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** Real libmpv on the test clip (set YARMIPLAYTV_SKIP_MPV_TESTS=1 to skip). Needs no display: mpv writes the frame itself. */
class MpvFrameGrabberTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clip = File(System.getProperty("yarmiplaytv.testClip") ?: "")

    @Before
    fun setUp() {
        assumeTrue("libmpv tests skipped", System.getenv("YARMIPLAYTV_SKIP_MPV_TESTS").isNullOrEmpty())
        assumeTrue("test clip missing: $clip", clip.isFile)
    }

    @Test
    fun writesAScaledJpegOfAFrame() {
        val target = tmp.root.resolve("frame.part")
        assertTrue(MpvFrameGrabber.grab(clip.path, 320, target))
        val image = ImageIO.read(target)
        assertNotNull("not a readable image", image)
        assertEquals(320, image.width)
        assertTrue("height ${image.height}", image.height in 100..320 && image.height % 2 == 0)
        // Nothing left behind but the frame itself.
        assertEquals(listOf("frame.part"), tmp.root.list()!!.toList())
    }

    @Test
    fun handlesPathsWithSpacesAndNonAsciiNames() {
        val copy = tmp.newFolder("Shows", "Ünïcode Season 1").resolve("Episode 01 – pilot.mp4")
        clip.copyTo(copy)
        val target = tmp.root.resolve("frame.jpg")
        assertTrue(MpvFrameGrabber.grab(copy.path, 160, target))
        assertEquals(160, ImageIO.read(target).width)
    }

    @Test
    fun failsForAFileThatIsNotAVideo() {
        val notVideo = tmp.newFile("notes.mkv").apply { writeText("not a video") }
        val target = tmp.root.resolve("frame.jpg")
        assertFalse(MpvFrameGrabber.grab(notVideo.path, 320, target))
        assertFalse(target.exists())
    }

    @Test
    fun failsForAMissingFile() {
        assertFalse(MpvFrameGrabber.grab(tmp.root.resolve("gone.mkv").path, 320, tmp.root.resolve("frame.jpg")))
    }
}
