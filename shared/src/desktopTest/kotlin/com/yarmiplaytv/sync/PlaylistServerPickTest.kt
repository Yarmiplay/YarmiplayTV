package com.yarmiplaytv.sync

import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.Executors

class PlaylistServerPickTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecordingPlayer : Player {
        override val state = MutableStateFlow(PlaybackState())
        override val tracks = MutableStateFlow<List<Track>>(emptyList())
        override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
        val commands: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) { commands += "load $url @$startPosition" }
        override fun stop() { commands += "stop" }
        override fun setPaused(paused: Boolean) { commands += "pause $paused" }
        override fun seek(position: Double) { commands += "seek $position" }
        override fun seekRelative(offset: Double) { commands += "seekRelative $offset" }
        override fun setSpeed(speed: Double) = Unit
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun showText(text: String, durationMs: Int) = Unit
        override val isFileLoaded get() = state.value.fileLoaded
        override val isPaused get() = state.value.paused
        override val duration get() = state.value.duration
        override fun currentPosition() = state.value.position
    }

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private val player = RecordingPlayer()
    private val movie = "North Wind (2022).mkv"
    private val jellyfin = FakeMediaSource(JF, exact = mapOf(movie to 10))
    private val plex = FakeMediaSource(PX, exact = mapOf(movie to 10))

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        local = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, emptyList(), watchForChanges = false)
    }

    @After
    fun tearDown() {
        local.close()
        scope.cancel()
        main.close()
    }

    private fun awaitTrue(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(20) } }

    @Test
    fun `a pick with a saved resume point opens at the start and is reported to both servers`() {
        val servers = MutableStateFlow<List<MediaSource>>(listOf(jellyfin, plex))
        val playlist = PlaylistController(
            scope, SyncController(scope, player), player,
            MutableStateFlow(CompositeMediaSource(servers.value)), local, MediaLocator(local, servers),
        )
        val item = MediaItem("$PX:$movie", movie, MediaItemType.MOVIE, false, path = "/m/$movie", resumeSeconds = 600.0, sourceKey = PX)
        playlist.playHere(item)

        awaitTrue { player.commands.isNotEmpty() }
        assertEquals(listOf("load http://$PX/$movie @0.0"), player.commands.toList())
        awaitTrue { playlist.nowPlaying.value?.copies?.size == 2 }
        assertEquals(
            listOf(ServerCopy(PX, "$PX:$movie"), ServerCopy(JF, "$JF:$movie")),
            playlist.nowPlaying.value?.copies,
        )
        assertTrue(player.commands.none { it.startsWith("seek") })
    }
}
