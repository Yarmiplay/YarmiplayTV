package com.syncplaytv

import android.app.Application
import android.os.Build
import android.util.Log
import com.syncplaytv.data.AppSettings
import com.syncplaytv.data.SettingsStore
import com.syncplaytv.media.MediaSource
import com.syncplaytv.media.jellyfin.ClientInfo
import com.syncplaytv.media.jellyfin.JellyfinClient
import com.syncplaytv.media.jellyfin.JellyfinSession
import com.syncplaytv.media.jellyfin.JellyfinSource
import com.syncplaytv.player.MpvOptions
import com.syncplaytv.player.MpvPlayer
import com.syncplaytv.sync.PlaylistController
import com.syncplaytv.sync.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SyncplayTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Process-wide singletons (one mpv instance, one Syncplay connection, one media source). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore = SettingsStore(app)

    private val initial: AppSettings = runBlocking { settingsStore.current() }
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, initial)

    val jellyfinClient = JellyfinClient(
        ClientInfo(
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "Android TV" },
            deviceId = runBlocking { settingsStore.deviceId() },
            version = BuildConfig.VERSION_NAME,
        ),
    )

    val player = MpvPlayer(
        app,
        MpvOptions(
            hwdec = if (initial.playback.hardwareDecoding) "mediacodec,mediacodec-copy" else "no",
            audioLanguages = initial.playback.audioLanguages,
            subtitleLanguages = initial.playback.subtitleLanguages,
        ),
    )

    private val _mediaSource = MutableStateFlow<JellyfinSource?>(initial.jellyfin?.let { JellyfinSource(jellyfinClient, it) })
    val mediaSource: StateFlow<MediaSource?> = _mediaSource.asStateFlow()
    val jellyfin: StateFlow<JellyfinSource?> = _mediaSource.asStateFlow()

    val sync = SyncController(scope, player)
    val playlist = PlaylistController(scope, sync, player, mediaSource)

    init {
        scope.launch {
            settings.collect { s ->
                sync.syncSettings = s.sync
                playlist.autoReady = s.autoReadyOnLoad
            }
        }
        if (initial.autoConnect && initial.syncplay.isComplete) sync.connect(initial.syncplay.toConfig())
        _mediaSource.value?.let { source ->
            scope.launch {
                val valid = runCatching { jellyfinClient.validate(source.session) }.getOrDefault(true)
                if (!valid) {
                    Log.w("AppContainer", "Stored Jellyfin session was revoked")
                    setJellyfinSession(null)
                } else {
                    runCatching { source.refreshIndex() }
                }
            }
        }
    }

    fun setJellyfinSession(session: JellyfinSession?) {
        val source = session?.let { JellyfinSource(jellyfinClient, it) }
        _mediaSource.value = source
        scope.launch {
            settingsStore.saveJellyfin(session)
            source?.let { runCatching { it.refreshIndex() } }
        }
    }
}
