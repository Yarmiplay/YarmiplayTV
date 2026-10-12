package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.ROOM_PRIVACY_HINT
import com.yarmiplaytv.ui.shared.ROOM_RULES
import com.yarmiplaytv.ui.shared.ROOM_RULES_URL
import com.yarmiplaytv.ui.shared.SyncplayConnectModel
import com.yarmiplaytv.ui.shared.approvedOnly
import com.yarmiplaytv.ui.shared.connectionStatusLine
import com.yarmiplaytv.ui.shared.isSharedServer
import com.yarmiplaytv.ui.shared.lanServerLabel
import com.yarmiplaytv.ui.shared.plexServerLabel
import com.yarmiplaytv.ui.shared.rememberJellyfinLoginModel
import com.yarmiplaytv.ui.shared.rememberPlexLoginModel
import com.yarmiplaytv.ui.shared.rememberSyncplayConnectModel
import com.yarmiplaytv.ui.shared.serverDetail
import com.yarmiplaytv.ui.shared.serverTag
import com.yarmiplaytv.ui.shared.signOutOfServer
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

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
        if (connected && !editing) {
            RoomLayout(container, nav, model, onEdit = { editing = true })
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
                ConnectForm(container, model, room.status, onConnected = { editing = false })
            }
        }
    }
}

/**
 * The room while connected. Wide windows get columns, so nothing is a long scroll: the playlist (what's watched),
 * the chat filling the height, and the room itself (people, ready, saving and switching rooms). Phones keep one
 * column in that order.
 */
@Composable
private fun RoomLayout(container: AppContainer, nav: Navigator, model: SyncplayConnectModel, onEdit: () -> Unit) {
    val onBrowse = { nav.switchTab(Screen.Home) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val height = maxHeight
        when {
            maxWidth >= 1000.dp -> Row(Modifier.fillMaxSize().testTag("room_columns_3")) {
                Column(Modifier.weight(1.4f).fillMaxHeight().padding(top = 8.dp)) {
                    PlaylistContent(container, onBrowse, maxHeight = (height - 150.dp).coerceAtLeast(200.dp))
                }
                VerticalDivider(color = AppColors.SurfaceHigh)
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)) {
                    ChatContent(container, fill = true)
                }
                VerticalDivider(color = AppColors.SurfaceHigh)
                Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
                    RoomColumn(container, model, onEdit)
                }
            }
            maxWidth >= 700.dp -> Row(Modifier.fillMaxSize().testTag("room_columns_2")) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
                    PlaylistContent(container, onBrowse, maxHeight = (height * 0.5f).coerceAtLeast(200.dp))
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
                    RoomColumn(container, model, onEdit)
                }
                VerticalDivider(color = AppColors.SurfaceHigh)
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)) {
                    ChatContent(container, fill = true)
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("room_columns_1")) {
                // What's watched first, then talking about it, the room's people, and last leaving for another room.
                PlaylistContent(container, onBrowse)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
                ChatContent(container, maxHeight = 300.dp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
                RoomColumn(container, model, onEdit)
            }
        }
    }
}

/** The room's people and ready state, saving it, switching rooms, and leaving. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomColumn(container: AppContainer, model: SyncplayConnectModel, onEdit: () -> Unit) {
    RoomUsersContent(container, showChangeRoom = false)
    SaveCurrentRoom(container, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.SurfaceHigh)
    RoomSwitchContent(container)
    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onEdit) { Text("Edit connection") }
        OutlinedButton({ model.disconnect() }, Modifier.testTag("disconnect")) { Text("Disconnect") }
    }
}

/** "Save this room" (★) for the room you're in, or "Saved" to take it off the list. */
@Composable
private fun SaveCurrentRoom(container: AppContainer, modifier: Modifier = Modifier) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val server = container.sync.server ?: return
    val key = "$server/${room.room}"
    val entry = settings.savedRooms.firstOrNull { it.key == key }
    var naming by remember { mutableStateOf(false) }
    if (entry?.saved == true) {
        TextButton({ container.scope.launch { container.settingsStore.setRoomSaved(key, false) } }, modifier.testTag("unsave_current_room")) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Saved" + entry.label.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty())
        }
    } else {
        TextButton({ naming = true }, modifier.testTag("save_current_room")) {
            Icon(Icons.Filled.StarBorder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save this room")
        }
    }
    if (naming) {
        RoomNameDialog("Save this room", "") { label ->
            naming = false
            if (label == null) return@RoomNameDialog
            container.scope.launch {
                val config = container.sync.client?.config ?: return@launch
                val host = server.substringBeforeLast(':')
                val port = server.substringAfterLast(':').toIntOrNull() ?: config.port
                container.settingsStore.saveRoom(
                    SavedRoom(host, port, config.useTls, config.username, room.room, config.password.orEmpty(), label = label, lastUsedMillis = System.currentTimeMillis()),
                )
            }
        }
    }
}

