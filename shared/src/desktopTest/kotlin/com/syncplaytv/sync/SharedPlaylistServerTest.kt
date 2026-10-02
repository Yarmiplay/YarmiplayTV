package com.syncplaytv.sync

import com.syncplaytv.AppContainer
import com.syncplaytv.FakePlayer
import com.syncplaytv.data.desktopSettingsStore
import com.syncplaytv.local.FileLocalLibrary
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.syncplay.SyncplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/**
 * The playlist controller in app containers; the room tests need a real syncplay-server and run with
 * SYNCPLAY_TEST_SERVER=host:port, e.g. after starting scripts/local-syncplay-server.ps1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedPlaylistServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val room = "playlist-" + System.nanoTime()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private val apps = mutableListOf<AppContainer>()
    private val alicePlayer = FakePlayer()
    private val bobPlayer = FakePlayer()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        onMain { apps.forEach { it.sync.disconnect() } }
        apps.forEach { it.scope.cancel() }
        // Main has to outlive the containers' coroutines, or the next test's setMain races them.
        runBlocking { withTimeout(5_000) { apps.forEach { it.scope.coroutineContext.job.join() } } }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    /** The containers' state belongs to the main thread, like in the app. */
    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(10_000) { while (!onMain(condition)) delay(20) } }
            .onFailure {
                val state = apps.joinToString { app ->
                    val saved = runBlocking { app.settingsStore.roomPlaylists() }
                    "${app.sync.room.value.username}: ${app.sync.room.value.status} ${app.sync.room.value.playlist}, saved $saved"
                }
                throw AssertionError("Timed out waiting for $what ($state)")
            }
    }

    private fun container(name: String, player: FakePlayer): AppContainer {
        val app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root.resolve(name), storeScope),
            deviceName = name,
            appVersion = "test",
            createPlayer = { player },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        apps += app
        return app
    }

    private fun join(name: String, player: FakePlayer): AppContainer {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        val app = container(name, player)
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        onMain { app.sync.connect(SyncplayConfig(host, port, name, room)) }
        awaitTrue("$name to join") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        return app
    }

    @Test
    fun `edits by anyone in the room can be undone, until the room changes`() {
        val alice = join("alice", alicePlayer)
        val bob = join("bob", bobPlayer)

        onMain { alice.playlist.shared.add(listOf("one.mkv", "two.mkv")) }
        awaitTrue("bob to see alice's entries") { bob.sync.room.value.playlist == listOf("one.mkv", "two.mkv") }
        onMain { bob.playlist.shared.move(0, 1) }
        awaitTrue("alice to see bob's move") { alice.sync.room.value.playlist == listOf("two.mkv", "one.mkv") }

        onMain { alice.playlist.shared.undo() }
        awaitTrue("bob's move to be undone") { bob.sync.room.value.playlist == listOf("one.mkv", "two.mkv") }
        onMain { alice.playlist.shared.undo() }
        awaitTrue("alice's add to be undone") { bob.sync.room.value.playlist.isEmpty() }
        assertEquals(false, onMain { alice.playlist.shared.canUndo.value })

        onMain { alice.playlist.shared.add(listOf("three.mkv")) }
        awaitTrue("the add to be undoable") { alice.playlist.shared.canUndo.value }
        onMain { alice.sync.changeRoom("$room-2") }
        awaitTrue("a new room to start a new history") { alice.sync.room.value.room == "$room-2" && !alice.playlist.shared.canUndo.value }
    }

    @Test
    fun `links on untrusted domains only open when the user says so`() {
        val alice = join("alice", alicePlayer)
        val bob = join("bob", bobPlayer)
        val untrusted = "https://media.example.org/clip.mp4"
        val trusted = "https://youtu.be/abc"

        onMain { alice.playlist.addUrlsToRoomPlaylist(listOf(untrusted, trusted, "not a url")) }
        awaitTrue("bob to see the links") { bob.sync.room.value.playlist == listOf(untrusted, trusted) }
        onMain { alice.playlist.selectIndex(0) }
        awaitTrue("bob to be asked") { bob.playlist.status.value == PlaylistStatus.Untrusted(untrusted, "media.example.org") }
        assertNull(bobPlayer.loaded)
        onMain { bob.playlist.playUntrusted() }
        awaitTrue("bob to open the link") { bobPlayer.loaded == untrusted }

        onMain { alice.playlist.selectIndex(1) }
        awaitTrue("bob to open the trusted link right away") { bobPlayer.loaded == trusted }
    }

    @Test
    fun `a room's playlist comes back when rejoining it empty`() {
        val alice = join("alice", alicePlayer)
        onMain { alice.playlist.shared.add(listOf("one.mkv", "two.mkv")) }
        awaitTrue("the playlist to be saved") { runBlocking { alice.settingsStore.roomPlaylist("${alice.sync.server}/$room") } == listOf("one.mkv", "two.mkv") }

        // Alone in a non-persistent room: leaving empties it on the server (once the server notices).
        onMain { alice.sync.disconnect() }
        Thread.sleep(1_000)
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        onMain { alice.sync.connect(SyncplayConfig(host, port, "alice", room)) }
        awaitTrue("the saved playlist to be put back") { alice.sync.room.value.playlist == listOf("one.mkv", "two.mkv") }
        assertEquals(true, onMain { alice.sync.feed.value.any { it.text == "Restored this room's playlist (2 entries)" } })

        // A room that already has a playlist keeps it, and that's what's remembered from then on.
        onMain { alice.sync.disconnect() }
        Thread.sleep(1_000)
        val bob = join("bob", bobPlayer)
        onMain { bob.playlist.shared.add(listOf("bob.mkv")) }
        onMain { alice.sync.connect(SyncplayConfig(host, port, "alice", room)) }
        awaitTrue("alice to rejoin") { alice.sync.room.value.status == ConnectionStatus.CONNECTED }
        awaitTrue("alice to remember bob's playlist") { runBlocking { alice.settingsStore.roomPlaylist("${alice.sync.server}/$room") } == listOf("bob.mkv") }
        assertEquals(listOf("bob.mkv"), alice.sync.room.value.playlist)

        // Emptying the playlist forgets it.
        onMain { alice.playlist.shared.remove(setOf(0)) }
        awaitTrue("the empty playlist to be forgotten") { runBlocking { alice.settingsStore.roomPlaylist("${alice.sync.server}/$room") }.isEmpty() }
    }

    @Test
    fun `nothing is restored with the setting off`() {
        val alice = join("alice", alicePlayer)
        val key = "${alice.sync.server}/$room"
        runBlocking { alice.settingsStore.saveRoomPlaylist(key, listOf("old.mkv")) }
        runBlocking { alice.settingsStore.saveAutosavePlaylists(false) }
        awaitTrue("the setting to apply") { !alice.settings.value.autosavePlaylists }
        onMain { alice.sync.changeRoom("$room-x"); alice.sync.changeRoom(room) }
        awaitTrue("alice back in the room") { alice.sync.room.value.room == room }
        Thread.sleep(1_000)
        assertEquals(emptyList<String>(), alice.sync.room.value.playlist)
        assertEquals(listOf("old.mkv"), runBlocking { alice.settingsStore.roomPlaylist(key) })
    }

    @Test
    fun `entries are available when they're in the media folders`() {
        val media = tmp.newFolder("Movies").apply { resolve("North Wind (2022).mkv").writeBytes(ByteArray(10)) }
        val alice = container("alice", alicePlayer)
        onMain { alice.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the folder to be indexed") { alice.local.files.value.isNotEmpty() }
        onMain {
            assertEquals(true, alice.playlist.isAvailable("North Wind (2022).mkv"))
            assertEquals(false, alice.playlist.isAvailable("South Wind (2023).mkv"))
            assertEquals(true, alice.playlist.isAvailable("https://example.org/a.mp4"))
        }
    }
}
