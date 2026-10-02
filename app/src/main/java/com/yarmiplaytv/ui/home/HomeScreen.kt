package com.yarmiplaytv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.browse.openItem
import com.yarmiplaytv.ui.components.Dot
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.components.Pill
import com.yarmiplaytv.ui.components.PosterCard
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.TvTile
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.UpdateState

@Composable
fun HomeScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()
    var libraries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(source) {
        val s = source ?: run { libraries = emptyList(); recent = emptyList(); return@LaunchedEffect }
        loadError = null
        runCatching { libraries = s.libraries() }.onFailure { loadError = it.message }
        runCatching { recent = s.recent() }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(com.yarmiplaytv.shared.R.drawable.ic_logo), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Text("YarmiplayTV", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                if (source != null) IconAction(Icons.Filled.Search, "Search", { nav.push(Screen.Search()) })
                Spacer(Modifier.width(8.dp))
                IconAction(Icons.Filled.Settings, "Settings", { nav.push(Screen.Settings) })
            }
        }
        update?.let { u ->
            item(key = "update") {
                UpdateNotice(u, {
                    container.updates.dismiss()
                    runCatching { firstFocus.requestFocus() }
                }, Modifier.padding(horizontal = 48.dp))
            }
        }
        item(key = "tiles") {
            // Tiles share the row width so they always fit inside the TV-safe area.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val (title, detail, color) = when (room.status) {
                    ConnectionStatus.CONNECTED -> Triple(
                        "Room: ${room.room}",
                        "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                        AppColors.Ready,
                    )
                    ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Triple("Connecting…", container.sync.client?.config?.host ?: "", AppColors.NotReady)
                    ConnectionStatus.DISCONNECTED -> Triple("Join a Syncplay room", "Watch in sync with friends", AppColors.TextDim)
                }
                StatusTile(Icons.Filled.Groups, title, detail, color, Modifier.weight(1f).focusRequester(firstFocus)) { nav.push(Screen.SyncplayConnect) }
                nowPlaying?.let { np ->
                    StatusTile(Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent, Modifier.weight(1f)) { nav.push(Screen.Player) }
                }
                val s = source
                if (s != null) {
                    StatusTile(Icons.Filled.Cloud, "Jellyfin: ${s.displayName}", "Signed in", AppColors.Ready, Modifier.weight(1f)) { nav.push(Screen.JellyfinLogin) }
                } else {
                    StatusTile(Icons.Filled.Cloud, "Connect Jellyfin", "Browse your media server", AppColors.TextDim, Modifier.weight(1f)) { nav.push(Screen.JellyfinLogin) }
                }
            }
        }
        if (loadError != null) {
            item(key = "load_error") { Text("Couldn't load libraries: $loadError", color = AppColors.Error, modifier = Modifier.padding(horizontal = 48.dp)) }
        }
        if (libraries.isNotEmpty()) {
            item(key = "libraries") {
                Column {
                    SectionTitle("Libraries", Modifier.padding(horizontal = 48.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
                        items(libraries, key = { it.id }) { lib ->
                            PosterCard(
                                title = lib.name,
                                subtitle = null,
                                imageUrl = source?.imageUrl(lib, 480),
                                aspectRatio = 16f / 9f,
                                placeholderIcon = when (lib.collectionType) {
                                    "tvshows" -> Icons.Filled.Tv
                                    "movies" -> Icons.Filled.Movie
                                    else -> Icons.Filled.Folder
                                },
                                onClick = { nav.push(Screen.Browse(lib)) },
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Column {
                    SectionTitle("Continue watching & recently added", Modifier.padding(horizontal = 48.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
                        items(recent, key = { it.id }) { item ->
                            PosterCard(
                                title = item.seriesName ?: item.name,
                                subtitle = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.year?.toString(),
                                imageUrl = source?.imageUrl(item, 480),
                                aspectRatio = 16f / 9f,
                                placeholderIcon = Icons.Filled.Movie,
                                onClick = { openItem(container, nav, item) },
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * TVs rarely have a browser, so this says how to get the update with Downloader, like the download page.
 * One full-width tile, so the D-pad reaches it from the tiles below; OK dismisses it.
 */
@Composable
private fun UpdateNotice(state: UpdateState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shortLink = state.update.pageUrl.substringAfter("://").trimEnd('/') + "/a"
    TvTile(onClick = onDismiss, modifier = modifier.fillMaxWidth().testTag("update_banner"), focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("YarmiplayTV ${state.update.version} is available", style = MaterialTheme.typography.titleMedium)
                Text("To install it, open Downloader and enter $shortLink", style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
            }
            Spacer(Modifier.width(16.dp))
            Pill("Not now", AppColors.TextDim)
        }
    }
}

@Composable
private fun StatusTile(icon: ImageVector, title: String, detail: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvTile(onClick = onClick, modifier = modifier.height(120.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Dot(color)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
