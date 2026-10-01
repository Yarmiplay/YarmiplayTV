package com.syncplaytv.ui.shared

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.syncplaytv.AppContainer
import com.syncplaytv.DeviceUi
import com.syncplaytv.data.SyncplayProfile
import com.syncplaytv.media.jellyfin.DiscoveredServer
import com.syncplaytv.media.jellyfin.JellyfinDiscovery
import com.syncplaytv.media.jellyfin.QuickConnectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Form state and actions of the Syncplay connect screen, shared by the TV and mobile UIs. */
class SyncplayConnectModel(private val container: AppContainer) {
    private val saved = container.settings.value

    var host by mutableStateOf(saved.syncplay.host)
    var port by mutableStateOf(saved.syncplay.port.toString())
    var username by mutableStateOf(saved.syncplay.username.ifEmpty { DeviceUi.defaultUserName(container.deviceKind) })
    var room by mutableStateOf(saved.syncplay.room)
    var password by mutableStateOf(saved.syncplay.password)
    var tls by mutableStateOf(saved.syncplay.useTls)
    var autoConnect by mutableStateOf(saved.autoConnect)
    var error by mutableStateOf<String?>(null)
        private set

    /** Validates, saves and connects. Returns false (with [error] set) when the form is incomplete. */
    fun connect(): Boolean {
        val p = port.toIntOrNull()
        error = when {
            host.isBlank() -> "Enter a server"
            p == null || p !in 1..65535 -> "Invalid port"
            username.isBlank() -> "Enter your name"
            room.isBlank() -> "Enter a room name"
            else -> null
        }
        if (error != null) return false
        val profile = SyncplayProfile(host.trim(), p!!, username.trim(), room.trim(), password, tls)
        container.scope.launch { container.settingsStore.saveSyncplay(profile, autoConnect) }
        container.sync.connect(profile.toConfig())
        return true
    }

    fun disconnect() {
        container.sync.disconnect()
        autoConnect = false
        container.scope.launch { container.settingsStore.setAutoConnect(false) }
    }
}

@Composable
fun rememberSyncplayConnectModel(container: AppContainer): SyncplayConnectModel = remember { SyncplayConnectModel(container) }

/** Jellyfin sign-in: LAN discovery, Quick Connect and username/password, shared by the TV and mobile UIs. */
class JellyfinLoginModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var url by mutableStateOf(container.settings.value.lastJellyfinUrl.ifEmpty { if (isEmulator()) "http://10.0.2.2:8096" else "" })
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

    suspend fun discover(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("jellyfin-discovery")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            discovered = JellyfinDiscovery.discover()
        } finally {
            runCatching { lock?.release() }
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
                            container.setJellyfinSession(state.session)
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
                container.setJellyfinSession(session)
                onSignedIn()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        val source = container.jellyfin.value ?: return
        container.scope.launch { runCatching { container.jellyfinClient.logout(source.session) } }
        container.setJellyfinSession(null)
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
}

@Composable
fun rememberJellyfinLoginModel(container: AppContainer): JellyfinLoginModel {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = remember { JellyfinLoginModel(container, scope) }
    LaunchedEffect(model) { model.discover(context) }
    DisposableEffect(model) { onDispose { model.cancelQuickConnect() } }
    return model
}
