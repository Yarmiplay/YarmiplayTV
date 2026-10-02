package com.yarmiplaytv.support

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.yarmiplaytv.TestSupport
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.support.AppState.Companion.onMain
import com.yarmiplaytv.support.AppState.Companion.waitUntil

/**
 * Builds the fixed app states the screenshot tests capture. Everything is set up before the activity
 * starts, so no notification toasts are on screen when the screenshots are taken.
 */
class Scenarios(private val state: AppState, private val syncplay: FakeSyncplayServer, private val jellyfin: FakeJellyfin) {
    private val container get() = TestSupport.container
    private var clip: Uri? = null

    val clipName = CLIP_NAME

    fun baseline() {
        syncplay.reset()
        state.baseline()
        clip?.let(TestSupport::delete)
        clip = null
    }

    fun tearDown() {
        onMain { container.playlist.stop() }
        clip?.let(TestSupport::delete)
        clip = null
        syncplay.reset()
    }

    /** Jellyfin signed in, two media folders, and a room with two other people, a playlist and some chat. */
    fun busyRoom() {
        state.signInJellyfin(jellyfin)
        state.fakeMediaFolders()
        state.joinRoom(syncplay)
        val feed = { container.sync.feed.value.size }
        var expected = feed()
        fun next() { expected++; waitUntil(5_000) { feed() >= expected } }
        syncplay.addPeer("Alex", FakeSyncplayServer.PeerFile(CLIP_NAME, CLIP_DURATION, 0), ready = true)
        next(); next() // joined, is playing
        syncplay.addPeer("Jordan", FakeSyncplayServer.PeerFile("Orbit Station S01E02 [1080p].mkv", 1_440.0, 0), ready = false)
        next(); next()
        syncplay.peerSetPlaylist("Alex", listOf("Orbit Station S01E01.mkv", CLIP_NAME, "Paper Boats (2018).mkv"), index = null)
        next()
        syncplay.peerChat("Alex", "Episode 2 tonight?")
        next()
        container.sync.sendChat("Sounds good, loading it now")
        next()
        syncplay.peerChat("Jordan", "Give me a minute")
        next()
        waitUntil(5_000) { container.sync.room.value.users.size == 3 && container.sync.room.value.playlist.size == 3 }
        container.playlist.handledPlayerRequests = container.playlist.openPlayerRequests.value
    }

    /** The test clip, selected in the room and loaded, paused at the start. */
    fun clipPlayingInRoom() {
        busyRoom()
        val uri = insertClip()
        container.playlist.playLocal(uri.toString(), inRoom = true)
        waitUntil(20_000) {
            container.player.state.value.fileLoaded && container.playlist.nowPlaying.value?.fileName == CLIP_NAME &&
                container.sync.room.value.playlistIndex == 1 && container.sync.room.value.isReady == true
        }
        waitUntil(10_000) { container.player.state.value.paused && container.player.currentPosition() < 0.05 }
        onMain { container.player.seek(0.0) }
        awaitCacheSettled()
        // The feed got "is playing"/ready messages; keep the chat panel's contents fixed.
        Thread.sleep(500)
    }

    /** The seek bar draws mpv's demuxer cache; wait until it stops growing so its length is fixed. */
    fun awaitCacheSettled() {
        val deadline = System.currentTimeMillis() + 10_000
        var last = -1.0
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val cached = container.player.state.value.cacheSeconds
            if (cached != last) {
                last = cached
                since = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since > 1_500) {
                break
            }
            Thread.sleep(50)
        }
        Log.i("Scenarios", "demuxer cache settled at %.3f s, position %.3f s".format(last, container.player.currentPosition()))
    }

    fun loadingStream() {
        container.playlist.playUrl(jellyfin.hangingStreamUrl)
        waitUntil(10_000) { container.playlist.status.value is PlaylistStatus.Loading }
    }

    fun failedStream() {
        container.playlist.playUrl(jellyfin.missingStreamUrl)
        waitUntil(20_000) { container.playlist.status.value is PlaylistStatus.Failed }
    }

    /** Someone in the room picks a file this device has no way to find (no Jellyfin, no media folders). */
    fun fileNotFound() {
        state.joinRoom(syncplay)
        syncplay.addPeer("Alex", null, ready = true)
        syncplay.peerSetPlaylist("Alex", listOf("Harbor Lights S02E05.mkv"), index = 0)
        waitUntil(10_000) { container.playlist.status.value is PlaylistStatus.NotFound }
        Thread.sleep(300)
    }

    private fun insertClip(): Uri {
        val resolver = TestSupport.app.contentResolver
        // A leftover copy would make MediaStore rename the new one to "… (1).mp4".
        resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Video.Media.DISPLAY_NAME}=?", arrayOf(CLIP_NAME))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, CLIP_NAME)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/YarmiplayTV-tests")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(CLIP_ASSET).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        clip = uri
        return uri
    }

    companion object {
        const val CLIP_NAME = "Orbit Station S01E02.mp4"
        const val CLIP_DURATION = 3.0

        /**
         * Solid black: the emulators' decoders sometimes place video tiles wrongly on the first frame,
         * which only stays pixel-identical when every tile looks the same.
         */
        private const val CLIP_ASSET = "yarmiplaytv-black-clip.mp4"
    }
}
