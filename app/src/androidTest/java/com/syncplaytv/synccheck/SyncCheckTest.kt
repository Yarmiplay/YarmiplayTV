package com.syncplaytv.synccheck

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syncplaytv.MainActivity
import com.syncplaytv.TestSupport
import com.syncplaytv.data.SyncplayProfile
import com.syncplaytv.support.AppState
import com.syncplaytv.support.AppState.Companion.onMain
import com.syncplaytv.syncplay.ConnectionStatus
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Two devices in one room on a real Syncplay server stay in sync through play, pause, seek and a file
 * change. `scripts/sync-check.ps1` runs this on two emulators at once: one as `leader` (acts like a
 * user) and one as `follower` (checks it follows). They coordinate through room chat: the leader
 * announces each step with its own position, the follower answers `ACK <step>` or `FAIL <step> …`.
 *
 * Arguments: `-e syncRole leader|follower -e syncRoom <room> [-e syncHost 10.0.2.2] [-e syncPort 8999]`.
 * Without `syncRole` the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class SyncCheckTest {
    private val args = InstrumentationRegistry.getArguments()
    private val role = args.getString("syncRole")
    private val container get() = TestSupport.container
    private val state = AppState()
    private val clips = mutableListOf<Uri>()
    private var activity: ActivityScenario<MainActivity>? = null

    private val leaderName = "SN-Leader"
    private val followerName = "SN-Follower"

    @Before
    fun setUp() {
        assumeTrue("needs -e syncRole leader|follower", role == "leader" || role == "follower")
        state.snapshot()
        state.baseline()
    }

    @After
    fun tearDown() {
        if (role == null) return
        activity?.close()
        clips.forEach(TestSupport::delete)
        state.restore()
    }

    @Test
    fun staysInSync() {
        val room = requireNotNull(args.getString("syncRoom")) { "-e syncRoom is required" }
        val host = args.getString("syncHost") ?: "10.0.2.2"
        val port = args.getString("syncPort")?.toInt() ?: 8999
        val me = if (role == "leader") leaderName else followerName
        container.sync.connect(SyncplayProfile(host, port, me, room, "", useTls = false).toConfig())
        await(15_000, "joined $room") { container.sync.room.value.status == ConnectionStatus.CONNECTED }

        val a = insertClip(CLIP_A)
        val b = insertClip(CLIP_B)
        activity = ActivityScenario.launch(MainActivity::class.java)
        // Registers both files with the playlist controller so the room's choice resolves to them.
        container.playlist.addLocalToRoomPlaylist(a.toString())
        Thread.sleep(500)
        container.playlist.addLocalToRoomPlaylist(b.toString())
        await(10_000, "playlist has both clips") { container.sync.room.value.playlist.containsAll(listOf(CLIP_A, CLIP_B)) }

        if (role == "leader") lead() else follow()
    }

    // --- Leader --------------------------------------------------------------------------

    private fun lead() {
        await(120_000, "follower joined") { container.sync.room.value.users.any { it.name.trimEnd('_') == followerName } }
        val player = container.player

        container.playlist.selectIndex(container.sync.room.value.playlist.indexOf(CLIP_A))
        await(20_000, "clip A loaded here") { loaded(CLIP_A) }
        await(30_000, "follower loaded clip A") { container.sync.room.value.users.any { it.name.trimEnd('_') == followerName && it.file?.name == CLIP_A } }
        step("file", "name=$CLIP_A")

        // Unpausing while not ready only marks this user ready, and within 5 s of a file switch
        // Syncplay reverts it (RECENT_REWIND_THRESHOLD).
        container.sync.setReady(true)
        await(5_000, "ready") { container.sync.room.value.isReady == true }
        Thread.sleep(6_000)
        onMain { player.setPaused(false) }
        await(10_000, "playing") { !player.state.value.paused && player.currentPosition() > 1.0 }
        step("playing")
        Thread.sleep(3_000)

        onMain { player.setPaused(true) }
        await(5_000, "paused") { player.state.value.paused }
        Thread.sleep(500)
        step("pause", "pos=%.2f".format(player.currentPosition()))

        onMain { player.seek(30.0) }
        await(5_000, "seeked") { abs(player.currentPosition() - 30.0) < 0.3 }
        Thread.sleep(500)
        step("seek", "pos=%.2f".format(player.currentPosition()))

        onMain { player.setPaused(false) }
        Thread.sleep(4_000)
        onMain { player.setPaused(true) }
        await(5_000, "paused again") { player.state.value.paused }
        Thread.sleep(500)
        step("pause", "pos=%.2f".format(player.currentPosition()))

        container.playlist.selectIndex(container.sync.room.value.playlist.indexOf(CLIP_B))
        await(20_000, "clip B loaded here") { loaded(CLIP_B) }
        step("file", "name=$CLIP_B")
        step("done")
    }

    private var stepNumber = 0

    /**
     * Announces a step and waits for the follower to confirm it. The announcement is repeated while
     * unanswered: chat sent during a reconnect (emulator network handovers) is lost.
     */
    private fun step(kind: String, detail: String = "") {
        val n = ++stepNumber
        val sentAt = container.sync.feed.value.size
        val announcement = "SYNC $n $kind $detail".trim()
        log("leader: step $n $kind $detail")
        var lastSent = 0L
        val answer = awaitValue(20_000, "follower answer to step $n $kind") {
            if (System.currentTimeMillis() - lastSent > RESEND_MS && container.sync.room.value.status == ConnectionStatus.CONNECTED) {
                container.sync.sendChat(announcement)
                lastSent = System.currentTimeMillis()
            }
            container.sync.feed.value.drop(sentAt).firstOrNull { it.from?.trimEnd('_') == followerName && (it.text == "ACK $n" || it.text.startsWith("FAIL $n")) }
        }
        if (answer.text.startsWith("FAIL")) throw AssertionError("Follower: ${answer.text}")
    }

    // --- Follower --------------------------------------------------------------------------

    private fun follow() {
        val player = container.player
        var seen = 0
        val answers = mutableMapOf<String, String>()
        while (true) {
            val index = awaitValue(300_000, "next step from the leader") {
                container.sync.feed.value.withIndex().drop(seen)
                    .firstOrNull { (_, m) -> m.from?.trimEnd('_') == leaderName && m.text.startsWith("SYNC ") }?.index
            }
            seen = index + 1
            val parts = container.sync.feed.value[index].text.split(" ", limit = 4)
            val n = parts[1]
            val kind = parts[2]
            val value = parts.getOrNull(3)?.substringAfter('=')
            val earlier = answers[n]
            if (earlier != null) {
                // A repeated announcement: our answer may have been lost.
                container.sync.sendChat(earlier)
                continue
            }
            log("follower: step $n $kind ${value ?: ""}")
            val error: String? = runCatching {
                when (kind) {
                    "file" -> await(20_000, "loaded $value") { loaded(value!!) }
                    "playing" -> await(5_000, "playing") { !player.state.value.paused && player.currentPosition() > 0.5 }
                    "pause", "seek" -> {
                        val target = value!!.toDouble()
                        await(5_000, "paused at %.2f (here %.2f)".format(target, player.currentPosition())) {
                            player.state.value.paused && abs(player.currentPosition() - target) < MAX_DRIFT_S
                        }
                    }
                    "done" -> Unit
                }
            }.exceptionOrNull()?.message
            val answer = if (error == null) "ACK $n" else "FAIL $n $error"
            answers[n] = answer
            container.sync.sendChat(answer)
            if (error != null) throw AssertionError("step $n $kind: $error")
            if (kind == "done") return
        }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun loaded(name: String) =
        container.player.state.value.fileLoaded && container.playlist.nowPlaying.value?.fileName == name

    private fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(50)
        }
    }

    private fun <T : Any> awaitValue(timeoutMs: Long, what: String, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            value()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(50)
        }
    }

    private fun insertClip(name: String): Uri {
        val resolver = TestSupport.app.contentResolver
        resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Video.Media.DISPLAY_NAME}=?", arrayOf(name))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SyncplayTV-tests")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(CLIP_ASSET).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        clips += uri
        return uri
    }

    private fun log(message: String) = Log.i("SyncCheck", message)

    companion object {
        private const val CLIP_ASSET = "syncplaytv-sync-clip.mp4"
        private const val CLIP_A = "SyncplayTV Sync Check A.mp4"
        private const val CLIP_B = "SyncplayTV Sync Check B.mp4"
        private const val MAX_DRIFT_S = 0.5
        private const val RESEND_MS = 5_000L
    }
}
