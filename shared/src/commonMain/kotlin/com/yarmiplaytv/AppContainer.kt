package com.yarmiplaytv

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.KnownServer
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.device.DeviceKeyStore
import com.yarmiplaytv.device.DeviceKeys
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.jellyfin.ClientInfo
import com.yarmiplaytv.media.jellyfin.JellyfinClient
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexClient
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.relay.RelayManager
import com.yarmiplaytv.sync.HostJellyfinSharing
import com.yarmiplaytv.sync.MediaLocator
import com.yarmiplaytv.sync.PlaybackReporter
import com.yarmiplaytv.sync.PlaylistAutosave
import com.yarmiplaytv.sync.PlaylistController
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.ServerKind
import com.yarmiplaytv.syncplay.YarmiplayInfo
import com.yarmiplaytv.update.Updates
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide singletons (one mpv instance, one Syncplay connection, the media servers). */
class AppContainer(
    val settingsStore: SettingsStore,
    deviceName: String,
    val appVersion: String,
    createPlayer: (AppSettings) -> Player,
    createLocalLibrary: (SettingsStore, CoroutineScope, List<String>) -> LocalLibrary,
    /** Keys for YarmiplayServerTV device access; without them the client never opts in to its extensions. */
    private val deviceKeys: DeviceKeyStore? = null,
    /** Where relayed files are cached; without it this device doesn't use the file relay. */
    cacheDir: File? = null,
) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Logger.e(TAG, "Background task failed", e) },
    )

    /** Set by the UI host; decides the default Syncplay name and which UI is shown. */
    var deviceKind: DeviceKind = DeviceKind.TV

    private val initial: AppSettings = runBlocking { settingsStore.current() }
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, initial)

    private val clientInfo = ClientInfo(
        deviceName = deviceName,
        deviceId = runBlocking { settingsStore.deviceId() },
        version = appVersion,
    )
    val jellyfinClient = JellyfinClient(clientInfo)
    val plexClient = PlexClient(clientInfo)

    val player: Player = createPlayer(initial)

    /** Server-list saves run in the order the changes were made, so an older list never overwrites a newer one. */
    private val saveLock = Mutex()
    private val _servers = MutableStateFlow<List<MediaSource>>(
        sorted(initial.jellyfinServers.map { JellyfinSource(jellyfinClient, it) } + initial.plexServers.map { PlexSource(plexClient, it) }),
    )
    /** Every connected media server: the Jellyfin ones first, each kind in the order it was added. */
    val servers: StateFlow<List<MediaSource>> = _servers.asStateFlow()
    val jellyfinServers: StateFlow<List<JellyfinSource>> = servers.map { it.filterIsInstance<JellyfinSource>() }
        .stateIn(scope, SharingStarted.Eagerly, servers.value.filterIsInstance<JellyfinSource>())
    val plexServers: StateFlow<List<PlexSource>> = servers.map { it.filterIsInstance<PlexSource>() }
        .stateIn(scope, SharingStarted.Eagerly, servers.value.filterIsInstance<PlexSource>())

    /** What the UI browses: the one connected server, all of them side by side, or null. */
    val mediaSource: StateFlow<MediaSource?> = servers.map(::browsable).stateIn(scope, SharingStarted.Eagerly, browsable(servers.value))

    val local: LocalLibrary = createLocalLibrary(settingsStore, scope, initial.localFolders)
    val sync = SyncController(scope, player).apply {
        showChat = initial.showRoomChat
        appVersion = this@AppContainer.appVersion
        deviceAuth = deviceKeys?.let { keys -> DeviceKeys(keys) { deviceDisplayName } }
    }
    /** The YarmiplayServerTV file relay; idle on other servers. */
    val relay: RelayManager? = cacheDir?.let { RelayManager(scope, sync, player, local, it, settings.map { s -> s.shareFiles }) }
    private val locator = MediaLocator(local, servers) { name -> relay?.find(name) }.apply { preferredServer = initial.preferredServer }
    val playlist = PlaylistController(scope, sync, player, mediaSource, local, locator, relay)
    private val reporter = PlaybackReporter(scope, player, playlist.nowPlaying, servers).apply { enabled = initial.reportPlayback }
    private val autosave = PlaylistAutosave(scope, sync, playlist.shared, settingsStore).apply { enabled = initial.autosavePlaylists }
    private val jellyfinSharing = HostJellyfinSharing(
        scope, sync, jellyfinClient,
        saved = { jellyfinServers.value.map { it.session } },
        enabled = { settings.value.addSharedServers },
        save = ::addJellyfin,
    )
    /** Starts checking only when the host calls [Updates.checkOnLaunch]. */
    val updates = Updates(scope, settingsStore, appVersion)

    init {
        scope.launch {
            settings.collect { s ->
                sync.syncSettings = s.sync
                sync.showChat = s.showRoomChat
                playlist.autoReady = s.autoReadyOnLoad
                playlist.trustedDomains = s.trustedDomains
                playlist.onlySwitchToTrustedDomains = s.onlySwitchToTrustedDomains
                autosave.enabled = s.autosavePlaylists
                locator.preferredServer = s.preferredServer
                reporter.enabled = s.reportPlayback
            }
        }
        scope.launch {
            sync.room.map { it.yarmiplay }.distinctUntilChanged().collect { info -> rememberServer(info) }
        }
        jellyfinSharing.start()
        if (initial.autoConnect && initial.syncplay.isComplete) sync.connect(initial.syncplay.toConfig(), requestAccess = false)
        // Each saved server is checked on its own, so a slow or offline one doesn't hold up the others.
        servers.value.forEach { source ->
            when (source) {
                is JellyfinSource -> scope.launch { checkJellyfin(source) }
                is PlexSource -> scope.launch { checkPlex(source) }
            }
        }
    }

    /** Remembers whether the current Syncplay server is a YarmiplayServerTV, and its id and access mode. */
    private suspend fun rememberServer(info: YarmiplayInfo) {
        val server = sync.server ?: return
        val known = when (info.serverKind) {
            ServerKind.UNKNOWN -> return
            ServerKind.SYNCPLAY -> KnownServer(yarmiplay = false)
            ServerKind.YARMIPLAY -> {
                val before = settings.value.knownServers[server]
                KnownServer(true, info.serverId ?: before?.serverId.orEmpty(), info.access ?: before?.access.orEmpty())
            }
        }
        if (settings.value.knownServers[server] != known) settingsStore.saveKnownServer(server, known)
    }

    /** The name YarmiplayServerTV hosts see when this device asks for access. */
    val deviceDisplayName: String get() = settings.value.deviceName.ifBlank { DevicePlatform.deviceName }.trim().take(60)

    /** Whether this device can ask YarmiplayServerTV hosts for access (it has a key store). */
    val supportsDeviceAccess: Boolean get() = deviceKeys != null

    /** Whether this device holds a key for the Syncplay server [server] ("host:port") that it can forget. */
    fun hasDeviceKey(server: String?): Boolean =
        deviceKeys != null && server != null && settings.value.knownServers[server]?.serverId?.isNotEmpty() == true

    /** Deletes this device's key for [server]; its host has to approve the device again. */
    fun forgetDeviceKey(server: String) {
        val known = settings.value.knownServers[server]?.takeIf { it.serverId.isNotEmpty() } ?: return
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { deviceKeys?.forget(known.serverId) }.onFailure { Logger.w(TAG, "Couldn't delete the device key", it) }.isSuccess
            }
            if (!deleted) return@launch
            settingsStore.saveKnownServer(server, known.copy(serverId = ""))
            sync.postLocal("Forgot this device's key for $server; its host has to approve this device again")
        }
    }

    private suspend fun checkJellyfin(source: JellyfinSource) {
        val session = source.session
        val valid = runCatching { jellyfinClient.validate(session) }.getOrNull()
        when {
            valid == false && session.isShared -> {
                Logger.w(TAG, "${source.displayName} is no longer shared by its Syncplay host")
                if (!session.noLongerShared) put(JellyfinSource(jellyfinClient, session.copy(noLongerShared = true)))
            }
            valid == false -> {
                Logger.w(TAG, "Stored Jellyfin session for ${source.displayName} was revoked")
                removeServer(source.key)
            }
            valid == true && session.noLongerShared -> put(JellyfinSource(jellyfinClient, session.copy(noLongerShared = false)))
            valid == null && session.isShared -> movedJellyfin(session)?.let { put(JellyfinSource(jellyfinClient, it)) }
            else -> runCatching { source.refreshIndex() }
        }
    }

    /** A shared Jellyfin whose address stopped answering, at another address its host gave that works. */
    private suspend fun movedJellyfin(session: JellyfinSession): JellyfinSession? {
        for (url in session.candidateUrls) {
            if (url.trimEnd('/') == session.serverUrl) continue
            val base = runCatching { jellyfinClient.normalizeServerUrl(url) }.getOrNull() ?: continue
            if (runCatching { jellyfinClient.publicInfo(base) }.getOrNull()?.id != session.serverId) continue
            val moved = session.copy(serverUrl = base)
            if (runCatching { jellyfinClient.validate(moved) }.getOrDefault(false)) return moved
        }
        return null
    }

    private suspend fun checkPlex(source: PlexSource) {
        val refreshed = try {
            plexClient.refresh(source.session)
        } catch (e: MediaSourceException) {
            Logger.w(TAG, "Plex server ${source.displayName} unreachable: ${e.message}")
            source.session
        }
        when {
            refreshed == null -> {
                Logger.w(TAG, "Stored Plex session for ${source.displayName} was revoked")
                removeServer(source.key)
            }
            refreshed != source.session -> updatePlex(refreshed)
            else -> runCatching { source.refreshIndex() }
        }
    }

    /** Adds a Jellyfin server, or replaces the saved sign-in for the same server. */
    fun addJellyfin(session: JellyfinSession) = put(JellyfinSource(jellyfinClient, session))

    /** Adds a Plex server, or replaces the saved sign-in for the same server. */
    fun addPlex(session: PlexSession) = put(PlexSource(plexClient, session))

    /** The same Plex server found at a new address (or with a new token). */
    fun updatePlex(session: PlexSession) = put(PlexSource(plexClient, session))

    fun removeServer(key: String) {
        val updated = _servers.updateAndGet { list -> list.filter { it.key != key } }
        save(updated)
    }

    fun serverFor(key: String): MediaSource? = servers.value.firstOrNull { it.key == key }

    private fun put(source: MediaSource) {
        val updated = _servers.updateAndGet { list ->
            val i = list.indexOfFirst { it.key == source.key }
            sorted(if (i >= 0) list.toMutableList().apply { set(i, source) } else list + source)
        }
        save(updated)
        scope.launch {
            when (source) {
                is JellyfinSource -> runCatching { source.refreshIndex() }
                is PlexSource -> runCatching { source.refreshIndex() }
            }
        }
    }

    private fun save(list: List<MediaSource>) {
        val jellyfin = list.filterIsInstance<JellyfinSource>().map { it.session }
        val plex = list.filterIsInstance<PlexSource>().map { it.session }
        scope.launch { saveLock.withLock { settingsStore.saveServers(jellyfin, plex) } }
    }

    private companion object {
        const val TAG = "AppContainer"

        fun browsable(servers: List<MediaSource>): MediaSource? = when (servers.size) {
            0 -> null
            1 -> servers.single()
            else -> CompositeMediaSource(servers)
        }

        /** Jellyfin servers before Plex ones; a stable sort keeps each kind in the order it was added. */
        fun sorted(list: List<MediaSource>): List<MediaSource> = list.sortedBy { if (it is JellyfinSource) 0 else 1 }
    }
}
