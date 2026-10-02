package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.rememberJellyfinLoginModel
import com.yarmiplaytv.ui.shared.rememberSyncplayConnectModel
import com.yarmiplaytv.ui.theme.AppColors

@Composable
fun MobileRoomScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val model = rememberSyncplayConnectModel(container)
    var editing by remember { mutableStateOf(false) }
    val connected = room.status == ConnectionStatus.CONNECTED

    Column(Modifier.fillMaxSize()) {
        MobileTopBar(if (connected && !editing) "Room" else "Join a Syncplay room", nav, showBack = editing) {
            if (editing) TextButton({ editing = false }) { Text("Cancel") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            if (connected && !editing) {
                RoomUsersContent(container)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ editing = true }) { Text("Edit connection") }
                    OutlinedButton({ model.disconnect() }, Modifier.testTag("disconnect")) { Text("Disconnect") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
                PlaylistContent(container, onBrowse = { nav.switchTab(Screen.Home) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
                ChatContent(container, maxHeight = 300.dp)
            } else {
                ConnectForm(container, model, room.status, onConnected = { editing = false })
            }
        }
    }
}

@Composable
private fun ConnectForm(container: AppContainer, model: com.yarmiplaytv.ui.shared.SyncplayConnectModel, status: ConnectionStatus, onConnected: () -> Unit) {
    FormColumn {
        OutlinedTextField(model.host, { model.host = it.trim() }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field_host"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(model.port, { model.port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("field_port"))
            Text("TLS", color = AppColors.TextDim)
            Switch(model.tls, { model.tls = it })
        }
        OutlinedTextField(model.username, { model.username = it.take(16) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field_name"))
        OutlinedTextField(model.room, { model.room = it.take(60) }, label = { Text("Room") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field_room"))
        OutlinedTextField(model.password, { model.password = it }, label = { Text("Server password (optional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connect automatically on start", Modifier.weight(1f))
            Switch(model.autoConnect, { model.autoConnect = it }, Modifier.testTag("auto_connect"))
        }
        model.error?.let { Text(it, color = AppColors.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ if (model.connect()) onConnected() }, Modifier.testTag("connect")) { Text("Connect") }
            if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING) {
                CircularProgressIndicator(Modifier.padding(start = 8.dp))
                TextButton({ model.disconnect() }) { Text("Cancel") }
            }
        }
        Text(
            "Use the same server and room as your friends' desktop Syncplay. The public servers are syncplay.pl ports 8995–8999.",
            color = AppColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun MobileJellyfinScreen(container: AppContainer, nav: Navigator) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val model = rememberJellyfinLoginModel(container)
    val onSignedIn = { nav.back(); Unit }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Jellyfin", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            val s = source
            if (s != null) {
                FormColumn {
                    Text("Signed in to ${s.displayName}", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton({ model.signOut() }, Modifier.testTag("jellyfin_signout")) { Text("Sign out") }
                }
                return@Column
            }
            FormColumn {
                OutlinedTextField(model.url, { model.url = it.trim() }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8096") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("jellyfin_url"))
                if (model.discovered.isNotEmpty()) {
                    Text("Found on your network", color = AppColors.TextDim, style = MaterialTheme.typography.labelLarge)
                    model.discovered.forEach { server ->
                        OutlinedButton({ model.url = server.address }, Modifier.fillMaxWidth()) { Text("${server.name} · ${server.address}") }
                    }
                }
                Text("Quick Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                val code = model.quickCode
                if (code != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            model.status?.let { Text(it, color = AppColors.TextDim) }
                            Text(
                                code,
                                style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textDirection = TextDirection.Ltr),
                                color = AppColors.Accent,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceHigh).padding(horizontal = 16.dp, vertical = 8.dp).testTag("quick_code"),
                            )
                            TextButton({ model.cancelQuickConnect() }) { Text("Cancel") }
                        }
                    }
                } else {
                    model.status?.let { Text(it, color = AppColors.TextDim) }
                    Button({ model.startQuickConnect(onSignedIn) }, enabled = model.url.isNotBlank(), modifier = Modifier.testTag("quick_connect")) { Text("Get a Quick Connect code") }
                }
                Text("Or sign in with a password", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(model.username, { model.username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("jellyfin_user"))
                OutlinedTextField(model.password, { model.password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                model.error?.let { Text(it, color = AppColors.Error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({ model.login(onSignedIn) }, enabled = !model.busy && model.url.isNotBlank() && model.username.isNotBlank(), modifier = Modifier.testTag("jellyfin_login")) { Text("Sign in") }
                    if (model.busy) CircularProgressIndicator(Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
