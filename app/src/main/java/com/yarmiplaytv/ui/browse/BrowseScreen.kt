package com.yarmiplaytv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.EmptyState
import com.yarmiplaytv.ui.components.PosterCard
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ItemGrid(
    container: AppContainer,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    showServer: Boolean = false,
) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val aspect = aspectFor(items)
    // Keep the next and previous row composed. A row that is not on screen yet is brought in
    // one cell at a time, and that cell is column 0, so Down would land there.
    val gridState = rememberLazyGridState(cacheWindow = LazyLayoutCacheWindow(ahead = 800.dp, behind = 400.dp))
    val scope = rememberCoroutineScope()
    val focus = remember(items) { ShowGridFocus(items.size) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (aspect < 1f) 170.dp else 240.dp),
        state = gridState,
        modifier = modifier.focusRestorer().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val rowsDelta = when (event.key) {
                Key.DirectionDown -> 1
                Key.DirectionUp -> -1
                else -> return@onPreviewKeyEvent false
            }
            val columns = gridState.layoutInfo.maxSpan
            val from = if (focus.landing >= 0) focus.landing else focus.cursor
            val target = sameColumnTarget(from, columns, rowsDelta, items.size) ?: return@onPreviewKeyEvent false
            if (focus.request(target)) return@onPreviewKeyEvent true
            focus.landing = target
            focus.job?.cancel()
            focus.job = scope.launch {
                val goal = target
                revealRow(gridState, from, goal, columns, rowsDelta)
                repeat(4) {
                    // A Left or Right while this row was scrolling in wins over the queued Down.
                    if (!isActive || focus.landing != goal) return@launch
                    if (focus.request(goal)) return@launch
                    delay(16)
                }
            }
            true
        },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.uniqueKey }) { index, item ->
            PosterCard(
                title = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.name,
                subtitle = subtitleFor(item, if (showServer) serverNameOf(source, item) else null),
                imageUrl = source?.imageUrl(item, if (aspect < 1f) 300 else 480),
                aspectRatio = aspect,
                placeholderIcon = when (item.type) {
                    MediaItemType.SERIES, MediaItemType.SEASON, MediaItemType.EPISODE -> Icons.Filled.Tv
                    MediaItemType.MOVIE, MediaItemType.VIDEO -> Icons.Filled.Movie
                    else -> Icons.Filled.Folder
                },
                badge = if (item.played) "Watched" else null,
                onClick = { onClick(item) },
                modifier = Modifier
                    .focusRequester(focus.requesters[index])
                    .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                    .onFocusChanged { if (it.hasFocus) focus.arrived(index) },
            )
        }
    }
}

/** Focus bookkeeping for one show grid. Not state: moving focus should not rebuild the grid. */
private class ShowGridFocus(count: Int) {
    val requesters = List(count) { FocusRequester() }
    var cursor = 0
    var landing = -1
    var job: Job? = null

    fun request(index: Int): Boolean {
        val ok = runCatching { requesters[index].requestFocus() }.getOrDefault(false)
        if (ok) arrived(index)
        return ok
    }

    fun arrived(index: Int) {
        cursor = index
        landing = -1
    }
}

/**
 * Compose just enough of [target]'s row to focus it, without scrolling the current poster away.
 * One pixel of the row is enough: a vertical grid measures every column of a row together.
 */
private suspend fun revealRow(state: LazyGridState, from: Int, target: Int, columns: Int, rowsDelta: Int) {
    val info = state.layoutInfo
    val anchor = info.visibleItemsInfo.firstOrNull { it.index == from }
    val rowStart = target - target.mod(columns)
    if (anchor == null) {
        state.scrollToItem(rowStart)
        return
    }
    val spacing = info.mainAxisItemSpacing
    val delta = if (rowsDelta > 0) {
        val bottom = anchor.offset.y + anchor.size.height
        (bottom + spacing + 1) - info.viewportEndOffset
    } else {
        val top = anchor.offset.y
        info.viewportStartOffset - (top - spacing - 1)
    }
    if (delta > 0) state.scroll { scrollBy(if (rowsDelta > 0) delta.toFloat() else -delta.toFloat()) }
    else state.scrollToItem(rowStart)
}

@Composable
fun BrowseScreen(container: AppContainer, nav: Navigator, parent: MediaItem) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(parent.uniqueKey, source) {
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
            if (parent.type == MediaItemType.SEASON) {
                val episodes = items.orEmpty().filter { it.isPlayable }
                ActionButton(
                    "Add all to playlist",
                    { container.playlist.addToRoomPlaylist(episodes) },
                    enabled = episodes.isNotEmpty(),
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                )
                Spacer(Modifier.width(12.dp))
            }
            if (container.mal.client.configured && parent.malInfo() != null) {
                ActionButton("MyAnimeList", { nav.push(Screen.MalEntry(parent)) }, icon = Icons.Filled.Bookmarks)
                Spacer(Modifier.width(12.dp))
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
            source == null -> EmptyState("Connect Jellyfin or Plex first")
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
            }, Modifier.fillMaxSize(), showServer = true)
        }
    }
}

@Composable
fun ItemActionsDialog(container: AppContainer, item: MediaItem, onDismiss: () -> Unit, onMal: (() -> Unit)? = null) {
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
            SurfaceInk {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item.seriesName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.titleMedium) }
                Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.fileName?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (inRoom) {
                        ActionButton("Play for everyone in '${ManagedRooms.label(room.room)}'", {
                            container.playlist.playInRoom(item); onDismiss()
                        }, Modifier.fillMaxWidth().focusRequester(firstFocus), icon = Icons.Filled.Groups, primary = true)
                        ActionButton("Add to room playlist", {
                            container.playlist.addToRoomPlaylist(item); onDismiss()
                        }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.PlaylistAdd)
                        ActionButton("Play only on this TV", {
                            container.playlist.playHere(item); onDismiss()
                        }, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
                    } else {
                        ActionButton("Play", {
                            container.playlist.playHere(item); onDismiss()
                        }, Modifier.fillMaxWidth().focusRequester(firstFocus), icon = Icons.Filled.PlayArrow, primary = true)
                        Text("Join a Syncplay room to watch together.", color = AppColors.TextDim)
                    }
                    if (onMal != null && container.mal.client.configured && item.malInfo() != null) {
                        ActionButton("MyAnimeList…", { onDismiss(); onMal() }, Modifier.fillMaxWidth(), icon = Icons.Filled.Bookmarks)
                    }
                }
            }
            }
        }
    }
}
