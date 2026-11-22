package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.StreamTls
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/** A file that didn't open because its server's certificate couldn't be verified is asked about, then opened again or not. */
class UntrustedCertificateTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private val player = CountingPlayer()

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

    private fun opened(host: String, answer: CompletableDeferred<Boolean>): Pair<PlaylistController, String> {
        val video = File(tmp.newFolder(), "Episode.mkv").apply { writeBytes(byteArrayOf(0)) }
        val servers = MutableStateFlow<List<MediaSource>>(emptyList())
        val playlist = PlaylistController(scope, SyncController(scope, player), player, MutableStateFlow(null), local, MediaLocator(local, servers))
        playlist.confirmUntrustedCertificate = { asked ->
            player.asked += asked
            answer.await()
        }
        val uri = FileLocalLibrary.uriOf(video.toPath())
        onMain { playlist.playLocal(uri, inRoom = false) }
        awaitTrue("the file to open") { player.loads.size == 1 }
        onMain { player.events.tryEmit(PlayerEvent.EndFile(uri, "$host's certificate can't be verified", untrustedHost = host)) }
        awaitTrue("the question") { player.asked.isNotEmpty() }
        return playlist to uri
    }

    @Test
    fun playAnywayOpensTheFileAgainWithoutCheckingThatHost() {
        val answer = CompletableDeferred<Boolean>()
        val (playlist, uri) = opened("anyway.example", answer)
        assertEquals(PlaylistStatus.Loading("Episode.mkv"), playlist.status.value)
        assertFalse(StreamTls.isAllowedUnverified("anyway.example"))
        answer.complete(true)
        awaitTrue("the second open") { player.loads.size == 2 }
        assertEquals(listOf(uri, uri), player.loads.toList())
        assertTrue(StreamTls.isAllowedUnverified("anyway.example"))
        assertEquals(PlaylistStatus.Loading("Episode.mkv"), playlist.status.value)
        onMain { player.finishLoading(10.0) }
        awaitTrue("playing") { playlist.status.value == PlaylistStatus.Idle }
    }

    @Test
    fun cancelKeepsTheOpenError() {
        val answer = CompletableDeferred<Boolean>()
        val (playlist, _) = opened("cancel.example", answer)
        answer.complete(false)
        awaitTrue("the error") { playlist.status.value is PlaylistStatus.Failed }
        assertEquals(PlaylistStatus.Failed("Episode.mkv", "cancel.example's certificate can't be verified"), playlist.status.value)
        assertEquals(1, player.loads.size)
        assertFalse(StreamTls.isAllowedUnverified("cancel.example"))
    }

    @Test
    fun otherOpenErrorsAreNotAskedAbout() {
        val video = File(tmp.newFolder(), "Movie.mkv").apply { writeBytes(byteArrayOf(0)) }
        val servers = MutableStateFlow<List<MediaSource>>(emptyList())
        val playlist = PlaylistController(scope, SyncController(scope, player), player, MutableStateFlow(null), local, MediaLocator(local, servers))
        playlist.confirmUntrustedCertificate = { player.asked += it; true }
        val uri = FileLocalLibrary.uriOf(video.toPath())
        onMain { playlist.playLocal(uri, inRoom = false) }
        awaitTrue("the file to open") { player.loads.size == 1 }
        onMain { player.events.tryEmit(PlayerEvent.EndFile(uri, "Couldn't open the file")) }
        awaitTrue("the error") { playlist.status.value is PlaylistStatus.Failed }
        assertTrue(player.asked.isEmpty())
    }
}

private class CountingPlayer : FakePlayer() {
    val loads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) {
        super.load(url, mediaTitle, startPaused, startPosition)
        loads += url
    }
}
