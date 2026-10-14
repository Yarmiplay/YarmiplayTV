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
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.data.SyncplayProfile
import com.yarmiplaytv.defaultSyncplayName
import com.yarmiplaytv.media.LanHosts
import com.yarmiplaytv.media.jellyfin.DiscoveredServer
import com.yarmiplaytv.media.jellyfin.JellyfinDiscovery
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.jellyfin.QuickConnectState
import com.yarmiplaytv.media.plex.PlexLinkState
import com.yarmiplaytv.media.plex.PlexServer
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.sync.discoverSyncplayServers
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.LanServer
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.ServerTlsFormat
import com.yarmiplaytv.syncplay.SyncplayDiscovery
import com.yarmiplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Form state and actions of the Syncplay connect screen, shared by the TV and mobile UIs. */
class SyncplayConnectModel(private val container: AppContainer) {
    private val saved = container.settings.value

    var host by mutableStateOf(saved.syncplay.host)
    var port by mutableStateOf(saved.syncplay.port.toString())
    var username by mutableStateOf(saved.syncplay.username.ifEmpty { defaultSyncplayName(container.deviceKind) })
    var room by mutableStateOf(saved.syncplay.room)
    var password by mutableStateOf(saved.syncplay.password)
    /** For a managed room (`+name:HASH`): saved like the others and sent once the room is joined. */
    var operatorPassword by mutableStateOf("")
    /** The room is a managed room's full name, so an operator password applies. */
    val showsOperatorPassword: Boolean get() = ManagedRooms.isManaged(room.trim())
    var tls by mutableStateOf(saved.syncplay.useTls)
    var autoConnect by mutableStateOf(saved.autoConnect)
    var error by mutableStateOf<String?>(null)
        private set
    /** The form's server as "host:port", or null while it's incomplete. */
    val serverKey: String?
        get() = port.toIntOrNull()?.takeIf { host.isNotBlank() }?.let { SyncController.serverKey(host, it) }

    /** YarmiplayServerTVs found on this network ([discover]). */
    var discovered by mutableStateOf<List<LanServer>>(emptyList())
        private set

    /** Looks for YarmiplayServerTVs on this network, on Wi-Fi or Ethernet only (stock Syncplay servers don't answer). */
    suspend fun discover() {
        discovered = discoverSyncplayServers(SyncplayDiscovery.COMMON_PORTS + listOfNotNull(port.toIntOrNull()))
    }

    /** Keep the room in the Saved rooms list once it's joined. */
    var saveRoom by mutableStateOf(false)

    /** Fills the form with a saved or recent room, to join it. */
    fun pick(room: SavedRoom) {
        host = room.host
        port = room.port.toString()
        tls = room.useTls
        username = room.username.ifBlank { username }
        this.room = room.room
        password = room.password
        operatorPassword = ""
        saveRoom = false
        error = null
    }

    /** Fills the form with a found server: its certificate's name (verified with TLS) or else its address. */
    fun pick(server: LanServer) {
        host = server.host
        port = server.port.toString()
        tls = server.tls != null || tls
        error = null
    }

    /** Set when [connect] needs the community rules agreed first; answered with [acceptRules] or [declineRules]. */
    var askRules by mutableStateOf(false)
        private set
    /** What [acceptRules] goes on with: [createManaged] rather than [connect]. */
    private var createAfterRules = false

    /**
     * Validates, saves and connects. Returns false when the form is incomplete (with [error] set) or the rules
     * haven't been agreed to yet (with [askRules] set).
     */
    fun connect(): Boolean {
        createAfterRules = false
        val profile = validProfile() ?: return false
        if (!container.settings.value.acceptedRoomRules) {
            askRules = true
            return false
        }
        join(profile)
        return true
    }

    /**
     * Like [connect], then makes a managed room named after the Room field and moves there as its operator. The
     * saved profile then points at the new room (`+name:HASH`), so the next connection goes straight back to it.
     */
    fun createManaged(): Boolean = createManaged(rulesAccepted = container.settings.value.acceptedRoomRules)

