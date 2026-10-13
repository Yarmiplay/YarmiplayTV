package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.media.SkipSegment.Kind.ENDING
import com.yarmiplaytv.media.SkipSegment.Kind.OPENING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Listening for openings and endings across a season's episodes. */
class SkipDetectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val settings = MutableStateFlow(AppSettings())
    private val both = setOf(OPENING, ENDING)

    private fun detector(season: FakeSeason, unmetered: Boolean = true) =
        SkipDetector(season.decoder, FingerprintCache(tmp.root.resolve("skip-detect")), season.siblings, settings) { unmetered }

    private fun assertPart(expected: SkipSegment, actual: SkipSegment?) {
        assertEquals(expected.kind, actual?.kind)
        assertEquals("start of $actual", expected.start, actual!!.start, 1.0)
        assertEquals("end of $actual", expected.end, actual.end, 0.6)
    }

    @Test
    fun `the song the episodes before and after share is the opening, and the one at the end the ending`() = runBlocking {
        val season = FakeSeason()
        val found = detector(season).detect(season.nowPlaying(2), season.duration, both)
        assertPart(SkipSegment(OPENING, 20.0, 110.0), found.firstOrNull { it.kind == OPENING })
        assertPart(SkipSegment(ENDING, 1300.0, 1390.0), found.firstOrNull { it.kind == ENDING })
        // Episode 2, then 3 and 1, the start and the end of each.
        assertEquals(listOf(2, 3, 1), season.reads.map { it.first }.distinct())
        assertEquals(6, season.reads.size)
    }

    @Test
    fun `the next episode needs only its own and its next one read, and again none`() = runBlocking {
        val season = FakeSeason()
        val detector = detector(season)
        detector.detect(season.nowPlaying(2), season.duration, both)
        season.reads.clear()

        val third = detector.detect(season.nowPlaying(3), season.duration, setOf(OPENING))
        assertPart(SkipSegment(OPENING, 30.0, 120.0), third.single())
        // Episode 3 was read already; 2 and 3 were compared; only 4 is new.
        assertEquals(listOf(4), season.reads.map { it.first })

        season.reads.clear()
        // Another run of the app: all from the cache.
        val again = detector(season).detect(season.nowPlaying(2), season.duration, both)
        assertEquals(2, again.size)
        assertTrue(season.reads.toString(), season.reads.isEmpty())
    }

    @Test
    fun `a server's episodes aren't read on a metered network unless the setting allows it`() = runBlocking {
        val season = FakeSeason(remote = true)
        assertTrue(detector(season, unmetered = false).detect(season.nowPlaying(2), season.duration, both).isEmpty())
        assertTrue(season.reads.isEmpty())

        settings.value = AppSettings(playback = PlaybackPrefs(detectOnAnyNetwork = true))
        assertEquals(2, detector(season, unmetered = false).detect(season.nowPlaying(2), season.duration, both).size)
    }

    @Test
    fun `episodes that share nothing have no opening`() = runBlocking {
        val season = FakeSeason(songSeconds = 0.0)
        assertTrue(detector(season).detect(season.nowPlaying(2), season.duration, setOf(OPENING)).isEmpty())
    }

    @Test
    fun `in a season of two the other episode is enough`() = runBlocking {
        val season = FakeSeason(count = 2)
        val found = detector(season).detect(season.nowPlaying(1), season.duration, setOf(OPENING))
        assertPart(SkipSegment(OPENING, 10.0, 100.0), found.single())
    }

    @Test
    fun `each episode's opening is found wherever it has it`() = runBlocking {
        // Episode 3 has a longer cold open than the others.
        val season = FakeSeason(openingAt = { n -> if (n == 3) 60.0 else 20.0 })
        val found = detector(season).detect(season.nowPlaying(2), season.duration, setOf(OPENING))
        assertPart(SkipSegment(OPENING, 20.0, 110.0), found.single())
    }

    @Test
    fun `cancelling stops reading`() = runBlocking {
        val season = FakeSeason().apply { hang = true }
        val job = async(Dispatchers.Default) { detector(season).detect(season.nowPlaying(2), season.duration, both) }
        while (season.reads.isEmpty()) delay(10)
        job.cancel()
        while (!season.sawCancel) delay(10)
        assertTrue(job.isCancelled)
    }
}
