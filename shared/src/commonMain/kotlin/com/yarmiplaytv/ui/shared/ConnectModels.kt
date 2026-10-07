package com.yarmiplaytv.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.defaultSyncplayName
import com.yarmiplaytv.media.jellyfin.DiscoveredServer
import com.yarmiplaytv.media.jellyfin.JellyfinDiscovery
import com.yarmiplaytv.media.jellyfin.QuickConnectState
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexLinkState
import com.yarmiplaytv.media.plex.PlexServer
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.sync.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Form state and actions of the Syncplay connect screen, shared by the TV and mobile UIs. */
class SyncplayConnectModel(private val container: AppContainer) {
    private val saved = container.settings.value

    var host by mutableStateOf(saved.syncplay.host)
    var port by mutableStateOf(saved.syncplay.port.toString())
    var username by mutableStateOf(saved.syncplay.username.ifEmpty { defaultSyncplayName(container.deviceKind) })
    var room by mutableStateOf(saved.syncplay.room)
    var password by mutableStateOf(saved.syncplay.password)
    var tls by mutableStateOf(saved.syncplay.useTls)
    var autoConnect by mutableStateOf(saved.autoConnect)
    var error by mutableStateOf<String?>(null)
        private set
    /** The form's server as "host:port", or null while it's incomplete. */
    val serverKey: String?
        get() = port.toIntOrNull()?.takeIf { host.isNotBlank() }?.let { SyncController.serverKey(host, it) }

    /** Set when [connect] needs the community rules agreed first; answered with [acceptRules] or [declineRules]. */
    var askRules by mutableStateOf(false)
        private set

    /**
     * Validates, saves and connects. Returns false when the form is incomplete (with [error] set) or the rules
     * haven't been agreed to yet (with [askRules] set).
     */
    fun connect(): Boolean {
        val profile = validProfile() ?: return false
        if (!container.settings.value.acceptedRoomRules) {
            askRules = true
            return false
        }
        join(profile)
        return true
    }

    /** Agrees to the rules and connects; returns whether it connected. */
    fun acceptRules(): Boolean {
        askRules = false
        container.scope.launch { container.settingsStore.saveAcceptedRoomRules(true) }
        val profile = validProfile() ?: return false
        join(profile)
        return true
    }

    fun declineRules() {
        askRules = false
    }

    private fun validProfile(): SyncplayProfile? {
        val p = port.toIntOrNull()
        error = when {
            host.isBlank() -> "Enter a server"
            p == null || p !in 1..65535 -> "Invalid port"
            username.isBlank() -> "Enter your name"
            room.isBlank() -> "Enter a room name"
            else -> null
        }
        return if (error == null) SyncplayProfile(host.trim(), p!!, username.trim(), room.trim(), password, tls) else null
    }

    private fun join(profile: SyncplayProfile) {
        container.scope.launch { container.settingsStore.saveSyncplay(profile, autoConnect) }
        container.sync.connect(profile.toConfig())
    }

    fun disconnect() {
        container.sync.disconnect()
        autoConnect = false
        container.scope.launch { container.settingsStore.setAutoConnect(false) }
    }
}

@Composable
fun rememberSyncplayConnectModel(container: AppContainer): SyncplayConnectModel = remember { SyncplayConnectModel(container) }

/**
 * Adds a Jellyfin server: LAN discovery, Quick Connect and username/password, shared by the TV and
 * mobile UIs. Signing in to a server that's already added replaces its saved sign-in.
 */
class JellyfinLoginModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var url by mutableStateOf(
        container.settings.value.lastJellyfinUrl.ifEmpty { if (DevicePlatform.isEmulator) "http://10.0.2.2:8096" else "" }
            .takeUnless { last -> container.jellyfinServers.value.any { it.session.serverUrl == last } } ?: "",
    )
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var quickCode by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set
    private var quickJob: Job? = null

    suspend fun discover() {
        discovered = DevicePlatform.withMulticastLock("jellyfin-discovery") { JellyfinDiscovery.discover() }
    }

    fun startQuickConnect(onSignedIn: () -> Unit) {
        quickJob?.cancel()
        error = null
        quickCode = null
        status = "Contacting server…"
        quickJob = scope.launch {
            try {
                container.settingsStore.saveLastJellyfinUrl(url)
                container.jellyfinClient.quickConnect(url).collect { state ->
                    when (state) {
                        is QuickConnectState.WaitingForApproval -> {
                            quickCode = state.code
                            status = "On your phone or PC open Jellyfin → your profile → Quick Connect, and enter this code:"
                        }
                        is QuickConnectState.Authorized -> {
                            container.addJellyfin(state.session)
                            onSignedIn()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
                status = null
                quickCode = null
            }
        }
    }

    fun cancelQuickConnect() {
        quickJob?.cancel()
        quickCode = null
        status = null
    }

    fun login(onSignedIn: () -> Unit) {
        cancelQuickConnect()
        error = null
        busy = true
        scope.launch {
            try {
                container.settingsStore.saveLastJellyfinUrl(url)
                val session = container.jellyfinClient.login(url, username.trim(), password)
                container.addJellyfin(session)
                onSignedIn()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

}

/** Signs out of one server (any kind); Jellyfin also ends the session on the server. */
fun signOutOfServer(container: AppContainer, key: String) {
    val source = container.serverFor(key) ?: return
    if (source is JellyfinSource) container.scope.launch { runCatching { container.jellyfinClient.logout(source.session) } }
    container.removeServer(key)
}

/**
 * Adds a Plex server: a plex.tv/link code, then a server from the account; shared by the TV and
 * mobile UIs. With a Plex server already added, [useSavedAccount] lists that account's servers
 * without a new code.
 */
class PlexLoginModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var linkCode by mutableStateOf<String?>(null)
        private set
    var servers by mutableStateOf<List<PlexServer>>(emptyList())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var accountToken: String? = null
    private var userName = ""
    private var job: Job? = null

    fun startLink(onSignedIn: () -> Unit) {
        cancel()
        error = null
        servers = emptyList()
        status = "Contacting plex.tv…"
        job = scope.launch {
            try {
                container.plexClient.link().collect { state ->
                    when (state) {
                        is PlexLinkState.WaitingForLink -> {
                            linkCode = state.code
                            status = "On your phone or PC go to plex.tv/link and enter this code:"
                        }
                        is PlexLinkState.Linked -> {
                            linkCode = null
                            accountToken = state.accountToken
                            userName = state.userName
                            loadServers(onSignedIn)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
                status = null
                linkCode = null
            }
        }
    }

    /** The account of the most recently added Plex server, or null when there's none. */
    val savedAccount: PlexSession? get() = container.plexServers.value.lastOrNull()?.session

    /** Lists the servers of [savedAccount]'s Plex account, skipping the link code. */
    fun useSavedAccount(onSignedIn: () -> Unit) {
        val saved = savedAccount ?: return
        cancel()
        error = null
        accountToken = saved.accountToken
        userName = saved.userName
        job = scope.launch {
            try {
                loadServers(onSignedIn)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
                status = null
            }
        }
    }

    fun isAdded(server: PlexServer): Boolean = container.serverFor(PlexSource.keyOf(server)) != null

    private suspend fun loadServers(onSignedIn: () -> Unit) {
        val token = accountToken ?: return
        status = "Looking for your servers…"
        val found = container.plexClient.servers(token)
        servers = found
        val notAdded = found.filterNot(::isAdded)
        when {
            found.isEmpty() -> {
                status = null
                error = "No Plex Media Server on this account"
            }
            notAdded.isEmpty() -> status = "Every server on this account is already added."
            notAdded.size == 1 -> connect(notAdded.single(), onSignedIn)
            else -> status = "Pick a server:"
        }
    }

    fun pick(server: PlexServer, onSignedIn: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try {
                connect(server, onSignedIn)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
                status = null
            }
        }
    }

    private suspend fun connect(server: PlexServer, onSignedIn: () -> Unit) {
        val token = accountToken ?: return
        error = null
        busy = true
        status = "Connecting to ${server.name}…"
        try {
            container.addPlex(container.plexClient.connect(server, token, userName))
            status = null
            servers = emptyList()
            onSignedIn()
        } finally {
            busy = false
        }
    }

    fun cancel() {
        job?.cancel()
        linkCode = null
        status = null
        busy = false
    }
}

@Composable
fun rememberPlexLoginModel(container: AppContainer): PlexLoginModel {
    val scope = rememberCoroutineScope()
    val model = remember { PlexLoginModel(container, scope) }
    DisposableEffect(model) { onDispose { model.cancel() } }
    return model
}

@Composable
fun rememberJellyfinLoginModel(container: AppContainer): JellyfinLoginModel {
    val scope = rememberCoroutineScope()
    val model = remember { JellyfinLoginModel(container, scope) }
    LaunchedEffect(model) { model.discover() }
    DisposableEffect(model) { onDispose { model.cancelQuickConnect() } }
    return model
}