    private fun createManaged(rulesAccepted: Boolean): Boolean {
        createAfterRules = true
        val profile = validProfile() ?: return false
        error = when {
            ManagedRooms.isManaged(profile.room) -> "That's already a managed room; join it with Connect"
            !ManagedRooms.isValidBaseName(profile.room) -> "A managed room's name has at most ${ManagedRooms.MAX_BASE_NAME_LENGTH} characters"
            else -> null
        }
        if (error != null) return false
        if (!rulesAccepted) {
            askRules = true
            return false
        }
        join(profile, create = true)
        return true
    }

    /** Agrees to the rules and connects; returns whether it connected. */
    fun acceptRules(): Boolean {
        askRules = false
        container.scope.launch { container.settingsStore.saveAcceptedRoomRules(true) }
        // Saving the agreement takes a moment, so it's passed along rather than read back.
        if (createAfterRules) return createManaged(rulesAccepted = true)
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

    private fun join(profile: SyncplayProfile, create: Boolean = false) {
        val auto = autoConnect
        if (saveRoom && !create) container.roomHistory.saveWhenJoined(profile.port, profile.room)
        container.scope.launch { container.settingsStore.saveSyncplay(profile, auto) }
        if (create) {
            // Subscribed before connecting: events aren't replayed.
            container.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) {
                    container.sync.events.filterIsInstance<SyncplayEvent.ManagedRoomCreated>().first { ManagedRooms.display(it.room) == profile.room }
                } ?: return@launch
                room = created.room
                container.settingsStore.saveSyncplay(profile.copy(room = created.room), auto)
            }
        }
        container.sync.connect(
            profile.toConfig(),
            operatorPassword = operatorPassword.takeIf { showsOperatorPassword && !create },
            createManagedRoom = profile.room.takeIf { create },
        )
    }

    private companion object {
        const val CREATE_TIMEOUT_MS = 60_000L
    }

    fun disconnect() {
        container.sync.disconnect()
        autoConnect = false
        container.scope.launch { container.settingsStore.setAutoConnect(false) }
    }
}

@Composable
fun rememberSyncplayConnectModel(container: AppContainer): SyncplayConnectModel {
    val model = remember { SyncplayConnectModel(container) }
    LaunchedEffect(model) { runCatching { model.discover() } }
    return model
}

/** A found server's button: "myname.duckdns.org (192.168.1.20:8999) · TLS", or its address without a certificate. */
fun lanServerLabel(server: LanServer): String {
    val at = "${ServerTlsFormat.urlHost(server.address)}:${server.port}"
    return server.tls?.let { "${it.name} ($at) · TLS" } ?: "YarmiplayServerTV at $at"
}

/** The saved (first) and recent rooms on [server] ("host:port") other than [current], to switch to. */
fun roomsOnServer(rooms: List<SavedRoom>, server: String?, current: String): List<SavedRoom> =
    if (server == null) emptyList() else rooms.filter { it.key.substringBeforeLast('/') == server && it.room != current }.take(8)

/** A saved or recent room's button text: its name, or the room itself. */
fun savedRoomLabel(room: SavedRoom): String = room.label.ifBlank { ManagedRooms.label(room.room) }

/** "Connected at home via 192.168.1.20 · verified as myname.duckdns.org", when the server was reached at home. */
fun connectionRouteLine(room: RoomState): String? {
    val route = room.route?.takeIf { it.atHome } ?: return null
    return "Connected at home via ${route.dialed}" + (route.verifiedAs?.let { " · verified as $it" } ?: "")
}

/** The connect form's line about the connection: connecting, why it's reconnecting, or why it stopped. */
fun connectionStatusLine(status: ConnectionStatus, error: String?): String? = when (status) {
    ConnectionStatus.CONNECTING -> "Connecting…"
    ConnectionStatus.RECONNECTING -> error?.let { "Reconnecting: $it" } ?: "Reconnecting…"
    ConnectionStatus.DISCONNECTED -> error?.let { "Disconnected: $it" }
    ConnectionStatus.CONNECTED -> null
}

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

    /** Servers on HTTPS at an IP address are listed under their certificate's name, which they're verified as. */
    suspend fun discover() {
        val found = DevicePlatform.withMulticastLock("jellyfin-discovery") { JellyfinDiscovery.discover() }
        discovered = found
        discovered = coroutineScope {
            found.map { server -> async { server.copy(address = runCatching { LanHosts.byCertificateName(server.address) }.getOrDefault(server.address)) } }
                .awaitAll()
        }
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
