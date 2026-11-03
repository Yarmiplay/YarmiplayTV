package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Search again after a playlist entry's search gave up, and looking again when the player comes back. */
class PlaylistSearchAgainTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var media: File
    private lateinit var local: FileLocalLibrary
    private lateinit var server: ScriptedRoom
    private val player = FakePlayer()
    /** What the media server has; empty until a test adds the file. */
    private val onServer = ConcurrentHashMap<String, Long>()
    private val jellyfin = FakeMediaSource(JF, exact = onServer)

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        media = tmp.newFolder("Movies")
        local = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, listOf(FileLocalLibrary.uriOf(media.toPath())), watchForChanges = false)
        server = ScriptedRoom(listOf(FILE))
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

    /** Joins the room, whose selected entry is found nowhere yet. */
    private fun joinAndGiveUp(): Pair<SyncController, PlaylistController> {
        val sync = SyncController(scope, player)
        val servers = MutableStateFlow<List<MediaSource>>(listOf(jellyfin))
        val playlist = PlaylistController(scope, sync, player, MutableStateFlow(CompositeMediaSource(servers.value)), local, MediaLocator(local, servers))
        onMain { sync.connect(SyncplayConfig("127.0.0.1", server.port, "ana", "movie night", useTls = false)) }
        awaitTrue("the room") { sync.room.value.status == ConnectionStatus.CONNECTED && sync.room.value.playlistIndex == 0 }
        awaitTrue("the search to give up") { playlist.status.value is PlaylistStatus.NotFound }
        assertEquals(FILE, onMain { playlist.searchableEntry() })
        return sync to playlist
    }

    private fun errors(sync: SyncController) = sync.feed.value.count { it.isError }

    @Test
    fun `Search again finds a file added to the media folders, before the server's copy`() {
        val (_, playlist) = joinAndGiveUp()
        val copy = File(media, FILE).apply { writeBytes(ByteArray(2048)) }
        onServer[FILE] = 2048L

        assertTrue(onMain { playlist.searchAgain() })
        awaitTrue("the local copy to load") { player.loaded == FileLocalLibrary.uriOf(copy.toPath()) }
        assertTrue(playlist.status.value is PlaylistStatus.Loading)
        assertNull("nothing left to search for", onMain { playlist.searchableEntry() })
    }

    @Test
    fun `Search again falls back to the media server`() {
        val (_, playlist) = joinAndGiveUp()
        onServer[FILE] = 2048L
        assertTrue(onMain { playlist.searchAgain() })
        awaitTrue("the server's copy to load") { player.loaded == "http://$JF/$FILE" }
    }

    @Test
    fun `Search again that still finds nothing gives up again, with the same card`() {
        val (sync, playlist) = joinAndGiveUp()
        val before = errors(sync)
        val looked = jellyfin.lookedUp.size
        assertTrue(onMain { playlist.searchAgain() })
        awaitTrue("a second lookup") { jellyfin.lookedUp.size > looked }
        awaitTrue("the search to give up again") { playlist.status.value is PlaylistStatus.NotFound }
        awaitTrue("the message saying so") { errors(sync) == before + 1 }
        assertEquals(FILE, onMain { playlist.searchableEntry() })
    }

    @Test
    fun `coming back to the player looks again for a given-up file, also after a dismiss, without a second message`() {
        val (sync, playlist) = joinAndGiveUp()
        onMain { playlist.dismissStatus() }
        assertEquals("the empty player offers it", FILE, onMain { playlist.searchableEntry() })
        val before = errors(sync)
        val looked = jellyfin.lookedUp.size
        assertTrue(onMain { playlist.retryGivenUp() })
        awaitTrue("a second lookup") { jellyfin.lookedUp.size > looked }
        awaitTrue("the search to give up again") { playlist.status.value is PlaylistStatus.NotFound }
        Thread.sleep(200)
        assertEquals("quiet", before, errors(sync))

        onServer[FILE] = 2048L
        assertTrue(onMain { playlist.retryGivenUp() })
        awaitTrue("the server's copy to load") { player.loaded == "http://$JF/$FILE" }
        assertFalse("found: nothing given up any more", onMain { playlist.retryGivenUp() })
    }

    @Test
    fun `a manual pick isn't undone by looking again`() {
        val (_, playlist) = joinAndGiveUp()
        onMain { playlist.resolveManually(MediaItem("$JF:Other.mkv", "Other.mkv", MediaItemType.MOVIE, false, sourceKey = JF)) }
        assertFalse(onMain { playlist.retryGivenUp() })
        awaitTrue("the pick to load") { player.loaded == "http://$JF/Other.mkv" }
    }

    @Test
    fun `only the room's files can be searched for again`() {
        val room = RoomState(status = ConnectionStatus.CONNECTED, playlist = listOf(FILE, "https://example.com/a.mp4"), playlistIndex = 0)
        assertEquals(FILE, PlaylistController.searchableEntry(PlaylistStatus.NotFound(FILE, "Not found"), RoomState(), null))
        assertEquals(FILE, PlaylistController.searchableEntry(PlaylistStatus.Idle, room, null))
        assertNull("already playing here", PlaylistController.searchableEntry(PlaylistStatus.Idle, room, FILE.lowercase()))
        assertEquals("another file plays here", FILE, PlaylistController.searchableEntry(PlaylistStatus.Idle, room, "Else.mkv"))
        assertNull("being looked up", PlaylistController.searchableEntry(PlaylistStatus.Resolving(FILE), room, null))
        assertNull("a URL", PlaylistController.searchableEntry(PlaylistStatus.Idle, room.copy(playlistIndex = 1), null))
        assertNull("not in a room", PlaylistController.searchableEntry(PlaylistStatus.Idle, room.copy(status = ConnectionStatus.DISCONNECTED), null))
        assertNull("nothing selected", PlaylistController.searchableEntry(PlaylistStatus.Idle, room.copy(playlistIndex = null), null))
    }

    private companion object {
        const val FILE = "Harbor Lights S02E05.mkv"
    }
}