/** Asks for a name for a saved room (optional); [onDone] gets it, or null when cancelled. */
@Composable
private fun RoomNameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it.copy(text = it.text.take(40)) },
                label = { Text("Name (optional)") },
                placeholder = { Text("e.g. Anime night") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("room_name_field"),
            )
        },
        confirmButton = { Button({ onDone(name.text.trim()) }, Modifier.testTag("room_name_save")) { Text("Save") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } },
    )
}

/**
 * Joining a room. Wide windows: the rooms you know (saved, recent, found on your network) on the left and the
 * form on the right; phones stack them. The form groups the server, you and the room, with the rarely needed
 * options folded away.
 */
@Composable
private fun ConnectForm(container: AppContainer, model: SyncplayConnectModel, status: ConnectionStatus, onConnected: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) {
            Row(
                Modifier.align(Alignment.TopCenter).widthIn(max = 1100.dp).fillMaxWidth().padding(20.dp).testTag("join_two_panes"),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { KnownRooms(container, model, onConnected) }
                JoinCard(container, model, status, onConnected, Modifier.weight(1.1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KnownRooms(container, model, onConnected, compact = true)
                JoinCard(container, model, status, onConnected, Modifier.fillMaxWidth())
            }
        }
    }
    if (model.askRules) RoomRulesDialog(onAgree = { if (model.acceptRules()) onConnected() }, onDecline = model::declineRules)
}

