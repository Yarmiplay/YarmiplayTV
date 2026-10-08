package com.yarmiplaytv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.sync.TrustedDomains
import com.yarmiplaytv.syncplay.Constants
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.UnpauseMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

data class SyncplayProfile(
    val host: String = "syncplay.pl",
    val port: Int = Constants.DEFAULT_PORT,
    val username: String = "",
    val room: String = "",
    val password: String = "",
    val useTls: Boolean = true,
) {
    fun toConfig() = SyncplayConfig(
        host = host.trim(),
        port = port,
        username = username.trim(),
        room = room.trim(),
        password = password.ifEmpty { null },
        useTls = useTls,
    )

    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank() && room.isNotBlank()
}

data class PlaybackPrefs(
    val hardwareDecoding: Boolean = true,
    val audioLanguages: String = "jpn,ja",
    val subtitleLanguages: String = "eng,en",
    val seekStepSeconds: Int = 10,
    /** TV: how long the player's controls stay up while a video plays; 0 keeps them up. */
    val controlsHideSeconds: Int = 3,
)

data class AppSettings(
    val syncplay: SyncplayProfile = SyncplayProfile(),
    val autoConnect: Boolean = false,
    val sync: SyncSettings = SyncSettings(),
    val autoReadyOnLoad: Boolean = true,
    val playback: PlaybackPrefs = PlaybackPrefs(),
    /** Signed-in Jellyfin servers, one per server, in the order they were added. */
    val jellyfinServers: List<JellyfinSession> = emptyList(),
    val lastJellyfinUrl: String = "",
    /** Signed-in Plex servers, one per server, in the order they were added. */
    val plexServers: List<PlexSession> = emptyList(),
    /** Which server streams a room's file when several have it (a [com.yarmiplaytv.media.MediaSource.key]); empty = the first. */
    val preferredServer: String = "",
    /** Send playback progress and watched state to the servers that have the playing file. */
    val reportPlayback: Boolean = true,
    /** Persisted URIs of the user's media folders (content:// trees on Android, file:// on desktop). */
    val localFolders: List<String> = emptyList(),
    /** Syncplay's trusted domains: the room may switch everyone to URLs on these without asking. */
    val trustedDomains: List<String> = TrustedDomains.DEFAULT,
    /** Syncplay's "Only switch to trusted domains" (when off, any URL the room picks is opened). */
    val onlySwitchToTrustedDomains: Boolean = true,
    /** Remember each room's playlist and put it back when rejoining the room finds it empty. */
    val autosavePlaylists: Boolean = true,
    /** Look for a newer version on the download page when the app starts (not in Play builds). */
    val checkForUpdates: Boolean = true,
    /** Desktop: download updates when the app starts and install them when it closes. */
    val installUpdatesOnLaunch: Boolean = false,
    /** The update version the user dismissed; it isn't shown again. */
    val dismissedUpdate: String = "",
    /** Show other people's chat in rooms; off hides all of it, whatever names they use. */
    val showRoomChat: Boolean = true,
    /** The user agreed to the community rules, asked once before the first room join. */
    val acceptedRoomRules: Boolean = false,
    /** The name YarmiplayServerTV hosts see when approving this device; empty uses the platform's. */
    val deviceName: String = "",
    /** Add the Jellyfin a YarmiplayServerTV host shares with their room. */
    val addSharedServers: Boolean = true,
    /** Offer the room's YarmiplayServerTV file relay the playlist files in this device's media folders. */
    val shareFiles: Boolean = true,
    /** What each Syncplay server ("host:port") turned out to be when last joined. */
    val knownServers: Map<String, KnownServer> = emptyMap(),
)

