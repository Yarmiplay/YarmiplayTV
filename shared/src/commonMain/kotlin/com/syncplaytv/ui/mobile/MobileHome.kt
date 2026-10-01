package com.syncplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncplaytv.AppContainer
import com.syncplaytv.DeviceKind
import com.syncplaytv.media.MediaItem
import com.syncplaytv.media.MediaItemType
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.ui.browse.openItem
import com.syncplaytv.ui.nav.Navigator
import com.syncplaytv.ui.nav.Screen
import com.syncplaytv.ui.theme.AppColors

@Composable
fun MobileHomeScreen(container: AppContainer, nav: Navigator, kind: DeviceKind) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val localFiles by container.local.files.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    var libraries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        val s = source ?: run { libraries = emptyList(); recent = emptyList(); return@LaunchedEffect }
        loadError = null
        runCatching { libraries = s.libraries() }.onFailure { loadError = it.message }
        runCatching { recent = s.recent() }
    }

    val cardWidth = if (kind == DeviceKind.TABLET) 260.dp else 200.dp
    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(appLogoPainter(), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text("SyncplayTV", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            CardGrid(multiColumn = kind != DeviceKind.PHONE, modifier = Modifier.padding(horizontal = 16.dp)) {
                val (title, detail, color) = when (room.status) {
                    ConnectionStatus.CONNECTED -> Triple(
                        "Room: ${room.room}",
                        "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                        AppColors.Ready,
                    )
                    ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Triple("Connecting…", container.sync.client?.config?.host ?: "", AppColors.NotReady)
                    ConnectionStatus.DISCONNECTED -> Triple("Join a Syncplay room", "Watch in sync with friends", AppColors.TextDim)
                }
                StatusCard(Icons.Filled.Groups, title, detail, color, { nav.switchTab(Screen.SyncplayConnect) }, Modifier.fillMaxWidth().testTag("card_room"))
                nowPlaying?.let { np ->
                    StatusCard(Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent, { nav.push(Screen.Player) }, Modifier.fillMaxWidth())
                }
                val s = source
                StatusCard(
                    Icons.Filled.Cloud,
                    if (s != null) "Jellyfin: ${s.displayName}" else "Connect Jellyfin",
                    if (s != null) "Signed in" else "Browse your media server",
                    if (s != null) AppColors.Ready else AppColors.TextDim,
                    { nav.push(Screen.JellyfinLogin) },
                    Modifier.fillMaxWidth().testTag("card_jellyfin"),
                )
                StatusCard(
                    Icons.Filled.SdStorage,
                    "Files on this device",
                    when {
                        folders.isEmpty() -> "Open a video or add media folders"
                        else -> "${localFiles.size} videos in ${folders.size} folder${if (folders.size == 1) "" else "s"}"
                    },
                    if (folders.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                    { nav.push(Screen.LocalFiles) },
                    Modifier.fillMaxWidth().testTag("card_local"),
                )
            }
        }
        loadError?.let { err ->
            item { Text("Couldn't load libraries: $err", color = AppColors.Error, modifier = Modifier.padding(16.dp)) }
        }
        if (libraries.isNotEmpty()) {
            item {
                SectionHeader("Libraries", Modifier.padding(top = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(libraries, key = { it.id }) { lib ->
                        PosterTile(
                            title = lib.name,
                            subtitle = null,
                            imageUrl = source?.imageUrl(lib, 480),
                            aspectRatio = 16f / 9f,
                            placeholder = when (lib.collectionType) {
                                "tvshows" -> Icons.Filled.Tv
                                "movies" -> Icons.Filled.Movie
                                else -> Icons.Filled.Folder
                            },
                            onClick = { nav.push(Screen.Browse(lib)) },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                SectionHeader("Continue watching & recently added", Modifier.padding(top = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { item ->
                        PosterTile(
                            title = item.seriesName ?: item.name,
                            subtitle = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.year?.toString(),
                            imageUrl = source?.imageUrl(item, 480),
                            aspectRatio = 16f / 9f,
                            placeholder = Icons.Filled.Movie,
                            onClick = { openItem(container, nav, item) },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
    }
}

private val MinCardWidth = 280.dp
private val CardGap = 10.dp

/** One column on phones; on wider screens as many equal columns of at least [MinCardWidth] as fit. */
@Composable
private fun CardGrid(multiColumn: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = CardGap.roundToPx()
        val width = constraints.maxWidth
        val columns = if (multiColumn) ((width + gap) / (MinCardWidth.roundToPx() + gap)).coerceIn(1, measurables.size.coerceAtLeast(1)) else 1
        val cellWidth = (width - gap * (columns - 1)) / columns
        val rows = measurables.map { it.measure(Constraints.fixedWidth(cellWidth)) }.chunked(columns)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { i, row ->
                row.forEachIndexed { j, placeable -> placeable.placeRelative(j * (cellWidth + gap), y) }
                y += rowHeights[i] + gap
            }
        }
    }
}