/** Saved rooms, recent rooms and YarmiplayServerTVs on this network; a short note while there are none. */
@Composable
private fun KnownRooms(container: AppContainer, model: SyncplayConnectModel, onConnected: () -> Unit, compact: Boolean = false) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val saved = settings.savedRooms.filter { it.saved }
    val recent = settings.savedRooms.filterNot { it.saved }
    if (saved.isNotEmpty()) {
        Text("Saved rooms", style = MaterialTheme.typography.titleMedium)
        saved.forEach { KnownRoomRow(container, model, it, onConnected) }
    }
    if (recent.isNotEmpty()) {
        Text("Recent rooms", style = MaterialTheme.typography.titleMedium)
        recent.forEach { KnownRoomRow(container, model, it, onConnected) }
    }
    if (model.discovered.isNotEmpty()) {
        Text("Found on your network", style = MaterialTheme.typography.titleMedium)
        model.discovered.forEach { server ->
            Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Dns, contentDescription = null, tint = AppColors.TextDim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(lanServerLabel(server), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton({ model.pick(server) }, Modifier.testTag("found_${server.address}:${server.port}")) { Text("Use") }
                }
            }
        }
    }
    if (!compact && saved.isEmpty() && recent.isEmpty() && model.discovered.isEmpty()) {
        Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How rooms work", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everyone joins the same room on the same Syncplay server, and playback stays in sync. Rooms you join " +
                        "show up here to rejoin with one click; star one to keep it with its own name.",
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A saved or recent room: one click (or Rejoin) joins it; the star keeps it; the menu renames or forgets it. */
@Composable
private fun KnownRoomRow(container: AppContainer, model: SyncplayConnectModel, room: SavedRoom, onConnected: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    fun join() {
        model.pick(room)
        if (model.connect()) onConnected()
    }
    Surface(
        onClick = ::join,
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("known_room_${room.room}"),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                { if (room.saved) container.scope.launch { container.settingsStore.setRoomSaved(room.key, false) } else naming = room.label },
                Modifier.testTag("star_${room.room}"),
            ) {
                Icon(
                    if (room.saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (room.saved) "Remove from saved rooms" else "Save this room",
                    tint = if (room.saved) AppColors.Accent else AppColors.TextDim,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(room.label.ifBlank { ManagedRooms.label(room.room) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = listOfNotNull(
                    ManagedRooms.label(room.room).takeIf { room.label.isNotBlank() },
                    "${room.host}:${room.port}",
                    room.username.takeIf { it.isNotBlank() }?.let { "as $it" },
                ).joinToString(" · ")
                Text(where, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(::join, Modifier.testTag("rejoin_${room.room}")) { Text("Rejoin") }
            Box {
                IconButton({ menu = true }, Modifier.testTag("room_menu_${room.room}")) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (room.saved) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; naming = room.label })
                        DropdownMenuItem({ Text("Remove from saved") }, { menu = false; container.scope.launch { container.settingsStore.setRoomSaved(room.key, false) } })
                    } else {
                        DropdownMenuItem({ Text("Save…") }, { menu = false; naming = "" })
                    }
                    DropdownMenuItem({ Text("Forget") }, { menu = false; container.scope.launch { container.settingsStore.forgetSavedRoom(room.key) } }, Modifier.testTag("forget_${room.room}"))
                }
            }
        }
    }
    naming?.let { initial ->
        RoomNameDialog(if (room.saved) "Rename room" else "Save this room", initial) { label ->
            naming = null
            if (label != null) container.scope.launch { container.settingsStore.setRoomSaved(room.key, true, label) }
        }
    }
}

/** The form: server, you, room, then the buttons; passwords and auto-connect under "More options". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JoinCard(container: AppContainer, model: SyncplayConnectModel, status: ConnectionStatus, onConnected: () -> Unit, modifier: Modifier) {
    val settings by container.settings.collectAsStateWithLifecycle()
    var more by remember { mutableStateOf(model.password.isNotEmpty() || model.autoConnect) }
    Surface(color = AppColors.Surface, shape = RoundedCornerShape(16.dp), modifier = modifier.testTag("join_card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Join a room", style = MaterialTheme.typography.titleLarge)

            FormLabel("Server")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(model.host, { model.host = it.trim() }, label = { Text("Server") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f).testTag("field_host"))
                OutlinedTextField(model.port, { model.port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp).testTag("field_port"))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TLS", color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
                    Switch(model.tls, { model.tls = it })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Public servers:", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                (8995..8999).forEach { port ->
                    SuggestionChip(
                        onClick = { model.host = "syncplay.pl"; model.port = port.toString() },
                        label = { Text("$port") },
                        modifier = Modifier.testTag("public_$port"),
                    )
                }
            }

            FormLabel("You")
            OutlinedTextField(model.username, { model.username = it.take(16) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field_name"))

            FormLabel("Room")
            OutlinedTextField(model.room, { model.room = it.take(60) }, label = { Text("Room") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field_room"))
            if (model.showsOperatorPassword) {
                OutlinedTextField(
                    model.operatorPassword, { model.operatorPassword = it.uppercase().take(20) },
                    label = { Text("Operator password (optional)") },
                    placeholder = { Text("AB-123-456") },
                    supportingText = { Text("Makes you an operator of this managed room once you've joined") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("field_operator_password"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model.saveRoom, { model.saveRoom = it }, Modifier.testTag("save_room"))
                Text("Save this room", Modifier.clickable { model.saveRoom = !model.saveRoom })
            }

            TextButton({ more = !more }, Modifier.testTag("more_options")) {
                Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("More options")
            }
            if (more) {
                if (!approvedOnly(settings.knownServers, model.host, model.port)) {
                    OutlinedTextField(model.password, { model.password = it }, label = { Text("Server password (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("field_server_password"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connect automatically on start", Modifier.weight(1f))
                    Switch(model.autoConnect, { model.autoConnect = it }, Modifier.testTag("auto_connect"))
                }
                model.serverKey?.takeIf(container::hasDeviceKey)?.let { server ->
                    TextButton({ container.forgetDeviceKey(server) }, Modifier.testTag("forget_device_key")) { Text("Forget this device's key") }
                }
            }

            model.error?.let { Text(it, color = AppColors.Error) }
            val connectionError by container.sync.connectionError.collectAsStateWithLifecycle()
            connectionStatusLine(status, connectionError)?.let {
                Text(it, color = if (status == ConnectionStatus.DISCONNECTED) AppColors.Error else AppColors.TextDim, modifier = Modifier.testTag("connection_status"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (model.connect()) onConnected() }, Modifier.testTag("connect")) { Text("Join room") }
                if (!model.showsOperatorPassword) {
                    // Joins the room above, then turns it into a managed one with you as its operator.
                    OutlinedButton({ if (model.createManaged()) onConnected() }, Modifier.testTag("connect_create_managed")) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create managed room")
                    }
                }
                if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING) {
                    CircularProgressIndicator(Modifier.size(32.dp).padding(start = 4.dp))
                    TextButton({ model.disconnect() }, Modifier.testTag("connect_cancel")) { Text("Cancel") }
                }
            }
            Text(
                "Use the same server and room as your friends' desktop Syncplay. " + ROOM_PRIVACY_HINT,
                color = AppColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text.uppercase(), color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RoomRulesDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Community rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ROOM_RULES.forEach { Text("• $it") }
                Text("Also at $ROOM_RULES_URL", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onAgree, Modifier.testTag("agree_rules")) { Text("I agree") } },
        dismissButton = { TextButton(onDecline) { Text("Cancel") } },
        modifier = Modifier.testTag("room_rules"),
    )
}

@Composable
fun MobileJellyfinScreen(container: AppContainer, nav: Navigator) {
    val model = rememberJellyfinLoginModel(container)
    val onSignedIn = { nav.back(); Unit }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Jellyfin", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
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

@Composable
fun MobilePlexScreen(container: AppContainer, nav: Navigator) {
    val model = rememberPlexLoginModel(container)
    val onSignedIn = { nav.back(); Unit }
    LaunchedEffect(model) { model.useSavedAccount(onSignedIn) }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Plex", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormColumn {
                Text("Link this device to your Plex account, then pick a server.", color = AppColors.TextDim)
                val code = model.linkCode
                if (code != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            model.status?.let { Text(it, color = AppColors.TextDim) }
                            Text(
                                code,
                                style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textDirection = TextDirection.Ltr),
                                color = AppColors.Accent,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceHigh).padding(horizontal = 16.dp, vertical = 8.dp).testTag("plex_code"),
                            )
                            TextButton({ model.cancel() }) { Text("Cancel") }
                        }
                    }
                } else {
                    model.status?.let { Text(it, color = AppColors.TextDim) }
                    if (model.savedAccount != null) {
                        OutlinedButton({ model.startLink(onSignedIn) }, enabled = !model.busy, modifier = Modifier.testTag("plex_link")) { Text("Use a different Plex account") }
                    } else if (model.servers.isEmpty()) {
                        Button({ model.startLink(onSignedIn) }, enabled = !model.busy, modifier = Modifier.testTag("plex_link")) { Text("Get a link code") }
                    }
                }
                model.servers.forEach { server ->
                    val added = model.isAdded(server)
                    OutlinedButton({ model.pick(server, onSignedIn) }, Modifier.fillMaxWidth().testTag("plex_server"), enabled = !model.busy && !added) {
                        Text(plexServerLabel(server, added))
                    }
                }
                if (model.busy) CircularProgressIndicator()
                model.error?.let { Text(it, color = AppColors.Error) }
            }
        }
    }
}

/** Every signed-in Jellyfin and Plex server, each with its own sign-out, and buttons to add more. */
@Composable
fun MobileServersScreen(container: AppContainer, nav: Navigator) {
    val servers by container.servers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Media servers", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("servers")) {
            if (servers.isEmpty()) EmptyMessage("No media servers yet")
            servers.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("server_${serverTag(s.key)}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(serverDetail(s), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton({ signOutOfServer(container, s.key) }, Modifier.testTag("signout_${serverTag(s.key)}")) {
                        Text(if (isSharedServer(s)) "Remove" else "Sign out")
                    }
                }
            }
            FormColumn {
                Button({ nav.push(Screen.JellyfinLogin) }, Modifier.testTag("add_jellyfin")) { Text("Add Jellyfin server") }
                Button({ nav.push(Screen.PlexLogin) }, Modifier.testTag("add_plex")) { Text("Add Plex server") }
            }
        }
    }
}
