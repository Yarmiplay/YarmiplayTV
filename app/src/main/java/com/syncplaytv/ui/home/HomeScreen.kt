package com.syncplaytv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.syncplaytv.AppContainer
import com.syncplaytv.R
import com.syncplaytv.media.MediaItem
import com.syncplaytv.media.MediaItemType
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.ui.Navigator
import com.syncplaytv.ui.Screen
import com.syncplaytv.ui.browse.openItem
import com.syncplaytv.ui.components.Dot
import com.syncplaytv.ui.components.PosterCard
import com.syncplaytv.ui.components.SectionTitle
import com.syncplaytv.ui.components.TvTile
import com.syncplaytv.ui.theme.AppColors

@Composable
fun HomeScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
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
        item {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Text("SyncplayTV", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
                item {
                    val (title, detail, color) = when (room.status) {
                        ConnectionStatus.CONNECTED -> Triple(
                            "Room: ${room.room}",
                            "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                            AppColors.Ready,
                        )
                        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Triple("Connecting…", container.sync.client?.config?.host ?: "", AppColors.NotReady)
                        ConnectionStatus.DISCONNECTED -> Triple("Join a Syncplay room", "Watch in sync with friends", AppColors.TextDim)
                    }
                    StatusTile(Icons.Filled.Groups, title, detail, color, Modifier.focusRequester(firstFocus)) { nav.push(Screen.SyncplayConnect) }
                }
                nowPlaying?.let { np ->
                    item { StatusTile(Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent) { nav.push(Screen.Player) } }
                }
                item {
                    val s = source
                    if (s != null) {
                        StatusTile(Icons.Filled.Cloud, "Jellyfin: ${s.displayName}", "Signed in", AppColors.Ready) { nav.push(Screen.JellyfinLogin) }
                    } else {
                        StatusTile(Icons.Filled.Cloud, "Connect Jellyfin", "Browse your media server", AppColors.TextDim) { nav.push(Screen.JellyfinLogin) }
                    }
                }
                if (source != null) {
                    item { StatusTile(Icons.Filled.Search, "Search", "Find a movie or episode", AppColors.TextDim) { nav.push(Screen.Search()) } }
                }
                item { StatusTile(Icons.Filled.Settings, "Settings", "Sync and playback", AppColors.TextDim) { nav.push(Screen.Settings) } }
            }
        }
        if (loadError != null) {
            item { Text("Couldn't load libraries: $loadError", color = AppColors.Error, modifier = Modifier.padding(horizontal = 48.dp)) }
        }
        if (libraries.isNotEmpty()) {
            item {
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
            item {
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
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusTile(icon: ImageVector, title: String, detail: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvTile(onClick = onClick, modifier = modifier.width(300.dp).height(120.dp)) {
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
