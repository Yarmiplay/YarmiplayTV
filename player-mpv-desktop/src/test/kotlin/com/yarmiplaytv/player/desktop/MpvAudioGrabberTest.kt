package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real libmpv on the test clip (set YARMIPLAYTV_SKIP_MPV_TESTS=1 to skip). */
class MpvAudioGrabberTest {
    private val clip = File(System.getProperty("yarmiplaytv.testClip") ?: "")

    @Before
    fun setUp() {
        assumeTrue("libmpv tests skipped", System.getenv("YARMIPLAYTV_SKIP_MPV_TESTS").isNullOrEmpty())
        assumeTrue("test clip missing: $clip", clip.isFile)
    }

    private fun temps() = File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("yarmiplaytv-sound") }!!.toSet()

    @Test
    fun knowsHowLongTheVideoIs() {
        val duration = MpvAudioGrabber.duration(clip.path)
        assertNotNull(duration)
        assertTrue("$duration s", duration!! > 2)
        assertNull(MpvAudioGrabber.duration(File(clip.parentFile, "no such clip.mp4").path))
    }

    @Test
    fun decodesPartOfTheSoundAsMono11025HzFasterThanItPlays() {
        val before = temps()
        var samples = 0
        var loud = 0
        val began = System.nanoTime()
        val ok = MpvAudioGrabber.grab(clip.path, 0.5, 2.0, 11025, "jpn,ja", { pcm, n ->
            samples += n
            for (i in 0 until n) if (kotlin.math.abs(pcm[i].toInt()) > 100) loud++
        }) { false }
        val took = (System.nanoTime() - began) / 1e9
        assertTrue(ok)
        assertEquals(22050.0, samples.toDouble(), 600.0)
        assertTrue("the clip has sound", loud > 1000)
        assertTrue("took $took s for 2 s of sound", took < 2.0)
        assertEquals("nothing left behind", before, temps())
    }

    @Test
    fun stopsWhenCancelled() {
        var asked = 0
        assertFalse(MpvAudioGrabber.grab(clip.path, 0.0, 2.0, 11025, "", { _, _ -> }) { ++asked > 0 })
    }

    @Test
    fun aMissingFileHasNoSound() {
        assertFalse(MpvAudioGrabber.grab(File(clip.parentFile, "no such clip.mp4").path, 0.0, 2.0, 11025, "", { _, _ -> }) { false })
    }
}
