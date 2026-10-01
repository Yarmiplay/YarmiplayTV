package com.syncplaytv

import com.syncplaytv.data.AppSettings
import com.syncplaytv.data.SettingsStore
import com.syncplaytv.local.LocalLibrary
import com.syncplaytv.media.MediaSource
import com.syncplaytv.media.jellyfin.ClientInfo
import com.syncplaytv.media.jellyfin.JellyfinClient
import com.syncplaytv.media.jellyfin.JellyfinSession
import com.syncplaytv.media.jellyfin.JellyfinSource
import com.syncplaytv.player.Player
import com.syncplaytv.sync.PlaylistController
import com.syncplaytv.sync.SyncController
import kotlinx.coroutines.CoroutineExceptionHandler
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

/** Process-wide singletons (one mpv instance, one Syncplay connection, one media source). */
class AppContainer(
    val settingsStore: SettingsStore,
    deviceName: String,
    appVersion: String,
    createPlayer: (AppSettings) -> Player,
    createLocalLibrary: (SettingsStore, CoroutineScope, List<String>) -> LocalLibrary,
) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Logger.e(TAG, "Background task failed", e) },
    )

    /** Set by the UI host; decides the default Syncplay name and which UI is shown. */
    var deviceKind: DeviceKind = DeviceKind.TV

    private val initial: AppSettings = runBlocking { settingsStore.current() }
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, initial)

    val jellyfinClient = JellyfinClient(
        ClientInfo(
            deviceName = deviceName,
            deviceId = runBlocking { settingsStore.deviceId() },
            version = appVersion,
        ),
    )

    val player: Player = createPlayer(initial)

    private val _mediaSource = MutableStateFlow<JellyfinSource?>(initial.jellyfin?.let { JellyfinSource(jellyfinClient, it) })
    val mediaSource: StateFlow<MediaSource?> = _mediaSource.asStateFlow()
    val jellyfin: StateFlow<JellyfinSource?> = _mediaSource.asStateFlow()

    val local: LocalLibrary = createLocalLibrary(settingsStore, scope, initial.localFolders)
    val sync = SyncController(scope, player)
    val playlist = PlaylistController(scope, sync, player, mediaSource, local)

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
                    Logger.w(TAG, "Stored Jellyfin session was revoked")
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

    private companion object {
        const val TAG = "AppContainer"
    }
}
