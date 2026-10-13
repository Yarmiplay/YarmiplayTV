package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.episodeFromFile
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The other episodes of the playing one's season, among the files on this device. */
class EpisodeSiblingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = MutableStateFlow<List<MediaSource>>(emptyList())

    @After
    fun tearDown() = scope.cancel()

    private fun video(dir: File, name: String) = File(dir, name).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(10)) }

    private fun playing(file: File, directory: String = ""): NowPlaying {
        val uri = FileLocalLibrary.uriOf(file.toPath())
        return NowPlaying(file.name, file.name, 10, 0.0, uri, episode = episodeFromFile(file.name, directory))
    }

    @Test
    fun `the same show and season in the media folder, the same folder first, nearest first`() = runBlocking {
        val media = tmp.newFolder("Anime")
        val playing = video(media, "Show/[Group] Show - 02.mkv")
        listOf(1, 3, 4, 6).forEach { video(media, "Show/[Group] Show - %02d.mkv".format(it)) }
        video(media, "Show/[Group] Other Show - 03.mkv")
        // Another release of the same show elsewhere: only used when the folder has nothing.
        video(media, "Old/[Old] Show - 05.mkv")
        val lib = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, listOf(FileLocalLibrary.uriOf(media.toPath())), watchForChanges = false)
        withTimeout(10_000) { while (lib.files.value.size < 7) delay(20) }

        val found = EpisodeSiblings(lib, servers).of(playing(playing, "Show"))!!
        assertEquals(2, found.number)
        assertEquals(listOf(3, 1, 4, 6), found.others.map { it.number })
        assertEquals(false, found.others.first().remote)
        lib.close()
    }

    @Test
    fun `a file opened outside the media folders finds the videos beside it`() = runBlocking {
        val dir = tmp.newFolder("Downloads")
        val playing = video(dir, "Show S01E05.mkv")
        video(dir, "Show S01E06.mkv")
        video(dir, "Show S02E01.mkv")
        val lib = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, emptyList(), watchForChanges = false)

        val found = EpisodeSiblings(lib, servers).of(playing(playing))!!
        assertEquals(listOf(6), found.others.map { it.number })
        lib.close()
    }

    @Test
    fun `no episode number, no season`() = runBlocking {
        val dir = tmp.newFolder("Films")
        val playing = video(dir, "A Film (2020).mkv")
        val lib = FileLocalLibrary(desktopSettingsStore(tmp.newFolder("config")), scope, emptyList(), watchForChanges = false)
        assertNull(EpisodeSiblings(lib, servers).of(playing(playing)))
        lib.close()
    }
}
