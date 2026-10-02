package com.yarmiplaytv.desktop

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.player.desktop.DesktopMpvPlayer
import com.yarmiplaytv.syncplay.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The desktop app as one more follower in SyncCheckTest's room (scripts/e2e-desktop.ps1): real libmpv playback,
 * the room's files found in a media folder by name, every step of the leader answered in chat.
 * Runs only with SYNCPLAY_E2E_ROOM and SYNCPLAY_TEST_SERVER set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSyncFollowerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun followsTheLeader() {
        val room = System.getenv("SYNCPLAY_E2E_ROOM").orEmpty()
        val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
        assumeTrue("SYNCPLAY_E2E_ROOM or SYNCPLAY_TEST_SERVER not set", room.isNotBlank() && server.isNotBlank())
        val clip = File(System.getProperty("yarmiplaytv.syncClip").orEmpty())
        val media = tmp.newFolder("media")
        CLIPS.forEach { clip.copyTo(File(media, it)) }

        val main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = desktopSettingsStore(tmp.root.resolve("config"), storeScope)
        runBlocking { store.saveLocalFolders(listOf(FileLocalLibrary.uriOf(media.toPath()))) }
        val player = DesktopMpvPlayer().apply { renderer.setSize(640, 360) }
        val app = AppContainer(
            settingsStore = store,
            deviceName = "Desktop follower",
            appVersion = "test",
            createPlayer = { player },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        try {
            val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
            runBlocking(main) { app.sync.connect(SyncplayProfile(host, port, NAME, room, "", useTls = false).toConfig()) }
            await(15_000, "joined $room") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
            follow(app, player)
        } finally {
            runBlocking(main) { app.sync.disconnect() }
            app.scope.cancel()
            player.close()
            storeScope.cancel()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private fun follow(app: AppContainer, player: DesktopMpvPlayer) {
        fun loaded(name: String) = player.state.value.fileLoaded && app.playlist.nowPlaying.value?.fileName == name
        var seen = 0
        val answers = mutableMapOf<String, String>()
        while (true) {
            val index = awaitValue(300_000, "next step from the leader") {
                app.sync.feed.value.withIndex().drop(seen)
                    .firstOrNull { (_, m) -> m.from?.trimEnd('_') == LEADER && m.text.startsWith("SYNC ") }?.index
            }
            seen = index + 1
            val parts = app.sync.feed.value[index].text.split(" ", limit = 4)
            val n = parts[1]
            val kind = parts[2]
            val value = parts.getOrNull(3)?.substringAfter('=')
            answers[n]?.let { earlier ->
                app.sync.sendChat(earlier)
                continue
            }
            println("desktop follower: step $n $kind ${value ?: ""}")
            val error = runCatching {
                when (kind) {
                    "file" -> await(20_000, "loaded $value") { loaded(value!!) }
                    "playing" -> await(5_000, "playing") { !player.state.value.paused && player.currentPosition() > 0.5 }
                    "pause", "seek" -> {
                        val target = value!!.toDouble()
                        await(5_000, "paused at %.2f (here %.2f)".format(target, player.currentPosition())) {
                            player.state.value.paused && abs(player.currentPosition() - target) < MAX_DRIFT_S
                        }
                    }
                }
            }.exceptionOrNull()?.message
            val answer = if (error == null) "ACK $n" else "FAIL $n $error"
            answers[n] = answer
            app.sync.sendChat(answer)
            if (error != null) throw AssertionError("step $n $kind: $error")
            if (kind == "done") return
        }
    }

    private fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        awaitValue(timeoutMs, what) { Unit.takeIf { condition() } }
    }

    private fun <T : Any> awaitValue(timeoutMs: Long, what: String, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            value()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val NAME = "SN-Desktop"
        const val LEADER = "SN-Leader"
        const val MAX_DRIFT_S = 0.5
        val CLIPS = listOf("YarmiplayTV Sync Check A.mp4", "YarmiplayTV Sync Check B.mp4")
    }
}
