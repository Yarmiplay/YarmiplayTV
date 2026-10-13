package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.tracking.detect.EpisodeFile
import com.yarmiplaytv.tracking.detect.EpisodeSiblings
import com.yarmiplaytv.tracking.detect.FingerprintCache
import com.yarmiplaytv.tracking.detect.SiblingFinder
import com.yarmiplaytv.tracking.detect.Siblings
import com.yarmiplaytv.tracking.detect.SkipDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Listening for the opening through real libmpv: made-up 44.1 kHz stereo episodes (a WAV each) that share an
 * opening song, read by mpv and fingerprinted (set YARMIPLAYTV_SKIP_MPV_TESTS=1 to skip).
 */
class ListeningTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        assumeTrue("libmpv tests skipped", System.getenv("YARMIPLAYTV_SKIP_MPV_TESTS").isNullOrEmpty())
    }

    private val rate = 44_100
    private val seconds = 120

    /** Episode [n]: its own chords, with the opening's 20 s at 3 + 2n seconds. */
    private fun episode(n: Int): File {
        val opening = 3.0 + 2 * n
        val file = tmp.root.resolve("Show - %02d.wav".format(n))
        val frames = seconds * rate
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF"); le32(36 + frames * 4); out.writeBytes("WAVEfmt "); le32(16); le16(1); le16(2)
            le32(rate); le32(rate * 4); le16(4); le16(16); out.writeBytes("data"); le32(frames * 4)
            val perChord = (0.45 * rate).toInt()
            var freqs = DoubleArray(0)
            var chord = -1
            var seed = -1
            for (i in 0 until frames) {
                val t = i.toDouble() / rate
                val inOpening = t >= opening && t < opening + 20
                val local = if (inOpening) ((t - opening) * rate).toInt() else i
                val s = if (inOpening) 7 else 100 + n
                val c = local / perChord
                if (c != chord || s != seed) {
                    chord = c
                    seed = s
                    val random = Random(s * 1_000_003L + c * 7919L)
                    val root = 40 + random.nextInt(24)
                    val notes = intArrayOf(root, root + 3 + random.nextInt(2), root + 7, root + 12 - random.nextInt(3) * 2)
                    freqs = DoubleArray(notes.size) { 440.0 * 2.0.pow((notes[it] - 69) / 12.0) }
                }
                val inChord = local - c * perChord
                val tc = inChord.toDouble() / rate
                var v = 0.0
                for (f in freqs) v += sin(2 * PI * f * tc) + 0.4 * sin(4 * PI * f * tc)
                val sample = (v / freqs.size * minOf(1.0, inChord / (0.02 * rate)) * 0.25 * Short.MAX_VALUE).toInt()
                le16(sample)
                le16(sample)
            }
        }
        return file
    }

    @Test
    fun `mpv's sound of made-up episodes gives their shared opening`() = runBlocking {
        val files = (1..4).associateWith(::episode)
        fun episodeFile(n: Int) = files.getValue(n).let { EpisodeFile(n, FileLocalLibrary.uriOf(it.toPath()), it.name, it.length(), null, remote = false) }
        val siblings = SiblingFinder { Siblings(2, EpisodeSiblings.nearestFirst(2, listOf(1, 3, 4).map(::episodeFile))) }
        val detector = SkipDetector(MpvAudioDecoder, FingerprintCache(tmp.newFolder("cache")), siblings, MutableStateFlow(AppSettings())) { true }
        val me = episodeFile(2)
        val np = NowPlaying("Show 2", me.fileName, me.sizeBytes, seconds.toDouble(), me.url, episode = EpisodeInfo("Show", 1, 2, seriesKey = "file/show"))
        val found = detector.detect(np, seconds.toDouble(), setOf(SkipSegment.Kind.OPENING)).single()
        assertEquals("start of $found", 7.0, found.start, 1.0)
        assertEquals("end of $found", 27.0, found.end, 1.0)
    }
}
