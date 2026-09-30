package com.syncplaytv.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Runs two clients against a real syncplay-server. Enabled with SYNCPLAY_TEST_SERVER=host:port,
 * e.g. after starting scripts/local-syncplay-server.ps1.
 */
class ServerIntegrationTest {
    private val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val room = "it-" + System.nanoTime()
    private val ep1 = FileInfo("Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv", 1420.0, 587443083)

    private lateinit var desktopPlayer: RealtimePlayer
    private lateinit var tvPlayer: RealtimePlayer
    private lateinit var desktop: SyncplayClient
    private lateinit var tv: SyncplayClient
    private val tvEvents = Collections.synchronizedList(mutableListOf<SyncplayEvent>())
    private val desktopEvents = Collections.synchronizedList(mutableListOf<SyncplayEvent>())

    @Before
    fun setUp() {
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        desktopPlayer = RealtimePlayer()
        tvPlayer = RealtimePlayer().apply { isFileLoaded = false }
        desktop = SyncplayClient(SyncplayConfig(host, port, "desktop", room), desktopPlayer, parentScope = scope)
        tv = SyncplayClient(SyncplayConfig(host, port, "tv", room), tvPlayer, parentScope = scope, log = { println("[tv] $it") })
        tv.events.onEach { tvEvents += it }.launchIn(scope)
        desktop.events.onEach { desktopEvents += it }.launchIn(scope)
    }

    @After
    fun tearDown() {
        if (::tv.isInitialized) {
            tv.close()
            desktop.close()
        }
        scope.cancel()
    }

    @Test
    fun `playlist auto-load and playback sync between two clients`() {
        desktop.start()
        desktop.fileLoaded(ep1)
        tv.start()
        eventually("both connected") {
            tv.state.value.status == ConnectionStatus.CONNECTED && desktop.state.value.users.size == 2
        }
        eventually("tv sees desktop's file") { tv.state.value.users.any { it.name == "desktop" && it.file == ep1 } }

        // Desktop adds the episode to the empty shared playlist: the TV is told to load it.
        desktop.addToPlaylist(ep1.name)
        val switch = eventuallyValue("tv switch event") {
            tvEvents.filterIsInstance<SyncplayEvent.SwitchToPlaylistItem>().firstOrNull()
        }
        assertEquals(0, switch.index)
        assertEquals(ep1.name, switch.filename)
        assertTrue(desktopEvents.none { it is SyncplayEvent.SwitchToPlaylistItem })

        // TV "loads" it and marks itself ready.
        tvPlayer.isFileLoaded = true
        tv.fileLoaded(ep1, switch.resetPosition)
        tv.setReady(true)
        desktop.setReady(true)
        eventually("desktop sees tv ready with file") {
            desktop.state.value.users.any { it.name == "tv" && it.isReady == true && it.file == ep1 }
        }

        // Desktop presses play.
        desktopPlayer.setPaused(false)
        eventually("tv unpaused") { !tvPlayer.isPaused }

        // Desktop seeks to 10:00 (after the 1 s window in which a freshly reset file ignores jumps).
        Thread.sleep(1_500)
        desktopPlayer.seek(600.0)
        eventually("tv followed seek") { tvPlayer.position in 598.0..606.0 }

        // TV pauses with the remote: desktop follows.
        tvPlayer.setPaused(true)
        eventually("desktop paused") { desktopPlayer.isPaused }
        val drift = kotlin.math.abs(desktopPlayer.position - tvPlayer.position)
        assertTrue("positions should match after pause, drift=$drift", drift < 1.5)

        tv.sendChat("hello from the TV")
        eventually("desktop got chat") {
            desktopEvents.any { it is SyncplayEvent.Chat && it.username == "tv" && it.message == "hello from the TV" }
        }
    }

    private fun eventually(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what\n tv=${tv.state.value}\n desktop=${desktop.state.value}\n tvPlayer=${tvPlayer.position}/${tvPlayer.isPaused} desktopPlayer=${desktopPlayer.position}/${desktopPlayer.isPaused}")
    }

    private fun <T : Any> eventuallyValue(what: String, timeoutMs: Long = 8_000, value: () -> T?): T {
        var result: T? = null
        eventually(what, timeoutMs) { value().also { result = it } != null }
        return result!!
    }
}

/** Thread-safe player driven by the wall clock. */
class RealtimePlayer(override var duration: Double = 1420.0) : PlayerAdapter {
    @Volatile override var isFileLoaded: Boolean = true
    private var basePosition = 0.0
    private var baseTime = now()
    private var paused = true
    private var speed = 1.0

    private fun now() = System.currentTimeMillis() / 1000.0

    override val position: Double
        @Synchronized get() = if (paused) basePosition else basePosition + (now() - baseTime) * speed
    override val isPaused: Boolean
        @Synchronized get() = paused

    @Synchronized override fun setPaused(paused: Boolean) {
        basePosition = position
        baseTime = now()
        this.paused = paused
    }

    @Synchronized override fun seek(position: Double) {
        basePosition = position
        baseTime = now()
    }

    @Synchronized override fun setSpeed(speed: Double) {
        basePosition = position
        baseTime = now()
        this.speed = speed
    }
}
