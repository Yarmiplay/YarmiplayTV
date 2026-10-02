package com.yarmiplaytv.sync

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.SyncplayConfig
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Edits a playlist and chats with the official Syncplay client (no-GUI mode) in one room. Needs
 * SYNCPLAY_TEST_SERVER=host:port, SYNCPLAY_CONSOLE pointing at Syncplay's syncplayClient.py (run with
 * SYNCPLAY_CONSOLE_PYTHON, default "python") or an installed client, and SYNCPLAY_CONSOLE_FILE, a video it
 * plays. SYNCPLAY_CONSOLE_PLAYER is the mpv it drives (default "mpv"), run without video or audio output.
 * The Windows SyncplayConsole.exe 1.7.x can't be used: it never sends commands typed into its console.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfficialClientInteropTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val console = System.getenv("SYNCPLAY_CONSOLE").orEmpty()
    private val video = System.getenv("SYNCPLAY_CONSOLE_FILE").orEmpty()
    private val room = "interop-" + System.nanoTime()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private var app: AppContainer? = null
    private var official: Process? = null
    private val officialOutput = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        assumeTrue(
            "SYNCPLAY_TEST_SERVER, SYNCPLAY_CONSOLE or SYNCPLAY_CONSOLE_FILE not set",
            server.isNotBlank() && File(console).exists() && File(video).exists(),
        )
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        if (!::main.isInitialized) return
        official?.let { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        app?.let {
            onMain { it.sync.disconnect() }
            it.scope.cancel()
            runBlocking { withTimeout(5_000) { it.scope.coroutineContext.job.join() } }
        }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(15_000) { while (!onMain(condition)) delay(50) } }
            .onFailure {
                val room = app?.sync?.room?.value
                throw AssertionError(
                    "Timed out waiting for $what. Our playlist: ${room?.playlist} (index ${room?.playlistIndex}). " +
                        "The official client said:\n" + officialOutput.joinToString("\n"),
                )
            }
    }

    private fun officialSaid(text: String) = synchronized(officialOutput) { officialOutput.any { text in it } }

    private fun officialCommand(line: String) {
        val stdin = official!!.outputStream
        stdin.write("$line\n".toByteArray())
        stdin.flush()
    }

    private fun startOfficial(host: String, port: Int) {
        val player = System.getenv("SYNCPLAY_CONSOLE_PLAYER") ?: "mpv"
        val client = if (console.endsWith(".py")) listOf(System.getenv("SYNCPLAY_CONSOLE_PYTHON") ?: "python", "-u", console) else listOf(console)
        val process = ProcessBuilder(
            client + listOf(
                "--no-gui", "--no-store", "-a", "$host:$port", "-n", "Official", "-r", room, "--player-path", player, video,
                "--", "--vo=null", "--ao=null",
            ),
        )
            .redirectErrorStream(true)
            .apply { environment()["PYTHONUNBUFFERED"] = "1" }
            .start()
        official = process
        thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { officialOutput += it } }
    }

    /** Asks the official client for its playlist ("ql", numbered from 1) until it lists [entries]. */
    private fun awaitOfficialPlaylist(what: String, entries: List<String>) {
        var asked = 0L
        awaitTrue(what) {
            val listed = entries.withIndex().all { (i, entry) -> officialSaid("${i + 1}: $entry") } && !officialSaid("${entries.size + 1}: ")
            if (!listed && System.currentTimeMillis() - asked > 1000) {
                officialOutput.clear()
                officialCommand("ql")
                asked = System.currentTimeMillis()
            }
            listed
        }
    }

    @Test
    fun `playlist edits and chat reach the official client and back`() {
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        val tv = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root.resolve("tv"), storeScope),
            deviceName = "tv",
            appVersion = "test",
            createPlayer = { FakePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        app = tv
        onMain { tv.sync.connect(SyncplayConfig(host, port, "tv", room)) }
        awaitTrue("tv to join") { tv.sync.room.value.status == ConnectionStatus.CONNECTED }
        startOfficial(host, port)
        awaitTrue("the official client to join") { tv.sync.room.value.users.any { it.name == "Official" } }

        // Joining an empty room with a file, the official client makes that file the playlist.
        awaitTrue("tv to see the official client's file") { tv.sync.room.value.playlist.size == 1 }
        val first = tv.sync.room.value.playlist.single()
        officialCommand("qa official.mkv")
        awaitTrue("tv to see the official client's entry") { tv.sync.room.value.playlist == listOf(first, "official.mkv") }

        onMain { tv.playlist.shared.add(listOf("tv one.mkv", "tv two.mkv")) }
        onMain { tv.playlist.shared.shift(setOf(3), -1) }
        awaitOfficialPlaylist("the official client to list tv's edits", listOf(first, "official.mkv", "tv two.mkv", "tv one.mkv"))

        officialCommand("qd 4")
        awaitTrue("tv to see the official client's delete") { tv.sync.room.value.playlist == listOf(first, "official.mkv", "tv two.mkv") }
        onMain { tv.playlist.shared.undo() }
        awaitTrue("tv to undo the official client's delete") { tv.sync.room.value.playlist == listOf(first, "official.mkv", "tv two.mkv", "tv one.mkv") }
        awaitOfficialPlaylist("the official client to list the undone delete", listOf(first, "official.mkv", "tv two.mkv", "tv one.mkv"))

        onMain { tv.playlist.selectIndex(2) }
        awaitTrue("the official client to follow tv's selection") { officialSaid("tv changed the playlist selection") }

        officialCommand("ch hello from official")
        awaitTrue("tv to get the official client's chat") { tv.sync.feed.value.any { it.from == "Official" && it.text == "hello from official" } }
        onMain { tv.sync.sendChat("hello from tv") }
        awaitTrue("the official client to get tv's chat") { officialSaid("<tv> hello from tv") }
    }
}
