package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MediaSource
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
import java.io.File
import java.util.concurrent.Executors

/** A subtitle file beside a video is added to the player once the video has opened. */
class SidecarAttachTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private val player = RecordingPlayer()

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

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(10_000) { while (!onMain(condition)) delay(20) } }
            .onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    @Test
    fun aSubtitleBesideTheVideoIsAddedWhenTheVideoLoads() {
        val dir = tmp.newFolder("show")
        val video = File(dir, "Episode.mkv").apply { writeBytes(byteArrayOf(0)) }
        File(dir, "Episode.en.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n")
        val servers = MutableStateFlow<List<MediaSource>>(emptyList())
        val playlist = PlaylistController(scope, SyncController(scope, player), player, MutableStateFlow(null), local, MediaLocator(local, servers))
        val uri = FileLocalLibrary.uriOf(video.toPath())
        onMain { playlist.playLocal(uri, inRoom = false) }
        awaitTrue("the file to open") { player.loaded == uri }
        onMain { player.finishLoading(10.0) }
        awaitTrue("the sidecar") { player.commands.any { it.firstOrNull() == "sub-add" } }
        val added = player.commands.first { it.first() == "sub-add" }
        assertTrue(added[1].contains("Episode.en.srt"))
        assertEquals("select", added[2])
    }

    @Test
    fun aSubtitleOpenedWhileAVideoPlaysIsSelected() {
        val video = File(tmp.newFolder("films"), "Movie.mkv").apply { writeBytes(byteArrayOf(0)) }
        val sub = File(tmp.newFolder("subs"), "Movie.ja.ass")
        val servers = MutableStateFlow<List<MediaSource>>(emptyList())
        val playlist = PlaylistController(scope, SyncController(scope, player), player, MutableStateFlow(null), local, MediaLocator(local, servers))
        val uri = FileLocalLibrary.uriOf(video.toPath())
        onMain { playlist.playLocal(uri, inRoom = false) }
        awaitTrue("the file to open") { player.loaded == uri }
        onMain {
            player.finishLoading(10.0)
            playlist.addExternalSubtitle(FileLocalLibrary.uriOf(sub.toPath()), "Movie.ja", "ja")
        }
        awaitTrue("the opened subtitle") { player.commands.any { it.getOrNull(3) == "Movie.ja" } }
        val added = player.commands.first { it.getOrNull(3) == "Movie.ja" }
        assertEquals("select", added[2])
    }
}

private class RecordingPlayer : FakePlayer() {
    val commands = mutableListOf<List<String>>()

    override fun command(vararg args: String): Boolean {
        commands += args.toList()
        return true
    }
}
