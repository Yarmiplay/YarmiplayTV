package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.player.sameFile
import com.yarmiplaytv.ui.theme.AppColors

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistContent(container: AppContainer, onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SheetTitle("Shared playlist", if (room.status == ConnectionStatus.CONNECTED) "Everyone in '${room.room}' sees these changes" else null)
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
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 480.dp)) {
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
                    Text(file, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    IconButton({ container.playlist.move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                    IconButton({ container.playlist.move(index, index + 1) }, enabled = index < room.playlist.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    IconButton({ container.playlist.removeIndex(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = AppColors.Error) }
                }
            }
        }
    }
}

@Composable
fun RoomUsersContent(container: AppContainer, modifier: Modifier = Modifier, showChangeRoom: Boolean = true) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    var newRoom by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (room.status != ConnectionStatus.CONNECTED) {
            SheetTitle("Room")
            Text(if (room.status == ConnectionStatus.DISCONNECTED) "Not connected to Syncplay." else "Connecting…", color = AppColors.TextDim)
            return@Column
        }
        SheetTitle(room.room, "${container.sync.client?.config?.host ?: ""} · Syncplay ${room.serverVersion ?: ""}")
        ReadyChip(room.isReady == true, { container.sync.toggleReady() }, Modifier.testTag("ready_toggle"))
        val me = room.users.firstOrNull { it.name == room.username }
        room.users.forEach { user ->
            val isMe = user.name == room.username
            val mismatch = !isMe && user.file != null && me?.file != null && !sameFile(user.file, me.file)
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
                    Text(user.name + (if (isMe) " (you)" else "") + (if (user.isController) " ★" else ""), style = MaterialTheme.typography.titleSmall)
                    Text(
                        user.file?.let { "${it.name} (${formatClock(it.duration)})" } ?: "No file open",
                        color = if (mismatch) AppColors.NotReady else AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (mismatch) Text("Different file than yours", color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showChangeRoom) {
            fun change() { if (newRoom.isNotBlank()) { container.sync.changeRoom(newRoom.trim()); newRoom = "" } }
            OutlinedTextField(
                newRoom, { newRoom = it.take(60) },
                label = { Text("Switch room") },
                placeholder = { Text(room.room) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { change() }),
                trailingIcon = { IconButton(::change) { Icon(Icons.Filled.MeetingRoom, "Change room") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ChatContent(container: AppContainer, modifier: Modifier = Modifier, maxHeight: androidx.compose.ui.unit.Dp = 420.dp) {
    val feed by container.sync.feed.collectAsStateWithLifecycle()
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
        SheetTitle("Chat")
        LazyColumn(Modifier.heightIn(max = maxHeight), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(feed) { msg ->
                Row {
                    if (msg.from != null) Text("${msg.from}: ", color = AppColors.Accent, fontWeight = FontWeight.Bold)
                    Text(
                        msg.text,
                        color = when {
                            msg.isError -> AppColors.Error
                            msg.from == null -> AppColors.TextDim
                            else -> AppColors.Text
                        },
                    )
                }
            }
        }
        if (feed.isEmpty()) Text("No messages yet.", color = AppColors.TextDim)
        OutlinedTextField(
            message, { message = it.take(container.sync.maxChatLength) },
            label = { Text("Message") },
            singleLine = true,
            enabled = container.sync.room.value.status == ConnectionStatus.CONNECTED,
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
