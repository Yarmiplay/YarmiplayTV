package com.yarmiplaytv.ui.connect

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Pill
import com.yarmiplaytv.ui.components.ToggleRow
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.shared.rememberSyncplayConnectModel
import com.yarmiplaytv.ui.theme.AppColors

@Composable
fun SyncplayConnectScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val model = rememberSyncplayConnectModel(container)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val connected = room.status != ConnectionStatus.DISCONNECTED
    fun connect() {
        if (model.connect()) nav.back()
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
            TvTextField(model.host, { model.host = it }, "Server", Modifier.weight(3f).focusRequester(firstFocus), placeholder = "syncplay.pl", keyboardType = KeyboardType.Uri)
            TvTextField(model.port, { model.port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TvTextField(model.username, { model.username = it.take(40) }, "Your name", Modifier.weight(1f))
            TvTextField(model.room, { model.room = it.take(60) }, "Room", Modifier.weight(1f), onSubmit = ::connect)
        }
        TvTextField(model.password, { model.password = it }, "Server password (optional)", password = true)
        ToggleRow("Secure connection (TLS)", model.tls, { model.tls = !model.tls }, subtitle = "Falls back to plain TCP if the server doesn't support it")
        ToggleRow("Connect automatically when the app starts", model.autoConnect, { model.autoConnect = !model.autoConnect })
        model.error?.let { Text(it, color = AppColors.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(if (connected) "Reconnect" else "Connect", ::connect, icon = Icons.Filled.Link, primary = true)
            if (connected) ActionButton("Disconnect", model::disconnect, icon = Icons.Filled.LinkOff)
        }
        if (connected && room.motd != null) {
            Text(room.motd ?: "", color = AppColors.TextDim, modifier = Modifier.width(900.dp))
        }
    }
}
