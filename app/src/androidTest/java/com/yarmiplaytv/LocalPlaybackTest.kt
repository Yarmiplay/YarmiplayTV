package com.yarmiplaytv

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.sync.PlaylistStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays a real clip from device storage through a content:// URI (mpv gets it as a file descriptor)
 * and drives the touch player: tap to toggle controls, double-tap to seek, bottom sheets.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LocalPlaybackTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = TestSupport.container
    private lateinit var restoreRoom: () -> Unit
    private var clip: Uri? = null
    private val clipName = "YarmiplayTV Instrumented Clip - ${System.currentTimeMillis() % 100000}.mp4"

    @Before
    fun setUp() {
        restoreRoom = TestSupport.isolateFromRoom()
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 10_000)
        clip = TestSupport.insertClip(clipName)
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { container.playlist.stop() }
        clip?.let(TestSupport::delete)
        restoreRoom()
    }

    private fun playClipAndWaitLoaded() {
        container.playlist.playLocal(clip!!.toString(), inRoom = false)
        compose.waitUntilExactlyOneExists(hasTestTag("player"), 10_000)
        compose.waitUntil(20_000) {
            container.player.state.value.fileLoaded && container.playlist.nowPlaying.value?.fileName == clipName
        }
    }

    @Test
    fun contentUriClipLoadsWithNameAndSize() {
        playClipAndWaitLoaded()
        val np = container.playlist.nowPlaying.value!!
        assertEquals(clipName, np.fileName)
        assertEquals(TestSupport.clipSize(), np.sizeBytes)
        val duration = container.player.state.value.duration
        assertTrue("duration $duration", duration in 2.5..3.5)
    }

    @Test
    fun unreadableUriFailsAndDoesNotBlockTheNextLoad() {
        val missing = Uri.parse("content://media/external/video/media/987654321")
        container.playlist.playLocal(missing.toString(), inRoom = false)
        compose.waitUntil(20_000) { container.playlist.status.value is PlaylistStatus.Failed }
        // The same URI again must try again rather than being treated as still loading.
        container.playlist.dismissStatus()
        container.playlist.playLocal(missing.toString(), inRoom = false)
        compose.waitUntil(20_000) { container.playlist.status.value is PlaylistStatus.Failed }
        playClipAndWaitLoaded()
    }

    @Test
    fun tapTogglesControlsAndDoubleTapSeeks() {
        playClipAndWaitLoaded()
        compose.runOnUiThread {
            container.player.setPaused(true)
            container.player.seek(2.5)
        }
        compose.waitUntil(5_000) { container.player.currentPosition() > 2.0 }

        // Taps go to an empty spot, away from the centre buttons and the top/bottom bars.
        compose.waitUntilExactlyOneExists(hasTestTag("play_pause"), 5_000)
        // A single tap only counts once the double-tap timeout has passed on the test clock.
        compose.onNodeWithTag("player_gestures").performTouchInput { click(Offset(width * 0.15f, height * 0.4f)) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_GAP_MS)
        compose.waitUntilDoesNotExist(hasTestTag("play_pause"), 5_000)
        assertTrue("tap must not unpause", container.player.state.value.paused)
        compose.onNodeWithTag("player_gestures").performTouchInput { click(Offset(width * 0.15f, height * 0.4f)) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_GAP_MS)
        compose.waitUntilExactlyOneExists(hasTestTag("play_pause"), 5_000)

        compose.onNodeWithTag("player_gestures").performTouchInput { doubleClick(Offset(width * 0.1f, height * 0.4f)) }
        compose.waitUntil(5_000) { container.player.currentPosition() < 1.0 }
    }

    @Test
    fun playerSheetsOpen() {
        playClipAndWaitLoaded()
        compose.runOnUiThread { container.player.setPaused(true) }
        compose.waitUntilExactlyOneExists(hasTestTag("sheet_Chat"), 5_000)
        val audioBounds = compose.onNodeWithTag("sheet_Audio").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("sheet_Chat").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("chat_field"), 5_000)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDoesNotExist(hasTestTag("chat_field"), 5_000)
        // The sheet brought the navigation bar back; tap only once the controls have slid back into place.
        compose.waitUntil(5_000) { compose.onNodeWithTag("sheet_Audio").fetchSemanticsNode().boundsInRoot == audioBounds }
        compose.onNodeWithTag("sheet_Audio").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("player_sheet"), 5_000)
    }

    @Test
    fun screenshotButtonSavesTheFrameToPictures() {
        playClipAndWaitLoaded()
        compose.runOnUiThread { container.player.setPaused(true) }
        val before = savedScreenshots()
        compose.waitUntilExactlyOneExists(hasTestTag("screenshot"), 5_000)
        compose.onNodeWithTag("screenshot").performClick()
        compose.waitUntil(10_000) { (savedScreenshots() - before).isNotEmpty() }
        val added = savedScreenshots() - before
        try {
            val image = TestSupport.app.contentResolver.openInputStream(added.single())!!.use(BitmapFactory::decodeStream)
            val colors = (0 until 16).flatMap { y -> (0 until 16).map { x -> image.getPixel(x * image.width / 16, y * image.height / 16) } }.toSet()
            assertTrue("screenshot ${image.width}x${image.height} has ${colors.size} colours", colors.size > 8)
        } finally {
            added.forEach(TestSupport::delete)
        }
    }

    /** The images in Pictures/YarmiplayTV. */
    private fun savedScreenshots(): Set<Uri> {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return TestSupport.app.contentResolver.query(
            images,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/YarmiplayTV/"),
            null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(ContentUris.withAppendedId(images, c.getLong(0))) } }.orEmpty()
    }

    private companion object {
        /** More than ViewConfiguration's double-tap timeout (300 ms). */
        const val DOUBLE_TAP_GAP_MS = 400L
    }
}
