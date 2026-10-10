package com.yarmiplaytv.screenshot

import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class ScreenshotsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pictures by lazy { tmp.root.resolve("Pictures/YarmiplayTV") }
    private val store by lazy { FolderScreenshotStore(tmp.root.resolve("scratch")) { pictures } }

    @After
    fun tearDown() = scope.cancel()

    private class ShootingPlayer(var works: Boolean = true) : FakePlayer() {
        val shots = mutableListOf<String>()
        val texts = mutableListOf<String>()

        init {
            state.value = PlaybackState(fileLoaded = true)
        }

        override fun screenshot(path: String): Boolean {
            shots += path
            if (works) File(path).writeBytes(byteArrayOf(1, 2, 3))
            return works
        }

        override fun showText(text: String, durationMs: Int) {
            texts += text
        }
    }

    private fun awaitTrue(condition: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!condition()) delay(10) } }

    private fun take(player: ShootingPlayer, folder: String = "", title: String? = "North Wind") {
        Screenshots(scope, player, store, { folder }) { title }.take()
        awaitTrue { player.texts.isNotEmpty() }
    }

    @Test
    fun `file names keep the title without characters file systems refuse`() {
        val at = LocalDateTime.of(2026, 10, 10, 5, 36, 12)
        assertEquals("Show S01E02 Pilot 2026-10-10 05-36-12.jpg", Screenshots.fileName("Show: S01E02 / Pilot?", at))
        assertEquals("YarmiplayTV 2026-10-10 05-36-12.jpg", Screenshots.fileName(null, at))
        assertEquals("YarmiplayTV 2026-10-10 05-36-12.jpg", Screenshots.fileName(" ... ", at))
        assertEquals(80 + " 2026-10-10 05-36-12.jpg".length, Screenshots.fileName("x".repeat(200), at).length)
    }

    @Test
    fun `a folder store numbers names that are taken`() {
        val first = store.scratchFile("Film 2026.jpg").apply { writeText("1") }
        store.save(first, "")
        val second = store.scratchFile("Film 2026.jpg").apply { writeText("2") }
        assertEquals(pictures.path, store.save(second, ""))
        assertEquals(listOf("Film 2026 (2).jpg", "Film 2026.jpg"), pictures.list().orEmpty().sorted())
        assertEquals(pictures.path, store.defaultFolder)
    }

    @Test
    fun `saves the frame to the default folder and says where it went`() {
        val player = ShootingPlayer()
        take(player)
        assertEquals(listOf("Screenshot saved to ${pictures.path}"), player.texts)
        val saved = pictures.listFiles().orEmpty().single()
        assertTrue(saved.name, saved.name.startsWith("North Wind ") && saved.name.endsWith(".jpg"))
        assertEquals(0, tmp.root.resolve("scratch").listFiles().orEmpty().size)
    }

    @Test
    fun `a folder picked in the settings gets the screenshots instead`() {
        val picked = tmp.newFolder("My shots")
        val player = ShootingPlayer()
        take(player, folder = picked.toURI().toString())
        assertEquals(listOf("Screenshot saved to ${picked.path}"), player.texts)
        assertEquals(1, picked.listFiles().orEmpty().size)
        assertTrue(!pictures.exists())
    }

    @Test
    fun `a failed screenshot leaves no file behind`() {
        val player = ShootingPlayer(works = false)
        take(player, title = null)
        assertEquals(listOf("Couldn't take a screenshot"), player.texts)
        assertTrue(!pictures.exists())
        assertEquals(0, tmp.root.resolve("scratch").listFiles().orEmpty().size)
    }

    @Test
    fun `nothing happens without a loaded file`() {
        val player = ShootingPlayer().apply { state.value = PlaybackState() }
        Screenshots(scope, player, store, { "" }) { null }.take()
        runBlocking { delay(100) }
        assertEquals(emptyList<String>(), player.shots + player.texts)
    }
}
