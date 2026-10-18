package com.yarmiplaytv.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Pill
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.player.ChatPanel
import com.yarmiplaytv.ui.player.PlaylistPanel
import com.yarmiplaytv.ui.player.RoomPanel
import com.yarmiplaytv.ui.theme.AppColors

/**
 * TV Room screen allowing the user to view room members, toggle ready/not-ready,
 * switch rooms, manage shared playlists, and chat—all without needing the video player.
 */
@Composable
fun RoomScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val connected = room.status == ConnectionStatus.CONNECTED
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { tabFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with room info and action buttons (Player & Connection)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (connected) "Room: ${room.room}" else "Syncplay room",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    when (room.status) {
                        ConnectionStatus.CONNECTED -> Pill("Connected" + if (room.tls) " · TLS" else "", AppColors.Ready)
                        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Pill("Connecting…", AppColors.NotReady)
                        ConnectionStatus.DISCONNECTED -> Pill("Disconnected", AppColors.TextDim)
                    }
                }
                if (connected) {
                    val host = container.sync.client?.config?.host ?: ""
                    Text(
                        "$host · Syncplay ${room.serverVersion ?: ""}",
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ActionButton(
                "Player",
                { nav.push(Screen.Player) },
                icon = Icons.Filled.PlayCircle,
            )
            ActionButton(
                "Connection",
                { nav.push(Screen.SyncplayConnect) },
                icon = Icons.Filled.Link,
            )
        }

        if (!connected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Not connected to a Syncplay room", style = MaterialTheme.typography.titleMedium, color = AppColors.TextDim)
                    ActionButton("Join a room", { nav.push(Screen.SyncplayConnect) }, Modifier.focusRequester(tabFocus), primary = true)
                }
            }
            return@Column
        }

        // Tabs to switch between Room (users & ready check), Shared Playlist, and Chat
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                "Users (${room.users.size})",
                { selectedTab = 0 },
                modifier = Modifier.focusRequester(tabFocus),
                icon = Icons.Filled.Groups,
                primary = selectedTab == 0,
            )
            ActionButton(
                "Playlist (${room.playlist.size})",
                { selectedTab = 1 },
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                primary = selectedTab == 1,
            )
            ActionButton(
                "Chat",
                { selectedTab = 2 },
                icon = Icons.Filled.Forum,
                primary = selectedTab == 2,
            )
        }

        // Active panel content
        Box(Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> RoomPanel(container, nav, contentFocus)
                1 -> PlaylistPanel(container, nav, contentFocus)
                2 -> ChatPanel(container, contentFocus)
            }
        }
    }
}
