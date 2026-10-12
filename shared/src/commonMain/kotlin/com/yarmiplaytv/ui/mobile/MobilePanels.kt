package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.ui.player.fileDifferenceNote
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.player.sizeWarning
import com.yarmiplaytv.ui.shared.BLOCKED_NOTE
import com.yarmiplaytv.ui.shared.HIDDEN_CHAT_NOTE
import com.yarmiplaytv.ui.shared.connectionRouteLine
import com.yarmiplaytv.ui.shared.relayNote
import com.yarmiplaytv.ui.shared.roomsOnServer
import com.yarmiplaytv.ui.shared.savedRoomLabel
import com.yarmiplaytv.ui.shared.serverStatusLine
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistContent(container: AppContainer, onBrowse: () -> Unit, modifier: Modifier = Modifier, maxHeight: androidx.compose.ui.unit.Dp = 480.dp) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SheetTitle("Shared playlist", if (room.status == ConnectionStatus.CONNECTED) "Everyone in '${ManagedRooms.label(room.room)}' sees these changes" else null)
        if (room.status != ConnectionStatus.CONNECTED) {
            Text("Join a Syncplay room to use the shared playlist.", color = AppColors.TextDim)
            return@Column
        }
        val np = nowPlaying
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            androidx.compose.material3.FilledTonalButton(onBrowse) { Text("Add from library") }
            if (np != null && room.playlist.none { Filenames.same(it, np.fileName) }) {
                androidx.compose.material3.OutlinedButton({ container.sync.client?.addToPlaylist(np.fileName) }) {
                    Icon(Icons.Filled.Add, contentDescription = null); Text(" Add current")
                }
            }
        }
        if (room.playlist.isEmpty()) {
            Text("The playlist is empty.", color = AppColors.TextDim, modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (room.playlistIndex ?: 0).coerceIn(0, room.playlist.lastIndex))
        LazyColumn(state = state, modifier = Modifier.heightIn(max = maxHeight)) {
            itemsIndexed(room.playlist) { index, file ->
                val current = index == room.playlistIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (current) AppColors.Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { container.playlist.selectIndex(index) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current) Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.width(28.dp))
                    else Text("${index + 1}.", color = AppColors.TextDim, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(file, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        room.sizeWarning(file)?.let { warning ->
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = AppColors.NotReady, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(warning, color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    IconButton({ container.playlist.move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                    IconButton({ container.playlist.move(index, index + 1) }, enabled = index < room.playlist.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    IconButton({ container.playlist.removeIndex(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = AppColors.Error) }
                }
            }
        }
    }
}

/**
 * The room, its ready button and its users; with [showChangeRoom] also [RoomSwitchContent]'s options under them
 * (the player's room sheet and the desktop panel). The Room tab shows those at its very end instead.
 */
@Composable
fun RoomUsersContent(container: AppContainer, modifier: Modifier = Modifier, showChangeRoom: Boolean = true) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (room.status != ConnectionStatus.CONNECTED) {
            SheetTitle("Room")
            Text(if (room.status == ConnectionStatus.DISCONNECTED) "Not connected to Syncplay." else "Connecting…", color = AppColors.TextDim)
            return@Column
        }
        SheetTitle(ManagedRooms.label(room.room), "${container.sync.client?.config?.host ?: ""} · Syncplay ${room.serverVersion ?: ""}")
        serverStatusLine(room.yarmiplay)?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall) }
        connectionRouteLine(room)?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connection_route")) }
        val relayStatus = container.relay?.let { relay ->
            val current by relay.status.collectAsStateWithLifecycle()
            current
        }
        val streamingFrom = relayStatus?.takeIf { !it.downloading && !it.complete }?.seeders?.toSet().orEmpty()
        relayStatus?.let { s ->
            if (streamingFrom.isEmpty()) {
                Text(s.text, color = AppColors.Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("relay_status"))
            }
            if (s.downloading && !s.complete) RelayDownloadBar(s.fraction, Modifier.testTag("relay_download_bar"))
            s.error?.let { Text(it, color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall) }
        }
        ReadyChip(room.isReady == true, { container.sync.toggleReady() }, Modifier.testTag("ready_toggle"))
        val blocked by container.sync.blocked.collectAsStateWithLifecycle()
        val me = room.users.firstOrNull { it.name == room.username }
        room.users.forEach { user ->
            val isMe = user.name == room.username
            val difference = if (isMe) null else fileDifferenceNote(user.file, me?.file)
            val mismatch = difference != null
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when (user.isReady) {
                            true -> AppColors.Ready
                            false -> AppColors.NotReady
                            null -> AppColors.TextDim
                        },
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        user.name + (if (isMe) " (you)" else "") + (if (user.isController) " ★ operator" else "") + (if (user.name in streamingFrom) " · streaming" else ""),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = if (user.name in streamingFrom) Modifier.testTag("relay_status") else Modifier,
                    )
                    Text(
                        user.file?.let { "${it.name} (${formatClock(it.duration)})" } ?: "No file open",
                        color = if (mismatch) AppColors.NotReady else AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (difference != null) Text(difference, color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall)
                    relayNote(room, user)?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall) }
                    if (user.name in blocked) Text(BLOCKED_NOTE, color = AppColors.Error, style = MaterialTheme.typography.labelSmall)
                }
                if (!isMe && room.isOperator) {
                    val markReady = user.isReady != true
                    IconButton({ container.sync.setOthersReady(user.name, markReady) }, Modifier.testTag("mark_ready_${user.name}")) {
                        Icon(
                            if (markReady) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            if (markReady) "Mark ${user.name} ready" else "Mark ${user.name} not ready",
                            tint = if (markReady) AppColors.Ready else AppColors.NotReady,
                        )
                    }
                }
                if (!isMe) {
                    val isBlocked = user.name in blocked
                    IconButton(
                        { if (isBlocked) container.sync.unblock(user.name) else container.sync.block(user.name) },
                        Modifier.testTag("block_${user.name}"),
                    ) {
                        Icon(
                            Icons.Filled.Block,
                            if (isBlocked) "Unblock ${user.name}" else "Block ${user.name} (hide their messages)",
                            tint = if (isBlocked) AppColors.Error else AppColors.TextDim,
                        )
                    }
                }
            }
        }
        if (showChangeRoom) RoomSwitchOptions(container, room)
    }
}

