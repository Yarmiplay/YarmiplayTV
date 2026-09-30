package com.syncplaytv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.syncplaytv.AppContainer
import com.syncplaytv.media.FileNames
import com.syncplaytv.media.MediaItem
import com.syncplaytv.media.MediaItemType
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.ui.Navigator
import com.syncplaytv.ui.Screen
import com.syncplaytv.ui.components.ActionButton
import com.syncplaytv.ui.components.EmptyState
import com.syncplaytv.ui.components.PosterCard
import com.syncplaytv.ui.components.TvTextField
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay

fun openItem(container: AppContainer, nav: Navigator, item: MediaItem) {
    if (item.isPlayable) nav.actionItem = item else nav.push(Screen.Browse(item))
}

private fun aspectFor(items: List<MediaItem>): Float {
    val posters = items.count { it.type in setOf(MediaItemType.SERIES, MediaItemType.MOVIE, MediaItemType.SEASON) }
    return if (posters > items.size / 2) 2f / 3f else 16f / 9f
}

private fun subtitleFor(item: MediaItem): String? = when (item.type) {
    MediaItemType.EPISODE -> listOfNotNull(item.seriesName, item.durationSeconds?.let { "${(it / 60).toInt()} min" }).joinToString(" · ")
    MediaItemType.MOVIE -> item.year?.toString()
    MediaItemType.SERIES -> item.year?.toString()
    MediaItemType.VIDEO -> item.fileName
    else -> null
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ItemGrid(
    container: AppContainer,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val aspect = aspectFor(items)
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (aspect < 1f) 170.dp else 240.dp),
        state = gridState,
        modifier = modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val index = items.indexOf(item)
            PosterCard(
                title = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.name,
                subtitle = subtitleFor(item),
                imageUrl = source?.imageUrl(item, if (aspect < 1f) 300 else 480),
                aspectRatio = aspect,
                placeholderIcon = when (item.type) {
                    MediaItemType.SERIES, MediaItemType.SEASON, MediaItemType.EPISODE -> Icons.Filled.Tv
                    MediaItemType.MOVIE, MediaItemType.VIDEO -> Icons.Filled.Movie
                    else -> Icons.Filled.Folder
                },
                badge = if (item.played) "Watched" else null,
                onClick = { onClick(item) },
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
fun BrowseScreen(container: AppContainer, nav: Navigator, parent: MediaItem) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(parent.id, source) {
        val s = source ?: return@LaunchedEffect
        runCatching { s.children(parent) }
            .onSuccess { items = it }
            .onFailure { error = it.message }
    }
    LaunchedEffect(items) { if (!items.isNullOrEmpty()) runCatching { delay(50); firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 48.dp, top = 32.dp, end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                parent.seriesName?.takeIf { parent.type == MediaItemType.SEASON }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.TextDim)
                }
                Text(parent.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ActionButton("Search", { nav.push(Screen.Search()) }, icon = Icons.Filled.Search)
        }
        parent.overview?.takeIf { parent.type == MediaItemType.SERIES && it.isNotBlank() }?.let {
            Text(it, color = AppColors.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp).width(1100.dp))
        }
        when {
            error != null -> EmptyState("Couldn't load: $error")
            items == null -> EmptyState("Loading…")
            items!!.isEmpty() -> EmptyState("Nothing here")
            else -> ItemGrid(container, items!!, { openItem(container, nav, it) }, Modifier.fillMaxSize(), firstFocus)
        }
    }
}

@Composable
fun SearchScreen(container: AppContainer, nav: Navigator, initialQuery: String, pickFor: String?) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery.ifEmpty { pickFor?.let { FileNames.parse(it).title } ?: "" }) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query, source) {
        val s = source ?: return@LaunchedEffect
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        results = runCatching { s.search(query) }.getOrDefault(emptyList())
        searching = false
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            if (pickFor != null) {
                Text("Pick the item to play for", color = AppColors.TextDim)
                Text(pickFor, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Search", style = MaterialTheme.typography.headlineMedium)
            }
            TvTextField(query, { query = it }, "Title", Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(fieldFocus), placeholder = "Type to search")
        }
        when {
            source == null -> EmptyState("Connect Jellyfin first")
            searching && results.isEmpty() -> EmptyState("Searching…")
            query.length >= 2 && results.isEmpty() -> EmptyState("No results")
            else -> ItemGrid(container, results, { item ->
                when {
                    pickFor != null && item.isPlayable -> {
                        container.playlist.resolveManually(item)
                        nav.back()
                    }
                    else -> openItem(container, nav, item)
                }
            }, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun ItemActionsDialog(container: AppContainer, item: MediaItem, onDismiss: () -> Unit) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { delay(50); firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .width(720.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.Surface)
                .padding(32.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item.seriesName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.titleMedium) }
                Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.fileName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (inRoom) {
                        ActionButton("Play for everyone in '${room.room}'", {
                            container.playlist.playInRoom(item); onDismiss()
                        }, Modifier.fillMaxWidth().focusRequester(firstFocus), icon = Icons.Filled.Groups, primary = true)
                        ActionButton("Add to room playlist", {
                            container.playlist.addToRoomPlaylist(item); onDismiss()
                        }, Modifier.fillMaxWidth(), icon = Icons.Filled.PlaylistAdd)
                        ActionButton("Play only on this TV", {
                            container.playlist.playHere(item); onDismiss()
                        }, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
                    } else {
                        ActionButton("Play", {
                            container.playlist.playHere(item); onDismiss()
                        }, Modifier.fillMaxWidth().focusRequester(firstFocus), icon = Icons.Filled.PlayArrow, primary = true)
                        Text("Join a Syncplay room to watch together.", color = AppColors.TextDim)
                    }
                }
            }
        }
    }
}