/** What a Syncplay server was last time: a YarmiplayServerTV (with its id and access mode) or a stock one. */
data class KnownServer(
    val yarmiplay: Boolean,
    /** The server's id from its device challenge; this device has a key for it. */
    val serverId: String = "",
    /** `open`, `password` or `approved`. */
    val access: String = "",
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val host = stringPreferencesKey("sp_host")
        val port = intPreferencesKey("sp_port")
        val username = stringPreferencesKey("sp_username")
        val room = stringPreferencesKey("sp_room")
        val password = stringPreferencesKey("sp_password")
        val tls = booleanPreferencesKey("sp_tls")
        val autoConnect = booleanPreferencesKey("sp_auto_connect")

        val rewind = booleanPreferencesKey("sync_rewind")
        val slowdown = booleanPreferencesKey("sync_slowdown")
        val unpause = stringPreferencesKey("sync_unpause_mode")
        val readyAtStart = booleanPreferencesKey("sync_ready_at_start")
        val autoReady = booleanPreferencesKey("sync_auto_ready")
        val sharedPlaylists = booleanPreferencesKey("sync_shared_playlists")
        val loopPlaylist = booleanPreferencesKey("sync_loop_playlist")
        val loopSingleFile = booleanPreferencesKey("sync_loop_single_file")
        val trustedDomains = stringPreferencesKey("trusted_domains")
        val onlyTrusted = booleanPreferencesKey("only_trusted_domains")
        val autosavePlaylists = booleanPreferencesKey("autosave_playlists")
        val checkForUpdates = booleanPreferencesKey("update_check")
        val installUpdatesOnLaunch = booleanPreferencesKey("update_install_on_launch")
        val dismissedUpdate = stringPreferencesKey("update_dismissed")
        val showRoomChat = booleanPreferencesKey("chat_show")
        val acceptedRoomRules = booleanPreferencesKey("room_rules_accepted")
        /** Followed by "host:port/room"; the value is the playlist, one entry per line. */
        const val ROOM_PLAYLIST_PREFIX = "room_playlist:"
        fun roomPlaylist(key: String) = stringPreferencesKey(ROOM_PLAYLIST_PREFIX + key)

        val hwdec = booleanPreferencesKey("pb_hwdec")
        val alang = stringPreferencesKey("pb_alang")
        val slang = stringPreferencesKey("pb_slang")
        val seekStep = intPreferencesKey("pb_seek_step")
        val controlsHide = intPreferencesKey("pb_controls_hide")

        /** JSON lists of [JellyfinSession] and [PlexSession]. */
        val jfServers = stringPreferencesKey("jf_servers")
        val pxServers = stringPreferencesKey("px_servers")

        // Single-server keys from before several servers were supported; read once, removed on the next save.
        val jfUrl = stringPreferencesKey("jf_url")
        val jfServerName = stringPreferencesKey("jf_server_name")
        val jfServerId = stringPreferencesKey("jf_server_id")
        val jfUserId = stringPreferencesKey("jf_user_id")
        val jfUserName = stringPreferencesKey("jf_user_name")
        val jfToken = stringPreferencesKey("jf_token")
        val jfLastUrl = stringPreferencesKey("jf_last_url")

        val pxUrl = stringPreferencesKey("px_url")
        val pxServerName = stringPreferencesKey("px_server_name")
        val pxMachineId = stringPreferencesKey("px_machine_id")
        val pxUserName = stringPreferencesKey("px_user_name")
        val pxServerToken = stringPreferencesKey("px_server_token")
        val pxAccountToken = stringPreferencesKey("px_account_token")

        val preferredServer = stringPreferencesKey("preferred_server")
        val reportPlayback = booleanPreferencesKey("report_playback")

        val deviceId = stringPreferencesKey("device_id")
        val localFolders = stringPreferencesKey("local_folders")

        val deviceName = stringPreferencesKey("device_name")
        val addSharedServers = booleanPreferencesKey("add_shared_servers")
        val shareFiles = booleanPreferencesKey("share_files")
        /** One [KnownServer] per line: "host:port", "yarmiplay" or "syncplay", server id and access, tab-separated. */
        val knownServers = stringPreferencesKey("sp_known_servers")
    }

    val settings: Flow<AppSettings> = dataStore.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val jellyfinServers = p[Keys.jfServers]?.let { decode(it, jellyfinList) } ?: listOfNotNull(legacyJellyfin(p))
        val plexServers = p[Keys.pxServers]?.let { decode(it, plexList) } ?: listOfNotNull(legacyPlex(p))
        val preferred = when (val saved = p[Keys.preferredServer] ?: "") {
            JellyfinSource.KIND -> jellyfinServers.firstOrNull()?.let(JellyfinSource::keyOf) ?: ""
            PlexSource.KIND -> plexServers.firstOrNull()?.let(PlexSource::keyOf) ?: ""
            else -> saved
        }
        return AppSettings(
            syncplay = SyncplayProfile(
                host = p[Keys.host] ?: defaults.syncplay.host,
                port = p[Keys.port] ?: defaults.syncplay.port,
                username = p[Keys.username] ?: "",
                room = p[Keys.room] ?: "",
                password = p[Keys.password] ?: "",
                useTls = p[Keys.tls] ?: true,
            ),
            autoConnect = p[Keys.autoConnect] ?: false,
            sync = SyncSettings(
                rewindOnDesync = p[Keys.rewind] ?: true,
                slowOnDesync = p[Keys.slowdown] ?: true,
                unpauseMode = p[Keys.unpause]?.let { runCatching { UnpauseMode.valueOf(it) }.getOrNull() } ?: UnpauseMode.IF_OTHERS_READY,
                readyAtStart = p[Keys.readyAtStart] ?: false,
                sharedPlaylists = p[Keys.sharedPlaylists] ?: true,
                loopPlaylist = p[Keys.loopPlaylist] ?: false,
                loopSingleFile = p[Keys.loopSingleFile] ?: false,
            ),
            autoReadyOnLoad = p[Keys.autoReady] ?: true,
            playback = PlaybackPrefs(
                hardwareDecoding = p[Keys.hwdec] ?: true,
                audioLanguages = p[Keys.alang] ?: defaults.playback.audioLanguages,
                subtitleLanguages = p[Keys.slang] ?: defaults.playback.subtitleLanguages,
                seekStepSeconds = p[Keys.seekStep] ?: 10,
                controlsHideSeconds = p[Keys.controlsHide] ?: defaults.playback.controlsHideSeconds,
            ),
            jellyfinServers = jellyfinServers,
            lastJellyfinUrl = p[Keys.jfLastUrl] ?: "",
            plexServers = plexServers,
            preferredServer = preferred,
            reportPlayback = p[Keys.reportPlayback] ?: true,
            localFolders = p[Keys.localFolders]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            trustedDomains = p[Keys.trustedDomains]?.split('\n')?.filter { it.isNotBlank() } ?: defaults.trustedDomains,
            onlySwitchToTrustedDomains = p[Keys.onlyTrusted] ?: true,
            autosavePlaylists = p[Keys.autosavePlaylists] ?: true,
            checkForUpdates = p[Keys.checkForUpdates] ?: true,
            installUpdatesOnLaunch = p[Keys.installUpdatesOnLaunch] ?: false,
            dismissedUpdate = p[Keys.dismissedUpdate] ?: "",
            showRoomChat = p[Keys.showRoomChat] ?: true,
            acceptedRoomRules = p[Keys.acceptedRoomRules] ?: false,
            deviceName = p[Keys.deviceName] ?: "",
            addSharedServers = p[Keys.addSharedServers] ?: true,
            shareFiles = p[Keys.shareFiles] ?: true,
            knownServers = p[Keys.knownServers]?.let(::decodeKnown) ?: emptyMap(),
        )
    }

    private fun decodeKnown(text: String): Map<String, KnownServer> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0].isEmpty()) null else f[0] to KnownServer(f[1] == "yarmiplay", f[2], f[3])
    }.toMap()

    private fun encodeKnown(map: Map<String, KnownServer>): String = map.entries.joinToString("\n") { (server, k) ->
        listOf(server, if (k.yarmiplay) "yarmiplay" else "syncplay", k.serverId, k.access).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    suspend fun saveDeviceName(name: String) = dataStore.edit { it[Keys.deviceName] = name.take(60) }

    suspend fun saveAddSharedServers(on: Boolean) = dataStore.edit { it[Keys.addSharedServers] = on }

    suspend fun saveShareFiles(on: Boolean) = dataStore.edit { it[Keys.shareFiles] = on }

    /** Remembers what [server] ("host:port") is; null forgets it. */
    suspend fun saveKnownServer(server: String, known: KnownServer?) = dataStore.edit {
        val map = read(it).knownServers.toMutableMap()
        if (known == null) map.remove(server) else map[server] = known
        it[Keys.knownServers] = encodeKnown(map)
    }

    suspend fun saveSyncplay(profile: SyncplayProfile, autoConnect: Boolean? = null) {
        dataStore.edit {
            it[Keys.host] = profile.host.trim()
            it[Keys.port] = profile.port
            it[Keys.username] = profile.username.trim()
            it[Keys.room] = profile.room.trim()
            it[Keys.password] = profile.password
            it[Keys.tls] = profile.useTls
            if (autoConnect != null) it[Keys.autoConnect] = autoConnect
        }
    }

    suspend fun setAutoConnect(value: Boolean) = dataStore.edit { it[Keys.autoConnect] = value }

    suspend fun saveSync(sync: SyncSettings, autoReady: Boolean) {
        dataStore.edit {
            it[Keys.rewind] = sync.rewindOnDesync
            it[Keys.slowdown] = sync.slowOnDesync
            it[Keys.unpause] = sync.unpauseMode.name
            it[Keys.readyAtStart] = sync.readyAtStart
            it[Keys.autoReady] = autoReady
            it[Keys.sharedPlaylists] = sync.sharedPlaylists
            it[Keys.loopPlaylist] = sync.loopPlaylist
            it[Keys.loopSingleFile] = sync.loopSingleFile
        }
    }

    /** Just the playlist options of [SyncSettings], e.g. from the playlist panel's menu. */
    suspend fun savePlaylistOptions(sharedPlaylists: Boolean, loopPlaylist: Boolean, loopSingleFile: Boolean) {
        dataStore.edit {
            it[Keys.sharedPlaylists] = sharedPlaylists
            it[Keys.loopPlaylist] = loopPlaylist
            it[Keys.loopSingleFile] = loopSingleFile
        }
    }

    suspend fun saveTrustedDomains(domains: List<String>, onlySwitchToTrusted: Boolean) {
        dataStore.edit {
            it[Keys.trustedDomains] = domains.map { d -> d.trim() }.filter { d -> d.isNotEmpty() }.distinct().joinToString("\n")
            it[Keys.onlyTrusted] = onlySwitchToTrusted
        }
    }

    suspend fun saveAutosavePlaylists(on: Boolean) = dataStore.edit { it[Keys.autosavePlaylists] = on }

    suspend fun saveUpdatePrefs(check: Boolean, installOnLaunch: Boolean) = dataStore.edit {
        it[Keys.checkForUpdates] = check
        it[Keys.installUpdatesOnLaunch] = installOnLaunch
    }

    suspend fun saveDismissedUpdate(version: String) = dataStore.edit { it[Keys.dismissedUpdate] = version }

    suspend fun saveShowRoomChat(on: Boolean) = dataStore.edit { it[Keys.showRoomChat] = on }

    suspend fun saveAcceptedRoomRules(accepted: Boolean) = dataStore.edit { it[Keys.acceptedRoomRules] = accepted }

    /** The playlist saved for [room] ("host:port/room"), or empty. */
    suspend fun roomPlaylist(room: String): List<String> =
        dataStore.data.first()[Keys.roomPlaylist(room)]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    /** Saves [room]'s playlist; an empty one forgets it. */
    suspend fun saveRoomPlaylist(room: String, files: List<String>) = dataStore.edit {
        if (files.isEmpty()) it.remove(Keys.roomPlaylist(room)) else it[Keys.roomPlaylist(room)] = files.joinToString("\n")
    }

    /** Every saved room playlist by room; with [replaceRoomPlaylists], tests put the user's back. */
    suspend fun roomPlaylists(): Map<String, List<String>> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(Keys.ROOM_PLAYLIST_PREFIX) }
            .associate { (key, value) -> key.name.removePrefix(Keys.ROOM_PLAYLIST_PREFIX) to (value as String).split('\n').filter { it.isNotEmpty() } }

    suspend fun replaceRoomPlaylists(playlists: Map<String, List<String>>) = dataStore.edit { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith(Keys.ROOM_PLAYLIST_PREFIX) }.forEach { prefs.remove(it) }
        playlists.filterValues { it.isNotEmpty() }.forEach { (room, files) -> prefs[Keys.roomPlaylist(room)] = files.joinToString("\n") }
    }

    suspend fun savePlayback(prefs: PlaybackPrefs) {
        dataStore.edit {
            it[Keys.hwdec] = prefs.hardwareDecoding
            it[Keys.alang] = prefs.audioLanguages
            it[Keys.slang] = prefs.subtitleLanguages
            it[Keys.seekStep] = prefs.seekStepSeconds
            it[Keys.controlsHide] = prefs.controlsHideSeconds
        }
    }

    /** Saves every signed-in server in one edit, replacing the saved lists (and any single-server keys from older versions). */
    suspend fun saveServers(jellyfin: List<JellyfinSession>, plex: List<PlexSession>) {
        dataStore.edit {
            val preferred = read(it).preferredServer
            it[Keys.jfServers] = json.encodeToString(jellyfinList, jellyfin)
            it[Keys.pxServers] = json.encodeToString(plexList, plex)
            LEGACY_SERVER_KEYS.forEach { k -> it.remove(k) }
            if (it[Keys.preferredServer] != null) it[Keys.preferredServer] = preferred
        }
    }

    suspend fun saveLastJellyfinUrl(url: String) = dataStore.edit { it[Keys.jfLastUrl] = url }

    private fun legacyJellyfin(p: Preferences): JellyfinSession? {
        val token = p[Keys.jfToken] ?: return null
        return JellyfinSession(
            serverUrl = p[Keys.jfUrl] ?: "",
            serverName = p[Keys.jfServerName] ?: "",
            serverId = p[Keys.jfServerId] ?: "",
            userId = p[Keys.jfUserId] ?: "",
            userName = p[Keys.jfUserName] ?: "",
            accessToken = token,
        )
    }

    private fun legacyPlex(p: Preferences): PlexSession? {
        val token = p[Keys.pxServerToken] ?: return null
        return PlexSession(
            serverUrl = p[Keys.pxUrl] ?: "",
            serverName = p[Keys.pxServerName] ?: "",
            machineId = p[Keys.pxMachineId] ?: "",
            userName = p[Keys.pxUserName] ?: "",
            serverToken = token,
            accountToken = p[Keys.pxAccountToken] ?: token,
        )
    }

    private fun <T> decode(text: String, serializer: KSerializer<List<T>>): List<T> =
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val jellyfinList = ListSerializer(JellyfinSession.serializer())
        val plexList = ListSerializer(PlexSession.serializer())
        val LEGACY_SERVER_KEYS = listOf(
            Keys.jfUrl, Keys.jfServerName, Keys.jfServerId, Keys.jfUserId, Keys.jfUserName, Keys.jfToken,
            Keys.pxUrl, Keys.pxServerName, Keys.pxMachineId, Keys.pxUserName, Keys.pxServerToken, Keys.pxAccountToken,
        )
    }

    suspend fun saveServerPrefs(preferredServer: String, reportPlayback: Boolean) = dataStore.edit {
        it[Keys.preferredServer] = preferredServer
        it[Keys.reportPlayback] = reportPlayback
    }

    suspend fun saveLocalFolders(uris: List<String>) = dataStore.edit { it[Keys.localFolders] = uris.joinToString("\n") }

    suspend fun deviceId(): String {
        var id: String? = null
        dataStore.edit {
            id = it[Keys.deviceId] ?: UUID.randomUUID().toString().also { new -> it[Keys.deviceId] = new }
        }
        return id!!
    }
}
