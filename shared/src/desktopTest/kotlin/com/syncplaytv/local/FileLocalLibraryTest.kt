package com.syncplaytv.local

import com.syncplaytv.data.SettingsStore
import com.syncplaytv.data.desktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLocalLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private lateinit var media: File
    private val libraries = mutableListOf<FileLocalLibrary>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = desktopSettingsStore(tmp.newFolder("config"))
        media = tmp.newFolder("Movies")
        File(media, "North Wind (2022).mkv").writeBytes(ByteArray(1234))
        File(media, "notes.txt").writeText("not a video")
        File(media, "Season 1").mkdirs()
        File(media, "Season 1/Show S01E01.mp4").writeBytes(ByteArray(10))
        File(media, ".hidden").mkdirs()
        File(media, ".hidden/Secret.mkv").writeBytes(ByteArray(10))
    }

    @After
    fun tearDown() {
        libraries.forEach { it.close() }
        scope.cancel()
    }

    private fun library(folders: List<String> = emptyList(), watch: Boolean = false) =
        FileLocalLibrary(store, scope, folders, watch).also { libraries += it }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }
    }

    @Test
    fun `indexes videos recursively and skips hidden folders and other files`() = runBlocking {
        val lib = library()
        lib.addFolder(FileLocalLibrary.uriOf(media.toPath()))
        awaitTrue { lib.files.value.size == 2 }
        assertEquals(listOf("North Wind (2022).mkv", "Show S01E01.mp4"), lib.files.value.map { it.name })
        val movie = lib.files.value.first()
        assertEquals(1234L, movie.sizeBytes)
        assertEquals("Movies", movie.folder)
        assertTrue(movie.uri.startsWith("file:"))
        assertEquals(File(media, "North Wind (2022).mkv").canonicalFile, File(FileLocalLibrary.pathOf(movie.uri).toString()).canonicalFile)
        assertEquals("Movies", lib.folders.value.single().name)
    }

    @Test
    fun `folders are persisted and indexed again on the next start`() = runBlocking {
        val uri = FileLocalLibrary.uriOf(media.toPath())
        library().addFolder(uri)
        awaitTrue { runBlocking { store.current().localFolders } == listOf(uri) }

        val next = library(store.current().localFolders)
        awaitTrue { next.files.value.size == 2 }
        assertTrue(next.hasFolders)
    }

    @Test
    fun `resolves playlist names and forgets removed folders`() = runBlocking {
        val lib = library(listOf(FileLocalLibrary.uriOf(media.toPath())))
        awaitTrue { lib.files.value.size == 2 }
        assertEquals("North Wind (2022).mkv", lib.resolve("north wind (2022).mkv")?.file?.name)
        assertNull(lib.resolve("Somewhere Else.mkv"))

        lib.removeFolder(lib.folders.value.single())
        awaitTrue { lib.files.value.isEmpty() }
        assertNull(lib.resolve("North Wind (2022).mkv"))
    }

    @Test
    fun `describes files given as file URIs or plain paths`() = runBlocking {
        val lib = library()
        val file = File(media, "North Wind (2022).mkv")
        val byUri = lib.describe(FileLocalLibrary.uriOf(file.toPath()))
        assertEquals("North Wind (2022).mkv", byUri.name)
        assertEquals(1234L, byUri.sizeBytes)
        val byPath = lib.describe(file.absolutePath)
        assertEquals(byUri, byPath)
        val missing = lib.describe("file:///does/not/exist/Movie.mkv")
        assertEquals("Movie.mkv", missing.name)
        assertEquals(0L, missing.sizeBytes)
    }

    @Test
    fun `new and deleted videos are picked up while watching`() {
        val lib = library(listOf(FileLocalLibrary.uriOf(media.toPath())), watch = true)
        awaitTrue { lib.files.value.size == 2 && !lib.indexing.value }

        File(media, "Season 1/Show S01E02.mp4").writeBytes(ByteArray(10))
        awaitTrue { lib.files.value.any { it.name == "Show S01E02.mp4" } }

        File(media, "New Folder").mkdirs()
        File(media, "New Folder/Late Arrival.mkv").writeBytes(ByteArray(10))
        awaitTrue { lib.files.value.any { it.name == "Late Arrival.mkv" } }

        File(media, "North Wind (2022).mkv").delete()
        awaitTrue { lib.files.value.none { it.name == "North Wind (2022).mkv" } }
        assertNotNull(lib.files.value.firstOrNull { it.name == "Show S01E01.mp4" })
    }
}
