package com.syncplaytv.ui.connect

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.syncplaytv.AppContainer
import com.syncplaytv.media.jellyfin.DiscoveredServer
import com.syncplaytv.media.jellyfin.JellyfinDiscovery
import com.syncplaytv.media.jellyfin.QuickConnectState
import com.syncplaytv.ui.Navigator
import com.syncplaytv.ui.components.ActionButton
import com.syncplaytv.ui.components.Panel
import com.syncplaytv.ui.components.TvTextField
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun JellyfinLoginScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val source by container.jellyfin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var url by rememberSaveable { mutableStateOf(settings.lastJellyfinUrl.ifEmpty { if (isEmulator()) "http://10.0.2.2:8096" else "" }) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var quickCode by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var quickJob by remember { mutableStateOf<Job?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("jellyfin-discovery")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            discovered = JellyfinDiscovery.discover()
        } finally {
            runCatching { lock?.release() }
        }
    }
    DisposableEffect(Unit) { onDispose { quickJob?.cancel() } }

    fun startQuickConnect() {
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
                            nav.back()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
                status = null
                quickCode = null
            }
        }
    }

    fun login() {
        quickJob?.cancel()
        error = null
        busy = true
        scope.launch {
            try {
                container.settingsStore.saveLastJellyfinUrl(url)
                val session = container.jellyfinClient.login(url, username.trim(), password)
                container.setJellyfinSession(session)
                nav.back()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Jellyfin", style = MaterialTheme.typography.headlineMedium)
        source?.let { s ->
            Panel {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Signed in to ${s.displayName}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.session.userName} · ${s.session.serverUrl}", color = AppColors.TextDim)
                    }
                    ActionButton("Sign out", {
                        scope.launch { container.jellyfinClient.logout(s.session) }
                        container.setJellyfinSession(null)
                    }, icon = Icons.Filled.Logout)
                }
            }
        }
        Text("Server address, e.g. http://192.168.1.10:8096. Inside the emulator your PC is 10.0.2.2.", color = AppColors.TextDim)
        TvTextField(url, { url = it.trim() }, "Server", Modifier.focusRequester(firstFocus), placeholder = "http://192.168.1.10:8096", keyboardType = KeyboardType.Uri)
        if (discovered.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                discovered.forEach { server ->
                    ActionButton("${server.name} (${server.address})", { url = server.address }, icon = Icons.Filled.Dns)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton("Quick Connect", ::startQuickConnect, icon = Icons.Filled.QrCode, primary = true, enabled = url.isNotBlank())
            ActionButton("Username & password", { showPassword = !showPassword; quickJob?.cancel(); quickCode = null; status = null }, icon = Icons.Filled.Key)
        }
        status?.let { Text(it, color = AppColors.TextDim) }
        quickCode?.let { code ->
            Text(code.chunked(3).joinToString(" "), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, letterSpacing = 8.sp)
        }
        if (showPassword) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvTextField(username, { username = it }, "Username", Modifier.weight(1f))
                TvTextField(password, { password = it }, "Password", Modifier.weight(1f), password = true, onSubmit = ::login)
            }
            ActionButton(if (busy) "Signing in…" else "Sign in", ::login, primary = true, enabled = !busy && username.isNotBlank() && url.isNotBlank())
        }
        error?.let { Text(it, color = AppColors.Error) }
    }
}

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
