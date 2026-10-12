package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.CompositeMediaSource
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The player's next/previous buttons, and looking up the playlist's next entry while one plays. */
class PlaylistNextTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = listOf("One.mkv", "Two.mkv", "Three.mkv")
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private lateinit var server: ScriptedRoom
    private val player = FakePlayer()
    private val jellyfin = FakeMediaSource(JF, exact = files.associateWith { 10L }, lookupDelayMs = 200)

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

    private fun join(): Pair<SyncController, PlaylistController> {
        val sync = SyncController(scope, player)
        val servers = MutableStateFlow<List<MediaSource>>(listOf(jellyfin))
        val playlist = PlaylistController(scope, sync, player, MutableStateFlow(CompositeMediaSource(servers.value)), local, MediaLocator(local, servers))
        onMain { sync.connect(SyncplayConfig("127.0.0.1", server.port, "ana", "movie night", useTls = false)) }
        awaitTrue("the room") { sync.room.value.status == ConnectionStatus.CONNECTED && sync.room.value.playlistIndex == 0 }
        return sync to playlist
    }

    @Test
    fun `the next entry is looked up while one plays, so moving on doesn't search again`() {
        val (_, playlist) = join()
        awaitTrue("the first entry to load") { player.loaded == "http://$JF/One.mkv" }
        onMain { player.finishLoading(100.0) }
        awaitTrue("the next entry to be looked up") { "Two.mkv" in jellyfin.lookedUp }

        server.select(1, by = "bob")
        awaitTrue("the next entry to load") { player.loaded == "http://$JF/Two.mkv" }
        assertEquals("looked up once, ahead of time", 1, jellyfin.lookedUp.count { it == "Two.mkv" })
        // And then the one after it.
        onMain { player.finishLoading(100.0) }
        awaitTrue("the third entry to be looked up") { "Three.mkv" in jellyfin.lookedUp }
        assertEquals(PlaylistStatus.Idle, playlist.status.value)
    }

    @Test
    fun `an entry selected while its lookup is under way waits for it instead of searching again`() {
        join()
        awaitTrue("the first entry to load") { player.loaded == "http://$JF/One.mkv" }
        onMain { player.finishLoading(100.0) }
        awaitTrue("the next entry's lookup to start") { "Two.mkv" in jellyfin.lookedUp }
        server.select(1, by = "bob")
        awaitTrue("the next entry to load") { player.loaded == "http://$JF/Two.mkv" }
        assertEquals(1, jellyfin.lookedUp.count { it == "Two.mkv" })
    }

    @Test
    fun `next and previous select the neighbouring entries for the room`() {
        val (sync, playlist) = join()
        assertNull("nothing before the first entry", onMain { playlist.skipTarget(sync.room.value, forward = false) })
        onMain { playlist.skip(true) }
        awaitTrue("the room to move on") { sync.room.value.playlistIndex == 1 }
        assertEquals(listOf(1), server.selections.toList())
        awaitTrue("the second entry to load") { player.loaded == "http://$JF/Two.mkv" }
        onMain { playlist.skip(false) }
        awaitTrue("the room to go back") { sync.room.value.playlistIndex == 0 }
        assertEquals(listOf(1, 0), server.selections.toList())
    }

    @Test
    fun `skipping stops at the ends unless the playlist loops`() {
        assertEquals(1, PlaylistController.skipTarget(3, 0, forward = true, loop = false))
        assertEquals(null, PlaylistController.skipTarget(3, 2, forward = true, loop = false))
        assertEquals(0, PlaylistController.skipTarget(3, 2, forward = true, loop = true))
        assertEquals(null, PlaylistController.skipTarget(3, 0, forward = false, loop = false))
        assertEquals(2, PlaylistController.skipTarget(3, 0, forward = false, loop = true))
        assertEquals(null, PlaylistController.skipTarget(1, 0, forward = true, loop = true))
        assertEquals(0, PlaylistController.skipTarget(2, null, forward = true, loop = false))
        assertEquals(null, PlaylistController.skipTarget(0, null, forward = true, loop = true))
    }

    /** A Syncplay room with one client: the playlist [files] with the first selected; selections are echoed back. */
    private class ScriptedRoom(private val files: List<String>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        val selections: MutableList<Int> = Collections.synchronizedList(ArrayList())
        @Volatile private var out: PrintWriter? = null

        init {
            thread(isDaemon = true) {
                runCatching {
                    val client: Socket = socket.accept()
                    val reader: BufferedReader = client.getInputStream().bufferedReader()
                    val writer = PrintWriter(client.getOutputStream().writer(), true)
                    out = writer
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.contains("\"Hello\"") -> {
                                send(
                                    """{"Hello":{"username":"ana","room":{"name":"movie night"},"version":"1.2.255","realversion":"1.7.0",""" +
                                        """"features":{"sharedPlaylists":true,"chat":true,"readiness":true},"motd":""}}""",
                                )
                                val list = files.joinToString(",") { "\"$it\"" }
                                send("""{"Set":{"playlistChange":{"user":"bob","files":[$list]}}}""")
                                select(0, by = "bob")
                            }
                            line.contains("\"playlistIndex\"") -> {
                                val index = Regex("\"index\":\\s*(\\d+)").find(line)!!.groupValues[1].toInt()
                                selections += index
                                select(index, by = "ana")
                            }
                        }
                    }
                }
            }
        }

        fun select(index: Int, by: String) = send("""{"Set":{"playlistIndex":{"user":"$by","index":$index}}}""")

        @Synchronized
        private fun send(json: String) {
            out?.apply { print(json + "\r\n"); flush() }
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val JF = "jellyfin:home"
    }
}