/** Switching rooms and the managed-room options, while connected; nothing otherwise. */
@Composable
fun RoomSwitchContent(container: AppContainer, modifier: Modifier = Modifier) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    if (room.status != ConnectionStatus.CONNECTED) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoomSwitchOptions(container, room)
    }
}

@Composable
private fun RoomSwitchOptions(container: AppContainer, room: RoomState) {
    var newRoom by rememberSaveable { mutableStateOf("") }
    fun change() { if (newRoom.isNotBlank()) { container.sync.changeRoom(newRoom.trim()); newRoom = "" } }
    OutlinedTextField(
        newRoom, { newRoom = it.take(60) },
        label = { Text("Switch room") },
        placeholder = { Text(ManagedRooms.label(room.room)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { change() }),
        trailingIcon = { IconButton(::change) { Icon(Icons.Filled.MeetingRoom, "Change room") } },
        modifier = Modifier.fillMaxWidth(),
    )
    val settings by container.settings.collectAsStateWithLifecycle()
    val others = roomsOnServer(settings.savedRooms, container.sync.server, room.room)
    if (others.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { r ->
                AssistChip(
                    onClick = { container.sync.changeRoom(r.room) },
                    label = { Text(savedRoomLabel(r), maxLines = 1) },
                    leadingIcon = if (r.saved) ({ Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = AppColors.Accent) }) else null,
                    modifier = Modifier.testTag("switch_to_${r.room}"),
                )
            }
        }
    }
    if (room.managedRooms) ManagedRoomActions(container, room)
}

/** "[label]: [value]" with its own copy button. */
@Composable
private fun CopyRow(label: String, value: String, copyDescription: String, buttonModifier: Modifier, textModifier: Modifier = Modifier, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: $value",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier.weight(1f),
        )
        IconButton(onCopy, buttonModifier) { Icon(Icons.Filled.ContentCopy, copyDescription, tint = AppColors.TextDim) }
    }
}

/**
 * Syncplay's managed rooms, on servers that have them: the room's full name and (for its operator) the password to
 * share, identifying as operator, and creating a new managed room.
 */
