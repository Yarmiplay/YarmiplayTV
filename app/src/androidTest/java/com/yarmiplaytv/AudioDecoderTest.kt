package com.yarmiplaytv

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yarmiplaytv.tracking.detect.AndroidAudioDecoder
import com.yarmiplaytv.tracking.detect.Fingerprinter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The platform's decoders read the clip's sound for listening for openings and endings, as mono 11025 Hz. */
@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val clip = File(target.cacheDir, "audio-decoder-test.mp4")
    private val decoder = AndroidAudioDecoder(target)

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().context.assets.open(TestSupport.CLIP_ASSET).use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun tearDown() {
        clip.delete()
    }

    @Test
    fun readsTheLengthAndPartOfTheSound() {
        val uri = Uri.fromFile(clip).toString()
        val duration = decoder.duration(uri)
        assertNotNull(duration)
        assertTrue("$duration s", duration!! > 2)

        var samples = 0
        var loud = 0
        val start = decoder.decode(uri, 0.5, 2.0, listOf("jpn", "ja"), { pcm, n ->
            samples += n
            for (i in 0 until n) if (abs(pcm[i].toInt()) > 100) loud++
        }) { false }
        assertNotNull(start)
        assertEquals("starts at the frame before", 0.5, start!!, 0.1)
        assertEquals(2.0 * Fingerprinter.SAMPLE_RATE, samples.toDouble(), 0.15 * Fingerprinter.SAMPLE_RATE)
        assertTrue("the clip has sound", loud > 1000)
    }

    @Test
    fun aMissingFileHasNoSound() {
        val missing = Uri.fromFile(File(target.cacheDir, "no such clip.mp4")).toString()
        assertNull(decoder.duration(missing))
        assertNull(decoder.decode(missing, 0.0, 2.0, emptyList(), { _, _ -> }) { false })
    }

    @Test
    fun stopsWhenCancelled() {
        assertNull(decoder.decode(Uri.fromFile(clip).toString(), 0.0, 2.0, emptyList(), { _, _ -> }) { true })
    }
}
