package com.syncplaytv.ui.connect

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.syncplaytv.AppContainer
import com.syncplaytv.data.SyncplayProfile
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.ui.Navigator
import com.syncplaytv.ui.components.ActionButton
import com.syncplaytv.ui.components.Pill
import com.syncplaytv.ui.components.ToggleRow
import com.syncplaytv.ui.components.TvTextField
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun SyncplayConnectScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val saved = settings.syncplay
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf(saved.host) }
    var port by rememberSaveable { mutableStateOf(saved.port.toString()) }
    var username by rememberSaveable { mutableStateOf(saved.username.ifEmpty { defaultName() }) }
    var roomName by rememberSaveable { mutableStateOf(saved.room) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    var tls by rememberSaveable { mutableStateOf(saved.useTls) }
    var autoConnect by rememberSaveable { mutableStateOf(settings.autoConnect) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val connected = room.status != ConnectionStatus.DISCONNECTED

    fun connect() {
        val p = port.toIntOrNull()
        error = when {
            host.isBlank() -> "Enter a server"
            p == null || p !in 1..65535 -> "Invalid port"
            username.isBlank() -> "Enter your name"
            roomName.isBlank() -> "Enter a room name"
            else -> null
        }
        if (error != null) return
        val profile = SyncplayProfile(host.trim(), p!!, username.trim(), roomName.trim(), password, tls)
        container.scope.launch { container.settingsStore.saveSyncplay(profile, autoConnect) }
        container.sync.connect(profile.toConfig())
        nav.back()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Syncplay room", style = MaterialTheme.typography.headlineMedium)
            when (room.status) {
                ConnectionStatus.CONNECTED -> Pill("Connected to ${room.room}" + if (room.tls) " · TLS" else "", AppColors.Ready)
                ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Pill("Connecting…", AppColors.NotReady)
                ConnectionStatus.DISCONNECTED -> Unit
            }
        }
        Text(
            "Everyone in the same room on the same server watches in sync. Use the public server syncplay.pl (ports 8995–8999) or your own.",
            color = AppColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TvTextField(host, { host = it }, "Server", Modifier.weight(3f).focusRequester(firstFocus), placeholder = "syncplay.pl", keyboardType = KeyboardType.Uri)
            TvTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TvTextField(username, { username = it.take(40) }, "Your name", Modifier.weight(1f))
            TvTextField(roomName, { roomName = it.take(60) }, "Room", Modifier.weight(1f), onSubmit = ::connect)
        }
        TvTextField(password, { password = it }, "Server password (optional)", password = true)
        ToggleRow("Secure connection (TLS)", tls, { tls = !tls }, subtitle = "Falls back to plain TCP if the server doesn't support it")
        ToggleRow("Connect automatically when the app starts", autoConnect, { autoConnect = !autoConnect })
        error?.let { Text(it, color = AppColors.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(if (connected) "Reconnect" else "Connect", ::connect, icon = Icons.Filled.Link, primary = true)
            if (connected) {
                ActionButton("Disconnect", {
                    container.sync.disconnect()
                    container.scope.launch { container.settingsStore.setAutoConnect(false) }
                }, icon = Icons.Filled.LinkOff)
            }
        }
        if (connected && room.motd != null) {
            Text(room.motd ?: "", color = AppColors.TextDim, modifier = Modifier.width(900.dp))
        }
    }
}

private fun defaultName(): String {
    val model = Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9]"), "").take(12)
    return if (model.isEmpty()) "TV" else "TV-$model"
}
