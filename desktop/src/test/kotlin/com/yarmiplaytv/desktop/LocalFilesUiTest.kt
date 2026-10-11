package com.yarmiplaytv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.FrameGrabber
import com.yarmiplaytv.player.desktop.MpvFrameGrabber
import com.yarmiplaytv.ui.mobile.MobileLocalFilesScreen
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Preview frames in the desktop's "Files on this device": made with libmpv from a real clip, an icon for a file
 * that isn't a video. Needs libmpv (set YARMIPLAYTV_SKIP_MPV_TESTS=1 to skip). A screenshot goes to the
 * yarmiplaytv.screenshotDir system property.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LocalFilesUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private lateinit var app: AppContainer
    private val clip = File(System.getProperty("yarmiplaytv.syncClip") ?: "")

    @Before
    fun setUp() {
        assumeTrue("libmpv tests skipped", System.getenv("YARMIPLAYTV_SKIP_MPV_TESTS").isNullOrEmpty())
        assumeTrue("test clip missing: $clip", clip.isFile)
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        app = AppContainer(
            settingsStore = desktopSettingsStore(tmp.root.resolve("app"), storeScope),
            deviceName = "desk",
            appVersion = "test",
            createPlayer = { IdlePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            cacheDir = tmp.root.resolve("cache"),
            frameGrabber = FrameGrabber { uri, width, target -> MpvFrameGrabber.grab(FileLocalLibrary.pathOf(uri).toString(), width, target) },
        )
    }

    @After
    fun tearDown() {
        if (!::app.isInitialized) return
        app.scope.cancel()
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun ComposeUiTest.showLocalFiles() {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val nav = Navigator(mutableStateListOf(Screen.Home, Screen.LocalFiles))
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MobileTheme { Box(Modifier.size(900.dp, 600.dp)) { MobileLocalFilesScreen(app, nav) } }
            }
        }
    }

    /** The tiles are inside clickable rows, which merge their children's semantics. */
    private fun ComposeUiTest.thumbnails() = onAllNodesWithTag("local_thumbnail", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `videos in a media folder show a frame, other files the icon`() = runComposeUiTest {
        val movies = tmp.newFolder("Movies")
        clip.copyTo(movies.resolve("Sync Clip (2026).mp4"))
        movies.resolve("Broken Download.mkv").writeText("not a video")
        app.local.addFolder(FileLocalLibrary.uriOf(movies.toPath()))
        val deadline = System.currentTimeMillis() + 10_000
        while (app.local.files.value.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(2, app.local.files.value.size)

        showLocalFiles()
        onNodeWithTag("local_folder").performClick()
        waitUntil(timeoutMillis = 20_000) { thumbnails() >= 1 }
        // Waits for the broken file's attempt (or makes it) and checks it failed; its row stays on the icon.
        val broken = app.local.files.value.single { it.name == "Broken Download.mkv" }
        assertNull(runBlocking { app.thumbnails!!.thumbnail(broken) })
        waitForIdle()
        assertEquals(1, thumbnails())
        onNodeWithText("Sync Clip (2026).mp4").fetchSemanticsNode()

        val cached = File(tmp.root, "cache/thumbnails").listFiles().orEmpty().filter { it.extension == "jpg" }
        assertEquals(1, cached.size)
        assertEquals(320, ImageIO.read(cached.single()).width)

        // The tile shows the decoded frame, not the flat placeholder with its icon.
        val tile = onNodeWithTag("local_thumbnail", useUnmergedTree = true).captureToImage().toAwtImage()
        val colours = HashSet<Int>()
        for (x in 0 until tile.width step 2) for (y in 0 until tile.height step 2) colours += tile.getRGB(x, y)
        assertTrue("only ${colours.size} colours in the tile", colours.size > 16)
        screenshot("local_files_previews")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