@Composable
private fun ManagedRoomActions(container: AppContainer, room: RoomState) {
    val clipboard = LocalClipboardManager.current
    var newManaged by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    if (room.inManagedRoom) {
        val passwordChanges by container.sync.operatorPasswordChanges.collectAsStateWithLifecycle()
        val saved = remember(room.room, room.isOperator, passwordChanges) { if (room.isOperator) container.sync.savedOperatorPassword(room.room) else null }
        Surface(color = AppColors.SurfaceHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("managed_room_info")) {
            Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (room.isOperator) "Managed room · you're an operator" else "Managed room · only operators control playback and the playlist",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The name is for everyone joining; the password only for those who should be operators too.
                CopyRow("Room", room.room, "Copy the room name", Modifier.testTag("copy_room_name")) { clipboard.setText(AnnotatedString(room.room)) }
                saved?.let { password ->
                    CopyRow("Operator password", password, "Copy the operator password", Modifier.testTag("copy_operator_password"), Modifier.testTag("operator_password")) {
                        clipboard.setText(AnnotatedString(password))
                    }
                }
            }
        }
        if (!room.isOperator) {
            fun identify() { if (password.isNotBlank()) { container.sync.identifyAsOperator(password); password = "" } }
            OutlinedTextField(
                password, { password = it.uppercase().take(20) },
                label = { Text("Operator password") },
                placeholder = { Text("AB-123-456") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { identify() }),
                trailingIcon = { IconButton(::identify, Modifier.testTag("identify_operator")) { Icon(Icons.Filled.Key, "Identify as operator") } },
                modifier = Modifier.fillMaxWidth().testTag("operator_password_field"),
            )
        }
    }
    fun create() { if (container.sync.createManagedRoom(newManaged)) newManaged = "" }
    OutlinedTextField(
        newManaged, { newManaged = it.take(ManagedRooms.MAX_BASE_NAME_LENGTH) },
        label = { Text("Create managed room") },
        supportingText = { Text("Only its operators control playback; you get a password to make others operators") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { create() }),
        trailingIcon = { IconButton(::create, Modifier.testTag("managed_create")) { Icon(Icons.Filled.Lock, "Create managed room") } },
        modifier = Modifier.fillMaxWidth().testTag("managed_create_field"),
    )
}

@Composable
fun ChatContent(container: AppContainer, modifier: Modifier = Modifier, maxHeight: androidx.compose.ui.unit.Dp = 420.dp) {
    val feed by container.sync.feed.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val showChat = settings.showRoomChat
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(feed.size) { if (feed.isNotEmpty()) listState.scrollToItem(feed.lastIndex) }
    fun send() {
        if (message.isNotBlank()) {
            container.sync.sendChat(message)
            message = ""
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { SheetTitle("Chat") }
            TextButton(
                { container.scope.launch { container.settingsStore.saveShowRoomChat(!showChat) } },
                Modifier.testTag("toggle_chat"),
            ) { Text(if (showChat) "Hide chat" else "Show chat") }
        }
        if (!showChat) Text(HIDDEN_CHAT_NOTE, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.heightIn(max = maxHeight), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(feed) { msg ->
                val from = msg.from
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(if (from != null && from != room.username) Modifier.clickable { menu = true } else Modifier) {
                        if (from != null) Text("$from: ", color = AppColors.Accent, fontWeight = FontWeight.Bold)
                        Text(
                            msg.text,
                            color = when {
                                msg.isError -> AppColors.Error
                                from == null -> AppColors.TextDim
                                else -> AppColors.Text
                            },
                        )
                    }
                    if (from != null) {
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Block $from (hide their messages)") }, { menu = false; container.sync.block(from) })
                        }
                    }
                }
            }
        }
        if (feed.isEmpty()) Text("No messages yet.", color = AppColors.TextDim)
        if (showChat) OutlinedTextField(
            message, { message = it.take(container.sync.maxChatLength) },
            label = { Text("Message") },
            singleLine = true,
            enabled = room.status == ConnectionStatus.CONNECTED,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = { IconButton(::send) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("chat_field"),
        )
    }
}

@Composable
fun TracksContent(container: AppContainer, type: TrackType, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    val tracks by container.player.tracks.collectAsStateWithLifecycle()
    val list = tracks.filter { it.type == type }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SheetTitle(if (type == TrackType.AUDIO) "Audio" else "Subtitles")
        if (type == TrackType.SUBTITLE) {
            TrackRow("Off", list.none { it.selected }) { container.player.selectTrack(type, null); onPicked() }
        }
        list.forEach { track ->
            TrackRow(track.label + if (track.isExternal) " (external)" else "", track.selected) {
                container.player.selectTrack(type, track.id); onPicked()
            }
        }
        if (list.isEmpty()) Text("No ${if (type == TrackType.AUDIO) "audio" else "subtitle"} tracks", color = AppColors.TextDim)
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = if (selected) AppColors.Accent else AppColors.TextDim)
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
