package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.PlayableMedia
import com.yarmiplaytv.media.ResolveResult
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/**
 * Search leaves the section tabs for a button beside Exit, and Library takes its place.
 * Phones and tablets keep libraries off Home; desktop Home keeps a short row of each one.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LibraryNavUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.newFolder(), storeScope),
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
        useLibrary(app, PreviewLibrary())
    }

    @After
    fun tearDown() {
        if (::app.isInitialized) runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
        if (::storeScope.isInitialized) runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        runCatching { Dispatchers.resetMain() }
        if (::main.isInitialized) main.close()
    }

    @Test
    fun `phone home puts search beside exit and keeps libraries on the Library tab`() = runDesktopComposeUiTest(420, 900) {
        show(DeviceKind.PHONE, 420, 900)
        onNodeWithTag("tab_Library").assertIsDisplayed()
        onNodeWithTag("tab_Search").assertDoesNotExist()
        assertSearchBeside("home_exit_app")
        assertTrue("phone home still lists a library", onAllNodesWithText("Movies").fetchSemanticsNodes().isEmpty())
        assertTrue("phone home still lists a title", onAllNodesWithText("Amelie").fetchSemanticsNodes().isEmpty())
        shot("phone-home")

        onNodeWithTag("tab_Library").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Amelie").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Aardvark").assertExists()
        shot("phone-library")

        onNodeWithTag("tab_Settings").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty() }
        assertSearchBeside("settings_exit_app")
        onNodeWithTag("top_search").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("search_field").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `tablet home puts search beside exit and keeps libraries on the Library tab`() = runDesktopComposeUiTest(1200, 800) {
        show(DeviceKind.TABLET, 1200, 800)
        onNodeWithTag("tab_Library").assertIsDisplayed()
        onNodeWithTag("tab_Search").assertDoesNotExist()
        onNodeWithTag("rail_exit").assertIsDisplayed()
        assertSearchBeside("home_exit_app")
        assertTrue("tablet home still lists a library", onAllNodesWithText("Movies").fetchSemanticsNodes().isEmpty())
        shot("tablet-home")

        onNodeWithTag("tab_Library").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Amelie").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Aardvark").assertExists()
        shot("tablet-library")
    }

    @Test
    fun `desktop home previews each library and see all opens the full one`() = runDesktopComposeUiTest(1440, 900) {
        show(DeviceKind.DESKTOP, 1440, 900)
        onNodeWithTag("tab_Library").assertIsDisplayed()
        onNodeWithTag("tab_Search").assertDoesNotExist()
        assertSearchBeside("home_exit_app")
        shot("desktop-home")

        onNodeWithText("Movies").performScrollTo()
        waitForIdle()
        onNodeWithText("Amelie").assertExists()
        onNodeWithText("The Matrix").assertExists()
        onNodeWithText("Aardvark").assertDoesNotExist()
        onNodeWithTag("see_all_jellyfin_preview_movies").assertExists()
        shot("desktop-home-libraries")

        onNodeWithTag("see_all_jellyfin_preview_movies").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("library").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText("Aardvark").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Harbor Lights").assertDoesNotExist()
        assertTrue("titles start in A to Z order", readingOrder("Arrival") < readingOrder("Dune"))
        shot("desktop-library")

        onNodeWithTag("library_sort").performClick()
        onNodeWithTag("library_sort_YearNewest").performClick()
        waitUntil(timeoutMillis = 5_000) { readingOrder("Dune") < readingOrder("Arrival") }

        onNodeWithTag("library_filter_all").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Harbor Lights").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("top_search").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("search_field").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("search_field").performTextReplacement("Harbor")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Harbor Lights").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("library").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.show(kind: DeviceKind, width: Int, height: Int) {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        app.deviceKind = kind
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme(appPalette(app)) {
                    Box(Modifier.size(width.dp, height.dp)) { MobileRoot(app, kind) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.assertSearchBeside(exitTag: String) {
        val search = onNodeWithTag("top_search").fetchSemanticsNode().boundsInRoot
        val exit = onNodeWithTag(exitTag).fetchSemanticsNode().boundsInRoot
        assertTrue("search starts before exit (${search.left} vs ${exit.left})", search.left < exit.left)
        assertTrue("search shares the exit row", search.bottom > exit.top && search.top < exit.bottom)
    }

    private fun ComposeUiTest.readingOrder(text: String): Float {
        val bounds = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        return bounds.top * 10_000f + bounds.left
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        for (dir in listOf(File("build/library-nav-shots"), File("/opt/cursor/artifacts"))) {
            runCatching {
                dir.mkdirs()
                ImageIO.write(image, "png", File(dir, "library-nav-$name.png"))
            }
        }
    }
}

/** A server with two libraries. Home's preview keeps the first eight movies; the rest are only on Library. */
private class PreviewLibrary : MediaSource {
    override val key = "jellyfin:preview"
    override val kind = "jellyfin"
    override val displayName = "Preview"

    private val movies = listOf(
        movie("amelie", "Amelie", 2001),
        movie("arrival", "Arrival", 2016),
        movie("dune", "Dune", 2021),
        movie("heat", "Heat", 1995),
        movie("moonlight", "Moonlight", 2016),
        movie("parasite", "Parasite", 2019),
        movie("spirited", "Spirited Away", 2001),
        movie("matrix", "The Matrix", 1999),
        movie("aardvark", "Aardvark", 2030),
    )
    private val shows = listOf(
        MediaItem("harbor", "Harbor Lights", MediaItemType.SERIES, isFolder = true, year = 2019, sourceKey = key),
    )
    private val moviesLibrary = MediaItem("movies", "Movies", MediaItemType.LIBRARY, isFolder = true, collectionType = "movies", sourceKey = key)
    private val showsLibrary = MediaItem("shows", "Shows", MediaItemType.LIBRARY, isFolder = true, collectionType = "tvshows", sourceKey = key)

    private fun movie(id: String, name: String, year: Int) =
        MediaItem(id, name, MediaItemType.MOVIE, isFolder = false, year = year, sourceKey = key)

    override suspend fun libraries() = listOf(moviesLibrary, showsLibrary)

    override suspend fun children(parent: MediaItem) = when (parent.id) {
        "movies" -> movies
        "shows" -> shows
        else -> emptyList()
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return (movies + shows).filter { it.name.lowercase().contains(q) }.take(limit)
    }

    override suspend fun recent(limit: Int) = emptyList<MediaItem>()

    override suspend fun playable(item: MediaItem) = PlayableMedia(
        itemId = item.id, url = "http://preview/${item.id}", fileName = "${item.name}.mkv",
        sizeBytes = 1, durationSeconds = 100.0, title = item.name, sourceKey = key,
    )

    override suspend fun resolveByFilename(fileName: String) = ResolveResult.NotFound(fileName, "Not in preview")
    override suspend fun findExact(fileName: String): ResolveResult.Found? = null
    override fun imageUrl(item: MediaItem, maxWidth: Int): String? = null
}

@Suppress("UNCHECKED_CAST")
private fun useLibrary(app: AppContainer, source: MediaSource) {
    val flow = AppContainer::class.java.getDeclaredField("_servers").apply { isAccessible = true }.get(app) as MutableStateFlow<List<MediaSource>>
    flow.value = listOf(source)
}
