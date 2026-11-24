package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/**
 * A spot this device remembers in a file: in a room a playlist switch starts at 0 like everyone else, as in Syncplay,
 * instead of jumping there alone where the room can't see it. Outside a room the file resumes there.
 */
class RoomResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    private class StartRecordingPlayer : FakePlayer() {
        val loads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val seeks: MutableList<Double> = Collections.synchronizedList(ArrayList())
        override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) {
            super.load(url, mediaTitle, startPaused, startPosition)
            loads += "$url @$startPosition"
        }
        override fun seek(position: Double) {
            seeks += position
        }
    }

    private val files = listOf("One.mkv", "Two.mkv")
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private lateinit var server: ScriptedRoom
    private val player = StartRecordingPlayer()
    private val jellyfin = FakeMediaSource(JF, exact = files.associateWith { 10L })

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        local = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, emptyList(), watchForChanges = false)
        server = ScriptedRoom(files)
    }

    @After
    fun tearDown() {
        server.close()
        local.close()
        scope.cancel()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(10_000) { while (!onMain(condition)) delay(20) } }
            .onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    private fun controllers(): Pair<SyncController, PlaylistController> {
        val sync = SyncController(scope, player)
        val servers = MutableStateFlow<List<MediaSource>>(listOf(jellyfin))
        val playlist = PlaylistController(scope, sync, player, MutableStateFlow(CompositeMediaSource(servers.value)), local, MediaLocator(local, servers))
        return sync to playlist
    }

    @Test
    fun `in a room a playlist switch opens at 0 even where this device stopped before`() {
        val (sync, playlist) = controllers()
        onMain { playlist.saveSeekPosition("Two.mkv", 36.5) }
        onMain { sync.connect(SyncplayConfig("127.0.0.1", server.port, "ana", "movie night", useTls = false)) }
        awaitTrue("the room") { sync.room.value.status == ConnectionStatus.CONNECTED && sync.room.value.playlistIndex == 0 }
        awaitTrue("the first entry to load") { player.loaded == "http://$JF/One.mkv" }
        onMain { player.finishLoading(100.0) }
        awaitTrue("the first entry to open") { playlist.status.value == PlaylistStatus.Idle }

        server.select(1, by = "bob")
        awaitTrue("the switch to load") { player.loaded == "http://$JF/Two.mkv" }
        onMain { player.finishLoading(100.0) }
        awaitTrue("the switch to open") { playlist.status.value == PlaylistStatus.Idle }
        delay200()

        assertEquals("http://$JF/Two.mkv @0.0", player.loads.last())
        assertTrue("never jumps to the remembered spot: ${player.seeks}", player.seeks.none { it > 0.0 })
    }

    @Test
    fun `outside a room a file opens where this device stopped before`() {
        val (_, playlist) = controllers()
        onMain { playlist.saveSeekPosition("Two.mkv", 36.5) }
        onMain { playlist.playHere(MediaItem("$JF:Two.mkv", "Two.mkv", MediaItemType.MOVIE, false, path = "/m/Two.mkv", sourceKey = JF)) }
        awaitTrue("the file to load") { player.loaded == "http://$JF/Two.mkv" }
        assertEquals("http://$JF/Two.mkv @36.5", player.loads.last())
        onMain { player.finishLoading(100.0) }
        awaitTrue("the file to open") { playlist.status.value == PlaylistStatus.Idle }
        delay200()
        assertEquals(listOf(36.5), player.seeks.toList())
    }

    private fun delay200() = runBlocking { delay(200) }

    private companion object {
        const val JF = "jellyfin:home"
    }
}
