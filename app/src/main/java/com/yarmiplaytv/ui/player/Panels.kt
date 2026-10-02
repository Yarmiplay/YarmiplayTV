package com.yarmiplaytv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Dot
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.components.TvTile
import com.yarmiplaytv.ui.theme.AppColors

@Composable
private fun PanelHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PlaylistPanel(container: AppContainer, nav: Navigator, focus: FocusRequester) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        PanelHeader("Shared playlist", if (inRoom) "Room ${room.room} · everyone sees these changes" else null)
        if (!inRoom) {
            Text("Join a Syncplay room to use the shared playlist.", color = AppColors.TextDim)
            ActionButton("Browse", { nav.popTo(Screen.Home) }, Modifier.padding(top = 16.dp).focusRequester(focus), icon = Icons.Filled.Home)
            return@Column
        }
        val np = nowPlaying
        val canAddCurrent = np != null && room.playlist.none { Filenames.same(it, np.fileName) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            ActionButton("Add from Jellyfin", { nav.popTo(Screen.Home) }, if (room.playlist.isEmpty()) Modifier.focusRequester(focus) else Modifier, icon = Icons.Filled.Home)
            if (canAddCurrent && np != null) {
                ActionButton("Add current", { container.sync.client?.addToPlaylist(np.fileName) }, icon = Icons.Filled.Add)
            }
        }
        if (room.playlist.isEmpty()) {
            Text("The playlist is empty.", color = AppColors.TextDim)
            return@Column
        }
        val focusIndex = (room.playlistIndex ?: 0).coerceIn(0, room.playlist.lastIndex)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(room.playlist) { index, file ->
                val current = index == room.playlistIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvTile(
                        onClick = { container.playlist.selectIndex(index) },
                        modifier = Modifier.weight(1f).then(if (index == focusIndex) Modifier.focusRequester(focus) else Modifier),
                        container = if (current) AppColors.Accent.copy(alpha = 0.2f) else AppColors.SurfaceHigh,
                        focusedScale = 1.02f,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (current) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = AppColors.Accent)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Text("${index + 1}.", color = AppColors.TextDim, modifier = Modifier.width(32.dp))
                            }
                            Text(file, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    IconAction(Icons.Filled.ArrowUpward, "Move up", { container.playlist.move(index, index - 1) }, enabled = index > 0)
                    IconAction(Icons.Filled.ArrowDownward, "Move down", { container.playlist.move(index, index + 1) }, enabled = index < room.playlist.lastIndex)
                    IconAction(Icons.Filled.Delete, "Remove", { container.playlist.removeIndex(index) }, tint = AppColors.Error)
                }
            }
        }
    }
}

@Composable
internal fun RoomPanel(container: AppContainer, nav: Navigator, focus: FocusRequester) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    var newRoom by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (room.status != ConnectionStatus.CONNECTED) {
            PanelHeader("Room")
            Text(if (room.status == ConnectionStatus.DISCONNECTED) "Not connected to Syncplay." else "Connecting…", color = AppColors.TextDim)
            ActionButton("Join a room", { nav.push(Screen.SyncplayConnect) }, Modifier.focusRequester(focus))
            return@Column
        }
        PanelHeader(room.room, "${container.sync.client?.config?.host ?: ""} · Syncplay ${room.serverVersion ?: ""}")
        val me = room.users.firstOrNull { it.name == room.username }
        val ready = room.isReady == true
        ActionButton(
            if (ready) "I'm ready" else "I'm not ready",
            { container.sync.toggleReady() },
            Modifier.focusRequester(focus),
            icon = if (ready) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            tint = if (ready) AppColors.Ready else AppColors.NotReady,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(room.users, key = { it.name }) { user ->
                val isMe = user.name == room.username
                val mismatch = !isMe && user.file != null && me?.file != null && !sameFile(user.file, me.file)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        when (user.isReady) {
                            true -> AppColors.Ready
                            false -> AppColors.NotReady
                            null -> AppColors.TextDim
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user.name + (if (isMe) " (you)" else "") + (if (user.isController) " ★" else ""),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            user.file?.let { "${it.name} (${formatClock(it.duration)})" } ?: "No file open",
                            color = if (mismatch) AppColors.NotReady else AppColors.TextDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (mismatch) Text("Different file than yours", color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(newRoom, { newRoom = it.take(60) }, "Switch room", Modifier.weight(1f), placeholder = room.room, onSubmit = {
                if (newRoom.isNotBlank()) container.sync.changeRoom(newRoom)
            })
            IconAction(Icons.Filled.MeetingRoom, "Change room", { if (newRoom.isNotBlank()) container.sync.changeRoom(newRoom) })
        }
    }
}

@Composable
internal fun ChatPanel(container: AppContainer, focus: FocusRequester) {
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
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        PanelHeader("Chat")
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(feed) { msg ->
                Row {
                    if (msg.from != null) Text("${msg.from}: ", color = AppColors.Accent, fontWeight = FontWeight.Bold)
                    Text(msg.text, color = when {
                        msg.isError -> AppColors.Error
                        msg.from == null -> AppColors.TextDim
                        else -> AppColors.Text
                    })
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(message, { message = it.take(500) }, "Message", Modifier.weight(1f).focusRequester(focus), onSubmit = ::send)
            IconAction(Icons.AutoMirrored.Filled.Send, "Send", ::send)
        }
    }
}

@Composable
internal fun TracksPanel(container: AppContainer, type: TrackType, focus: FocusRequester) {
    val tracks by container.player.tracks.collectAsStateWithLifecycle()
    val list = tracks.filter { it.type == type }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        PanelHeader(if (type == TrackType.AUDIO) "Audio" else "Subtitles")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == TrackType.SUBTITLE) {
                item {
                    TrackRow("Off", list.none { it.selected }, { container.player.selectTrack(type, null) }, if (list.none { it.selected }) Modifier.focusRequester(focus) else Modifier)
                }
            }
            items(list, key = { it.id }) { track ->
                TrackRow(
                    track.label + if (track.isExternal) " (external)" else "",
                    track.selected,
                    { container.player.selectTrack(type, track.id) },
                    if (track.selected) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
        if (list.isEmpty()) Text("No ${if (type == TrackType.AUDIO) "audio" else "subtitle"} tracks", color = AppColors.TextDim)
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvTile(onClick = onClick, modifier = modifier.fillMaxWidth(), container = if (selected) AppColors.Accent.copy(alpha = 0.2f) else AppColors.SurfaceHigh, focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = if (selected) AppColors.Accent else AppColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
