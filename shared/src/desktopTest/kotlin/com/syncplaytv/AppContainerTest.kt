package com.syncplaytv

import com.syncplaytv.data.SyncplayProfile
import com.syncplaytv.data.desktopSettingsStore
import com.syncplaytv.local.FileLocalLibrary
import com.syncplaytv.player.PlaybackState
import com.syncplaytv.player.Player
import com.syncplaytv.player.PlayerEvent
import com.syncplaytv.player.Track
import com.syncplaytv.player.TrackType
import com.syncplaytv.sync.PlaylistStatus
import com.syncplaytv.syncplay.SyncSettings
import com.syncplaytv.syncplay.UnpauseMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private val player = FakePlayer()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun awaitTrue(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(20) } }

    private fun container(): AppContainer {
        val store = desktopSettingsStore(tmp.root.resolve("config"), storeScope)
        return AppContainer(
            settingsStore = store,
            deviceName = "Test PC",
            appVersion = "test",
            createPlayer = { player },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
        )
    }

    @Test
    fun `plays a local file outside a room and unpauses once loaded`() {
        val video = tmp.newFolder("Movies").resolve("North Wind (2022).mkv").apply { writeBytes(ByteArray(2048)) }
        val app = container()
        app.playlist.playLocal(FileLocalLibrary.uriOf(video.toPath()), inRoom = false)

        awaitTrue { player.loaded != null }
        assertEquals("North Wind (2022).mkv", app.playlist.nowPlaying.value?.fileName)
        assertEquals(2048L, app.playlist.nowPlaying.value?.sizeBytes)
        assertEquals(PlaylistStatus.Loading("North Wind (2022).mkv"), app.playlist.status.value)

        player.finishLoading(duration = 90.0)
        awaitTrue { app.playlist.status.value == PlaylistStatus.Idle && !player.isPaused }
        app.scope.cancel()
    }

    @Test
    fun `saved settings reach the controllers`() {
        runBlocking {
            desktopSettingsStore(tmp.root.resolve("config"), storeScope).apply {
                saveSyncplay(SyncplayProfile(host = "syncplay.example.org", username = "Sam", room = "movie-night"))
                saveSync(SyncSettings(unpauseMode = UnpauseMode.ALWAYS), autoReady = false)
            }
        }
        storeScope.cancel()
        runBlocking { storeScope.coroutineContext.job.join() }
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val app = container()
        assertEquals("Sam", app.settings.value.syncplay.username)
        awaitTrue { !app.playlist.autoReady && app.sync.syncSettings.unpauseMode == UnpauseMode.ALWAYS }
        assertFalse(app.sync.isActive)
        app.scope.cancel()
    }
}

private class FakePlayer : Player {
    override val state = MutableStateFlow(PlaybackState())
    override val tracks = MutableStateFlow<List<Track>>(emptyList())
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)

    @Volatile var loaded: String? = null
    @Volatile private var paused = true
    @Volatile private var length = 0.0

    override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) {
        loaded = url
        paused = startPaused
    }

    fun finishLoading(duration: Double) {
        length = duration
        state.value = PlaybackState(url = loaded, fileLoaded = true, duration = duration, paused = paused)
        events.tryEmit(PlayerEvent.FileLoaded(loaded!!, duration))
    }

    override fun stop() { loaded = null }
    override fun setPaused(paused: Boolean) { this.paused = paused }
    override fun seek(position: Double) = Unit
    override fun seekRelative(offset: Double) = Unit
    override fun setSpeed(speed: Double) = Unit
    override fun selectTrack(type: TrackType, id: Int?) = Unit
    override fun showText(text: String, durationMs: Int) = Unit
    override val isFileLoaded: Boolean get() = state.value.fileLoaded
    override val isPaused: Boolean get() = paused
    override val duration: Double get() = length
    override fun currentPosition(): Double = 0.0
}
