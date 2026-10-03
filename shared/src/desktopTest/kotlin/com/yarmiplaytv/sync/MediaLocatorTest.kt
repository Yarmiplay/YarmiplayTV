package com.yarmiplaytv.sync

import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MatchKind
import com.yarmiplaytv.media.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.io.File

class MediaLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var local: FileLocalLibrary
    private val servers = MutableStateFlow<List<MediaSource>>(emptyList())
    private lateinit var locator: MediaLocator

    private val movie = "North Wind (2022).mkv"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        local = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, emptyList(), watchForChanges = false)
        locator = MediaLocator(local, servers)
    }

    @After
    fun tearDown() {
        local.close()
        scope.cancel()
    }

    private fun locate(name: String = movie) = runBlocking { locator.locateForRoom(name) }

    @Test
    fun `local media folders beat every server`() = runBlocking {
        val media = tmp.newFolder("Movies")
        File(media, movie).writeBytes(ByteArray(10))
        local.addFolder(FileLocalLibrary.uriOf(media.toPath()))
        withTimeout(10_000) { while (local.files.value.isEmpty()) delay(20) }
        val jellyfin = FakeMediaSource(JF, exact = mapOf(movie to 10))
        servers.value = listOf(jellyfin)

        assertTrue(locate() is RoomLocation.Local)
        assertEquals(0, jellyfin.exactLookups.get())
    }

    @Test
    fun `preferred server streams when both have the exact file, and both get reports`() {
        servers.value = listOf(
            FakeMediaSource(JF, exact = mapOf(movie to 10)),
            FakeMediaSource(PX, exact = mapOf(movie to 10)),
        )
        val viaJellyfin = locate() as RoomLocation.Server
        assertEquals(JF, viaJellyfin.playable.sourceKey)
        assertEquals(setOf(JF, PX), viaJellyfin.copies.map { it.sourceKey }.toSet())

        locator.preferredServer = PX
        val viaPlex = locate() as RoomLocation.Server
        assertEquals(PX, viaPlex.playable.sourceKey)
        assertEquals(MatchKind.EXACT_FILENAME, viaPlex.matchedBy)
    }

    @Test
    fun `two servers of one kind both get copies, and preferring the second streams from it`() {
        servers.value = listOf(
            FakeMediaSource(JF, exact = mapOf(movie to 10)),
            FakeMediaSource(JF2, exact = mapOf(movie to 10)),
        )
        val first = locate() as RoomLocation.Server
        assertEquals(JF, first.playable.sourceKey)
        assertEquals(listOf(ServerCopy(JF, "$JF:$movie"), ServerCopy(JF2, "$JF2:$movie")), first.copies)

        locator.preferredServer = JF2
        val second = locate() as RoomLocation.Server
        assertEquals(JF2, second.playable.sourceKey)
        assertEquals(setOf(JF, JF2), second.copies.map { it.sourceKey }.toSet())
    }

    @Test
    fun `an exact match on any server beats a loose match on the preferred one`() {
        val jellyfin = FakeMediaSource(JF, loose = mapOf(movie to "North Wind.mkv"))
        val plex = FakeMediaSource(PX, exact = mapOf(movie to 10))
        servers.value = listOf(jellyfin, plex)
        val found = locate() as RoomLocation.Server
        assertEquals(PX, found.playable.sourceKey)
        assertEquals(listOf(ServerCopy(PX, "$PX:$movie")), found.copies)
        assertEquals(0, jellyfin.looseLookups.get())
    }

    @Test
    fun `loose pass runs only without an exact match, preferred server first`() {
        val jellyfin = FakeMediaSource(JF, loose = mapOf(movie to "North Wind.mkv"))
        val plex = FakeMediaSource(PX, loose = mapOf(movie to "North.Wind.2022.mkv"))
        servers.value = listOf(plex, jellyfin)
        locator.preferredServer = JF
        val found = locate() as RoomLocation.Server
        assertEquals(JF, found.playable.sourceKey)
        assertEquals(MatchKind.PARSED_SEARCH, found.matchedBy)
        assertEquals(listOf(ServerCopy(JF, "$JF:North Wind.mkv")), found.copies)
        assertEquals(0, plex.looseLookups.get())
    }

    @Test
    fun `copies need the same size when both sizes are known`() {
        servers.value = listOf(
            FakeMediaSource(JF, exact = mapOf(movie to 10)),
            FakeMediaSource(PX, exact = mapOf(movie to 99)),
        )
        val found = locate() as RoomLocation.Server
        assertEquals(listOf(JF), found.copies.map { it.sourceKey })

        val unknownSize = FakeMediaSource(PX, exact = mapOf(movie to 0))
        servers.value = listOf(servers.value.first(), unknownSize)
        assertEquals(2, (locate() as RoomLocation.Server).copies.size)
    }

    @Test
    fun `copies of an explicit pick are found on the other servers only`() = runBlocking {
        val jellyfin = FakeMediaSource(JF, exact = mapOf(movie to 10))
        val plex = FakeMediaSource(PX, exact = mapOf(movie to 10))
        servers.value = listOf(jellyfin, plex)
        val copies = locator.copiesOf(plex.playableFor(movie))
        assertEquals(listOf(ServerCopy(PX, "$PX:$movie"), ServerCopy(JF, "$JF:$movie")), copies)
        assertEquals(0, plex.exactLookups.get())
    }

    @Test
    fun `servers are searched at the same time`() {
        servers.value = listOf(
            FakeMediaSource(JF, exact = mapOf(movie to 10), lookupDelayMs = 400),
            FakeMediaSource(PX, exact = mapOf(movie to 10), lookupDelayMs = 400),
        )
        val started = System.nanoTime()
        locate()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 700)
    }

    @Test
    fun `missing everywhere explains why`() {
        assertEquals(RoomLocation.Missing("Not in your media folders"), locate())
        servers.value = listOf(FakeMediaSource(JF), FakeMediaSource(PX))
        assertEquals(RoomLocation.Missing("Not in Living; Not in Den"), locate())
    }
}
