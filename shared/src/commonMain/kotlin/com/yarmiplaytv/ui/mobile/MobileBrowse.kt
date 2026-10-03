package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.browse.aspectFor
import com.yarmiplaytv.ui.browse.openItem
import com.yarmiplaytv.ui.browse.serverNameOf
import com.yarmiplaytv.ui.browse.subtitleFor
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileTopBar(title: String, nav: Navigator, subtitle: String? = null, showBack: Boolean = nav.canGoBack, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = AppColors.TextDim, maxLines = 1)
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (showBack) IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Background),
        // MobileScaffold already pads its content below the status bar.
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun MobileItemGrid(container: AppContainer, items: List<MediaItem>, kind: DeviceKind, showServer: Boolean = false, onClick: (MediaItem) -> Unit) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val aspect = aspectFor(items)
    val min = when {
        aspect < 1f && kind.isLarge -> 150.dp
        aspect < 1f -> 110.dp
        kind.isLarge -> 240.dp
        else -> 170.dp
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(min),
        modifier = Modifier.fillMaxSize().testTag("grid"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.uniqueKey }) { item ->
            PosterTile(
                title = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.name,
                subtitle = subtitleFor(item, if (showServer) serverNameOf(source, item) else null),
                imageUrl = source?.imageUrl(item, if (aspect < 1f) 300 else 480),
                aspectRatio = aspect,
                placeholder = when (item.type) {
                    MediaItemType.SERIES, MediaItemType.SEASON, MediaItemType.EPISODE -> Icons.Filled.Tv
                    MediaItemType.MOVIE, MediaItemType.VIDEO -> Icons.Filled.Movie
                    else -> Icons.Filled.Folder
                },
                badge = if (item.played) "Watched" else null,
                onClick = { onClick(item) },
            )
        }
    }
}

@Composable
fun MobileBrowseScreen(container: AppContainer, nav: Navigator, parent: MediaItem, kind: DeviceKind) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(parent.id, source) {
        val s = source ?: return@LaunchedEffect
        runCatching { s.children(parent) }.onSuccess { items = it }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar(parent.name, nav, subtitle = parent.seriesName?.takeIf { parent.type == MediaItemType.SEASON }) {
            IconButton(onClick = { nav.push(Screen.Search()) }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
        }
        when {
            error != null -> EmptyMessage("Couldn't load: $error")
            items == null -> EmptyMessage("Loading…")
            items!!.isEmpty() -> EmptyMessage("Nothing here")
            else -> MobileItemGrid(container, items!!, kind) { openItem(container, nav, it) }
        }
    }
}

@Composable
fun MobileSearchScreen(container: AppContainer, nav: Navigator, initialQuery: String, pickFor: String?, kind: DeviceKind) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery.ifEmpty { pickFor?.let { FileNames.parse(it).title } ?: "" }) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, source) {
        val s = source ?: return@LaunchedEffect
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        results = runCatching { s.search(query) }.getOrDefault(emptyList())
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar(if (pickFor != null) "Pick the item to play" else "Search", nav, subtitle = pickFor, showBack = pickFor != null)
        OutlinedTextField(
            query, { query = it },
            label = { Text("Title") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search_field"),
        )
        when {
            source == null -> EmptyMessage("Connect Jellyfin or Plex first")
            searching && results.isEmpty() -> EmptyMessage("Searching…")
            query.length >= 2 && results.isEmpty() -> EmptyMessage("No results")
            else -> MobileItemGrid(container, results, kind, showServer = true) { item ->
                if (pickFor != null && item.isPlayable) {
                    container.playlist.resolveManually(item)
                    nav.back()
                } else {
                    openItem(container, nav, item)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileItemActionsSheet(container: AppContainer, item: MediaItem, onDismiss: () -> Unit) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = AppColors.Surface, modifier = Modifier.exposeTestTags()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.seriesName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.titleSmall) }
            Text(item.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.fileName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            if (inRoom) {
                Button({ container.playlist.playInRoom(item); onDismiss() }, Modifier.fillMaxWidth().testTag("action_play_room")) {
                    Icon(Icons.Filled.Groups, contentDescription = null); Text("  Play for everyone in '${room.room}'")
                }
                FilledTonalButton({ container.playlist.addToRoomPlaylist(item); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null); Text("  Add to room playlist")
                }
                FilledTonalButton({ container.playlist.playHere(item); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null); Text("  Play only on this device")
                }
            } else {
                Button({ container.playlist.playHere(item); onDismiss() }, Modifier.fillMaxWidth().testTag("action_play")) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null); Text("  Play")
                }
                Text("Join a Syncplay room to watch together.", color = AppColors.TextDim)
            }
        }
    }
}

@Composable
internal fun Spaced(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
