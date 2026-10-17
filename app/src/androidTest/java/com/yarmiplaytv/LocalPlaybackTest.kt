package com.yarmiplaytv

import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yarmiplaytv.local.AndroidFrameGrabber
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.support.Screenshots
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.ui.mobile.PlayerPictureInPicture
import com.yarmiplaytv.ui.nav.Screen
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays a real clip from device storage through a content:// URI (mpv gets it as a file descriptor)
 * and drives the touch player: tap to toggle controls, double-tap to seek, bottom sheets, the seek bar and volume.
 * Also makes preview frames of the clip for the file lists.
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
        compose.runOnUiThread {
            if (TestSupport.app.screens.lastOrNull() != Screen.Home) {
                TestSupport.app.screens.apply {
                    clear()
                    add(Screen.Home)
                }
            }
        }
        compose.waitUntilExactlyOneExists(hasTestTag("home"), 10_000)
        clip = TestSupport.insertClip(clipName)
    }

    @After
    fun tearDown() {
        compose.runOnUiThread {
            container.playlist.stop()
            TestSupport.app.screens.apply {
                clear()
                add(Screen.Home)
            }
        }
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
    fun aRecreatedActivityComesBackOnThePlayer() {
        // The system recreates the activity for changes no app can handle itself (CI emulators do it when System UI
        // restarts and reapplies its overlays); the video mustn't vanish behind the home screen.
        playClipAndWaitLoaded()
        val before = compose.activity
        // Activity.recreate() like the system does it; ActivityScenario.recreate() waits for it while syncing with
        // Compose, which a slow emulator can trip ("performMeasureAndLayout called during measure layout").
        compose.runOnUiThread { before.recreate() }
        val deadline = System.currentTimeMillis() + 15_000
        while (runCatching { compose.activity === before }.getOrDefault(true)) {
            check(System.currentTimeMillis() < deadline) { "The activity wasn't recreated" }
            Thread.sleep(100)
        }
        compose.waitUntilExactlyOneExists(hasTestTag("player"), 10_000)
        assertTrue(container.player.state.value.fileLoaded)
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
        compose.runOnUiThread {
            container.player.seek(0.5)
            container.player.setPaused(false)
        }
        compose.waitUntil(10_000) {
            !container.player.state.value.seeking &&
                container.player.state.value.videoWidth > 0 &&
                container.player.currentPosition() >= 0.7
        }
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

    @Test
    fun seekBarIsTheTvTrackAndSeeks() {
        playClipAndWaitLoaded()
        compose.runOnUiThread { container.player.setPaused(true) }
        compose.waitUntilExactlyOneExists(hasTestTag("seek_slider"), 5_000)
        compose.onNodeWithTag("seek_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2.0f) }
        compose.waitUntil(5_000) { container.player.currentPosition() in 1.8..2.2 }
        // A tap on the track seeks there too: the first quarter of a 3 s clip.
        compose.onNodeWithTag("seek_slider").performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.waitUntil(5_000) { container.player.currentPosition() in 0.5..1.0 }
    }

    @Test
    fun volumeControlSetsAndMutesTheMediaVolume() {
        val audio = TestSupport.app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        try {
            playClipAndWaitLoaded()
            compose.runOnUiThread { container.player.setPaused(true) }
            compose.waitUntilExactlyOneExists(hasTestTag("volume_slider"), 5_000)
            compose.onNodeWithTag("volume_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
            compose.waitUntil(5_000) { audio.getStreamVolume(stream) == Math.round(0.4 * max).toInt() }
            val shown = audio.getStreamVolume(stream) * 100 / max
            compose.onNodeWithTag("volume_label").assertTextEquals("${Math.round(audio.getStreamVolume(stream) * 100.0 / max)}%")
            assertTrue("$shown%", shown in 30..50)

            compose.onNodeWithTag("volume_mute").performClick()
            compose.waitUntil(5_000) { audio.isStreamMute(stream) }
            compose.onNodeWithTag("volume_label").assertTextEquals("Muted")
            compose.onNodeWithTag("volume_mute").performClick()
            compose.waitUntil(5_000) { !audio.isStreamMute(stream) }

            // The volume buttons change it behind the player's back; the bar follows.
            audio.setStreamVolume(stream, max, 0)
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("volume_label").assertTextEquals("100%") }.isSuccess }
        } finally {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(stream, before, 0)
        }
    }

    @Test
    fun previewFrameOfAClipOnThisDevice() {
        val file = LocalFile(clipName, TestSupport.clipSize(), clip!!.toString(), modified = System.currentTimeMillis())
        val preview = runBlocking { container.thumbnails!!.thumbnail(file) }
        assertNotNull("no preview for the media store clip", preview)
        assertColourfulImage(preview!!)
        assertEquals("served from the cache", preview, runBlocking { container.thumbnails!!.thumbnail(file) })
    }

    @Test
    fun frameGrabberDecodesAFileWithoutAProviderThumbnail() {
        // A plain file has no provider to ask, so this is the frame the media retriever decodes.
        val copy = File(TestSupport.app.cacheDir, "grabber-test.mp4")
        val target = File(TestSupport.app.cacheDir, "grabber-test.jpg")
        try {
            TestSupport.app.contentResolver.openInputStream(clip!!)!!.use { input -> copy.outputStream().use(input::copyTo) }
            assertTrue(AndroidFrameGrabber(TestSupport.app).grab(Uri.fromFile(copy).toString(), 320, target))
            assertColourfulImage(target)
            assertFalse(AndroidFrameGrabber(TestSupport.app).grab(Uri.fromFile(File(TestSupport.app.cacheDir, "missing.mp4")).toString(), 320, target.resolveSibling("x.jpg")))
        } finally {
            copy.delete()
            target.delete()
        }
    }

    @Test
    fun pipButtonShrinksThePlayerToAWindowThatKeepsTheVideo() {
        assumeTrue("no picture-in-picture on this device", pipSupported)
        playClipAndWaitLoaded()
        compose.runOnUiThread { container.player.setPaused(true) }
        val activity = compose.activity
        try {
            compose.waitUntilExactlyOneExists(hasTestTag("pip"), 5_000)
            compose.onNodeWithTag("pip").performClick()
            waitFor(10_000) { activity.isInPictureInPictureMode }
            // The player screen switched to its picture-in-picture layout. (Compose's test APIs can't see the
            // window while it's in PiP, so this is the screen's own flag rather than its nodes.)
            waitFor(5_000) { PlayerPictureInPicture.active }
            assertTrue("still loaded", container.player.state.value.fileLoaded)
        } finally {
            compose.runOnUiThread { container.playlist.stop() }
            // If full screen doesn't come back, the PiP window still mustn't outlive the test.
            runCatching { leavePictureInPicture(activity) }.onFailure {
                closePictureInPicture(activity)
                throw it
            }
        }
        // Full size again without the activity being recreated: the same player screen, its controls back.
        assertSame(activity, compose.activity)
        compose.waitUntilExactlyOneExists(hasTestTag("player_controls"), 10_000)
        assertFalse(PlayerPictureInPicture.active)
    }

    @Test
    fun leavingTheAppWhilePlayingGoesIntoPictureInPicture() {
        assumeTrue("no picture-in-picture on this device", pipSupported)
        waitFor(5_000) { container.settings.value.playback.pictureInPicture }
        withLongClipPlaying {
            Screenshots.shell("input keyevent KEYCODE_HOME")
            val entered = runCatching { waitFor(5_000) { compose.activity.isInPictureInPictureMode } }.isSuccess
            if (!entered) {
                // If a slow emulator or transition dropped the first Home key, send it again.
                play()
                Screenshots.shell("input keyevent KEYCODE_HOME")
                waitFor(15_000) { compose.activity.isInPictureInPictureMode }
            }
            waitFor(5_000) { PlayerPictureInPicture.active }
        }
    }

    @Test
    fun leavingTheAppDoesNotGoIntoPictureInPictureWhenTurnedOff() {
        assumeTrue("no picture-in-picture on this device", pipSupported)
        val saved = container.settings.value.playback
        try {
            runBlocking { container.settingsStore.savePlayback(saved.copy(pictureInPicture = false)) }
            waitFor(5_000) { !container.settings.value.playback.pictureInPicture }
            withLongClipPlaying {
                Screenshots.shell("input keyevent KEYCODE_HOME")
                Thread.sleep(3_000)
                assertFalse("the setting is off", compose.activity.isInPictureInPictureMode)
                assertFalse(PlayerPictureInPicture.active)
            }
        } finally {
            runBlocking { container.settingsStore.savePlayback(saved) }
            waitFor(5_000) { container.settings.value.playback.pictureInPicture == saved.pictureInPicture }
        }
    }

    /**
     * Runs [block] with the minute-long clip playing on the player screen: the 3 s one can end, and so stop playing,
     * before a slow emulator handles Home. Afterwards the app is brought back and the clip removed.
     */
    private fun withLongClipPlaying(block: () -> Unit) {
        val long = TestSupport.insertClip("YarmiplayTV PiP Clip - ${System.currentTimeMillis() % 100000}.mp4", TestSupport.LONG_CLIP_ASSET)
        try {
            container.playlist.playLocal(long.toString(), inRoom = false)
            compose.waitUntilExactlyOneExists(hasTestTag("player"), 10_000)
            compose.waitUntil(20_000) { container.player.state.value.fileLoaded }
            play()
            block()
        } finally {
            compose.runOnUiThread { container.playlist.stop() }
            closePictureInPicture(compose.activity)
            TestSupport.delete(long)
        }
    }

    /** Plays the loaded clip and waits until the PiP parameters have caught up with it playing. */
    private fun play() {
        compose.runOnUiThread { container.player.setPaused(false) }
        waitFor(5_000) { !container.player.state.value.paused }
        compose.waitUntil(5_000) { container.player.currentPosition() > 0.1 }
        // A few frames for the parameters (auto-enter on) to be set from the new state.
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        Thread.sleep(500)
    }

    /**
     * Polls [condition]. Unlike the compose rule's waits it works while the window is in picture-in-picture, where the
     * rule finds no compose hierarchy; it moves the rule's clock along itself, so the UI keeps recomposing.
     */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(50)
        }
    }

    /** Back to full screen; asks again if the first request came while the window was still animating into PiP. */
    private fun leavePictureInPicture(activity: android.app.Activity) {
        repeat(5) {
            bringBack()
            if (runCatching { waitFor(3_000) { !activity.isInPictureInPictureMode } }.isSuccess) return
        }
        waitFor(1) { !activity.isInPictureInPictureMode }
    }

    /**
     * Makes sure no PiP window outlives the test: the next test's (singleTask) activity would open inside it. Back to
     * full screen if the system lets us, else the activity and its pinned task go.
     */
    private fun closePictureInPicture(activity: android.app.Activity) {
        // A Home-started PiP can still be on its way in when a slow emulator's test gives up, and would then outlive
        // it: only trust the window once it has focus, full size, and stays that way for a moment.
        repeat(3) {
            bringBack()
            val settled = runCatching { waitFor(3_000) { !activity.isInPictureInPictureMode && activity.hasWindowFocus() } }.isSuccess
            if (settled) {
                Thread.sleep(1_000)
                if (!activity.isInPictureInPictureMode) return
            }
        }
        compose.runOnUiThread { activity.finishAndRemoveTask() }
        waitFor(10_000) { activity.isDestroyed }
    }

    /** Like tapping the app's icon: brings the (singleTask) activity back to full screen without a new intent to act on. */
    private fun bringBack() {
        Screenshots.shell("am start -n ${TestSupport.app.packageName}/com.yarmiplaytv.MainActivity")
    }

    private val pipSupported get() = TestSupport.app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun assertColourfulImage(file: File) {
        val image = BitmapFactory.decodeFile(file.path)
        assertNotNull("not an image: $file", image)
        assertTrue("${image.width} px wide", image.width in 16..320)
        val colors = (0 until 16).flatMap { y -> (0 until 16).map { x -> image.getPixel(x * image.width / 16, y * image.height / 16) } }.toSet()
        assertTrue("preview ${image.width}x${image.height} has ${colors.size} colours", colors.size > 8)
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
