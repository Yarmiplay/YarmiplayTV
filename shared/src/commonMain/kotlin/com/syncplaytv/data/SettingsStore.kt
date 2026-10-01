package com.syncplaytv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.syncplaytv.media.jellyfin.JellyfinSession
import com.syncplaytv.syncplay.Constants
import com.syncplaytv.syncplay.SyncSettings
import com.syncplaytv.syncplay.SyncplayConfig
import com.syncplaytv.syncplay.UnpauseMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
)

data class AppSettings(
    val syncplay: SyncplayProfile = SyncplayProfile(),
    val autoConnect: Boolean = false,
    val sync: SyncSettings = SyncSettings(),
    val autoReadyOnLoad: Boolean = true,
    val playback: PlaybackPrefs = PlaybackPrefs(),
    val jellyfin: JellyfinSession? = null,
    val lastJellyfinUrl: String = "",
    /** Persisted URIs of the user's media folders (content:// trees on Android, file:// on desktop). */
    val localFolders: List<String> = emptyList(),
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

        val hwdec = booleanPreferencesKey("pb_hwdec")
        val alang = stringPreferencesKey("pb_alang")
        val slang = stringPreferencesKey("pb_slang")
        val seekStep = intPreferencesKey("pb_seek_step")

        val jfUrl = stringPreferencesKey("jf_url")
        val jfServerName = stringPreferencesKey("jf_server_name")
        val jfServerId = stringPreferencesKey("jf_server_id")
        val jfUserId = stringPreferencesKey("jf_user_id")
        val jfUserName = stringPreferencesKey("jf_user_name")
        val jfToken = stringPreferencesKey("jf_token")
        val jfLastUrl = stringPreferencesKey("jf_last_url")

        val deviceId = stringPreferencesKey("device_id")
        val localFolders = stringPreferencesKey("local_folders")
    }

    val settings: Flow<AppSettings> = dataStore.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val token = p[Keys.jfToken]
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
            ),
            autoReadyOnLoad = p[Keys.autoReady] ?: true,
            playback = PlaybackPrefs(
                hardwareDecoding = p[Keys.hwdec] ?: true,
                audioLanguages = p[Keys.alang] ?: defaults.playback.audioLanguages,
                subtitleLanguages = p[Keys.slang] ?: defaults.playback.subtitleLanguages,
                seekStepSeconds = p[Keys.seekStep] ?: 10,
            ),
            jellyfin = if (token != null) JellyfinSession(
                serverUrl = p[Keys.jfUrl] ?: "",
                serverName = p[Keys.jfServerName] ?: "",
                serverId = p[Keys.jfServerId] ?: "",
                userId = p[Keys.jfUserId] ?: "",
                userName = p[Keys.jfUserName] ?: "",
                accessToken = token,
            ) else null,
            lastJellyfinUrl = p[Keys.jfLastUrl] ?: "",
            localFolders = p[Keys.localFolders]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        )
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
        }
    }

    suspend fun savePlayback(prefs: PlaybackPrefs) {
        dataStore.edit {
            it[Keys.hwdec] = prefs.hardwareDecoding
            it[Keys.alang] = prefs.audioLanguages
            it[Keys.slang] = prefs.subtitleLanguages
            it[Keys.seekStep] = prefs.seekStepSeconds
        }
    }

    suspend fun saveJellyfin(session: JellyfinSession?) {
        dataStore.edit {
            if (session == null) {
                listOf(Keys.jfUrl, Keys.jfServerName, Keys.jfServerId, Keys.jfUserId, Keys.jfUserName, Keys.jfToken).forEach { k -> it.remove(k) }
            } else {
                it[Keys.jfUrl] = session.serverUrl
                it[Keys.jfServerName] = session.serverName
                it[Keys.jfServerId] = session.serverId
                it[Keys.jfUserId] = session.userId
                it[Keys.jfUserName] = session.userName
                it[Keys.jfToken] = session.accessToken
                it[Keys.jfLastUrl] = session.serverUrl
            }
        }
    }

    suspend fun saveLastJellyfinUrl(url: String) = dataStore.edit { it[Keys.jfLastUrl] = url }

    suspend fun saveLocalFolders(uris: List<String>) = dataStore.edit { it[Keys.localFolders] = uris.joinToString("\n") }

    suspend fun deviceId(): String {
        var id: String? = null
        dataStore.edit {
            id = it[Keys.deviceId] ?: UUID.randomUUID().toString().also { new -> it[Keys.deviceId] = new }
        }
        return id!!
    }
}
