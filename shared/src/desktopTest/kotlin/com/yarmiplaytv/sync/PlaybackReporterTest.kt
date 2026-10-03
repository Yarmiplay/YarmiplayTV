package com.yarmiplaytv.sync

import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.ReportState
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    /** Only exposes state: the reporter must never command playback. */
    private class WatchOnlyPlayer : Player {
        override val state = MutableStateFlow(PlaybackState())
        override val tracks = MutableStateFlow<List<Track>>(emptyList())
        override val events = MutableSharedFlow<PlayerEvent>()
        private fun forbidden(): Nothing = throw AssertionError("The reporter must not control the player")
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) = forbidden()
        override fun stop() = forbidden()
        override fun setPaused(paused: Boolean) = forbidden()
        override fun seek(position: Double) = forbidden()
        override fun seekRelative(offset: Double) = forbidden()
        override fun setSpeed(speed: Double) = forbidden()
        override fun selectTrack(type: TrackType, id: Int?) = forbidden()
        override fun showText(text: String, durationMs: Int) = forbidden()
        override val isFileLoaded get() = state.value.fileLoaded
        override val isPaused get() = state.value.paused
        override val duration get() = state.value.duration
        override fun currentPosition() = state.value.position
    }

    private val player = WatchOnlyPlayer()
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val jellyfin = FakeMediaSource(JF)
    private val plex = FakeMediaSource(PX)
    private val servers = MutableStateFlow<List<MediaSource>>(listOf(jellyfin, plex))
    private val both = listOf(ServerCopy(JF, "j1"), ServerCopy(PX, "p1"))

    private fun TestScope.reporter() = PlaybackReporter(backgroundScope, player, nowPlaying, servers, heartbeatMs = 10_000).also { runCurrent() }

    private fun TestScope.play(url: String = "http://jellyfin/a.mkv", copies: List<ServerCopy> = both, paused: Boolean = false) {
        nowPlaying.value = NowPlaying("A", "a.mkv", 10, 100.0, url, copies = copies)
        player.state.value = PlaybackState(url = url, fileLoaded = true, duration = 100.0, paused = paused)
        runCurrent()
    }

    private fun TestScope.update(block: PlaybackState.() -> PlaybackState) {
        player.state.value = player.state.value.block()
        runCurrent()
    }

    private fun FakeMediaSource.states() = reports.map { it.state }

    @Test
    fun startsPausesAndStopsOnEveryServerWithTheFile() = runTest {
        reporter()
        play()
        update { copy(position = 30.0, paused = true) }
        update { copy(paused = false) }
        nowPlaying.value = null
        runCurrent()

        val expected = listOf(ReportState.STARTED, ReportState.PAUSED, ReportState.PLAYING, ReportState.STOPPED)
        assertEquals(expected, jellyfin.states())
        assertEquals(expected, plex.states())
        assertEquals(listOf("j1"), jellyfin.reports.map { it.itemId }.distinct())
        assertEquals(listOf("p1"), plex.reports.map { it.itemId }.distinct())
        assertEquals(1, (jellyfin.reports + plex.reports).map { it.sessionId }.distinct().size)
        assertEquals(30.0, jellyfin.reports[1].positionSeconds, 0.0)
    }

    @Test
    fun twoServersOfOneKindEachGetTheirOwnReports() = runTest {
        val office = FakeMediaSource(JF2)
        servers.value = listOf(jellyfin, office)
        reporter()
        play(copies = listOf(ServerCopy(JF, "j1"), ServerCopy(JF2, "j2")))
        update { copy(position = 30.0, paused = true) }

        assertEquals(listOf(ReportState.STARTED, ReportState.PAUSED), jellyfin.states())
        assertEquals(listOf(ReportState.STARTED, ReportState.PAUSED), office.states())
        assertEquals(listOf("j1"), jellyfin.reports.map { it.itemId }.distinct())
        assertEquals(listOf("j2"), office.reports.map { it.itemId }.distinct())
    }

    @Test
    fun heartbeatOnlyWhilePlaying() = runTest {
        reporter()
        play()
        advanceTimeBy(10_001)
        assertEquals(listOf(ReportState.STARTED, ReportState.PLAYING), jellyfin.states())
        update { copy(paused = true) }
        advanceTimeBy(30_000)
        assertEquals(listOf(ReportState.STARTED, ReportState.PLAYING, ReportState.PAUSED), jellyfin.states())
    }

    @Test
    fun reportsASeekOnceItSettles() = runTest {
        reporter()
        play()
        update { copy(seeking = true, position = 70.0) }
        assertEquals(listOf(ReportState.STARTED), jellyfin.states())
        update { copy(seeking = false, position = 70.0) }
        assertEquals(ReportState.PLAYING, jellyfin.reports.last().state)
        assertEquals(70.0, jellyfin.reports.last().positionSeconds, 0.0)
    }

    @Test
    fun marksWatchedOnceAtNinetyPercent() = runTest {
        reporter()
        play()
        update { copy(position = 89.0) }
        assertFalse(jellyfin.reports.any { it.markWatched })
        update { copy(position = 91.0) }
        update { copy(position = 95.0, paused = true) }
        assertEquals(1, jellyfin.reports.count { it.markWatched })
        assertEquals(1, plex.reports.count { it.markWatched })
    }

    @Test
    fun endOfFileStopsAtTheEnd() = runTest {
        reporter()
        play()
        update { copy(position = 99.5, eofReached = true, fileLoaded = false) }
        val stop = jellyfin.reports.last()
        assertEquals(ReportState.STOPPED, stop.state)
        assertEquals(100.0, stop.positionSeconds, 0.0)
    }

    @Test
    fun nextFileStartsANewSession() = runTest {
        reporter()
        play()
        play(url = "http://jellyfin/b.mkv")
        assertEquals(listOf(ReportState.STARTED, ReportState.STOPPED, ReportState.STARTED), jellyfin.states())
        assertNotEquals(jellyfin.reports[0].sessionId, jellyfin.reports[2].sessionId)
    }

    @Test
    fun copiesAddedLaterGetStarted() = runTest {
        reporter()
        play(copies = both.take(1))
        assertTrue(plex.reports.isEmpty())
        nowPlaying.value = nowPlaying.value!!.copy(copies = both)
        runCurrent()
        assertEquals(listOf(ReportState.STARTED), plex.states())
        assertEquals(listOf(ReportState.STARTED), jellyfin.states())
    }

    @Test
    fun skipsServersThatAreNotConnectedAndSurvivesFailures() = runTest {
        val failing = FakeMediaSource(JF, failReports = true)
        servers.value = listOf(failing)
        reporter()
        play()
        update { copy(paused = true) }
        assertTrue(plex.reports.isEmpty())
    }

    @Test
    fun localFilesAndDisabledReportingSendNothing() = runTest {
        val reporter = reporter()
        play(copies = emptyList())
        assertTrue(jellyfin.reports.isEmpty())
        reporter.enabled = false
        play(url = "http://jellyfin/b.mkv")
        update { copy(paused = true) }
        assertTrue(jellyfin.reports.isEmpty() && plex.reports.isEmpty())
    }
}
