package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalMatcher
import com.yarmiplaytv.media.ResolveResult
import com.yarmiplaytv.sync.PlaylistEdits
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.ui.player.sizeWarning
import com.yarmiplaytv.ui.theme.AppColors
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROW_MIN = 52.dp
private val THUMB_W = 72.dp
private val THUMB_H = 40.dp

internal enum class PlaylistSourceKind { LOCAL, JELLYFIN, PLEX, URL, UNKNOWN }

internal data class PlaylistRowMeta(
    val source: PlaylistSourceKind = PlaylistSourceKind.UNKNOWN,
    val durationSeconds: Double? = null,
    val thumb: Any? = null,
    val localFile: LocalFile? = null,
)

/** Filters playlist indices by a case-insensitive substring of the entry string. */
internal fun playlistFilterIndices(playlist: List<String>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return playlist.indices.toList()
    return playlist.indices.filter { playlist[it].contains(q, ignoreCase = true) }
}

internal fun formatPlaylistDuration(seconds: Double?): String? {
    if (seconds == null || seconds <= 0.0 || !seconds.isFinite()) return null
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun playlistSourceLabel(kind: PlaylistSourceKind): String = when (kind) {
    PlaylistSourceKind.LOCAL -> "Local"
    PlaylistSourceKind.JELLYFIN -> "Jellyfin"
    PlaylistSourceKind.PLEX -> "Plex"
    PlaylistSourceKind.URL -> "URL"
    PlaylistSourceKind.UNKNOWN -> ""
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopPlaylist(container: AppContainer, modifier: Modifier) {
    val room by container.sync.room.collectAsState()
    val settings by container.settings.collectAsState()
    val localFiles by container.local.files.collectAsState()
    val nowPlaying by container.playlist.nowPlaying.collectAsState()
    val canUndo by container.playlist.shared.canUndo.collectAsState()
    val mediaSource by container.mediaSource.collectAsState()
    val shared = container.playlist.shared
    val playlist = room.playlist
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var addingUrls by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var contextIndex by remember { mutableStateOf<Int?>(null) }
    val listFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(playlist) { selected = selected.filter { it in playlist.indices }.toSet() }
    val localIndex by produceState<LocalMatcher.Index?>(null, localFiles) {
        value = withContext(Dispatchers.Default) { LocalMatcher.Index(localFiles) }
    }
    val available = remember(playlist, localIndex) {
        localIndex?.let { index -> playlist.map { container.playlist.isAvailable(it, index) } }.orEmpty()
    }
    val filtered = remember(playlist, filter) { playlistFilterIndices(playlist, filter) }
    val filtering = filter.isNotBlank()
    val doubleClickMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val lastClick = remember { LastClick() }
    val options = settings.sync
    val peerDurations = remember(room.users, playlist) {
        buildMap {
            for (user in room.users) {
                val info = user.file ?: continue
                if (info.duration > 0) put(Filenames.baseName(info.name).lowercase(), info.duration)
            }
        }
    }

    fun saveOptions(sharedPlaylists: Boolean = options.sharedPlaylists, loop: Boolean = options.loopPlaylist, single: Boolean = options.loopSingleFile) {
        container.scope.launch { container.settingsStore.savePlaylistOptions(sharedPlaylists, loop, single) }
    }
    fun addPaths(paths: List<java.nio.file.Path>, at: Int? = null) =
        container.playlist.addLocalFilesToRoomPlaylist(paths.flatMap(DesktopDialogs::videosIn).map(FileLocalLibrary::uriOf), at)
    fun removeSelected() {
        if (selected.isEmpty()) return
        shared.remove(selected)
        selected = emptySet()
    }
    fun play(index: Int) = container.playlist.selectIndex(index)
    fun addCurrent() {
        val name = nowPlaying?.fileName ?: return
        shared.add(listOf(name))
    }

    var dropAt by remember { mutableStateOf<Int?>(null) }
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density by rememberUpdatedState(LocalDensity.current.density)
    val filteredNow by rememberUpdatedState(filtered)
    val filteringNow by rememberUpdatedState(filtering)
    val drop = remember(container) {
        object : DragAndDropTarget {
            fun indexAt(event: DragAndDropEvent): Int {
                val count = container.sync.room.value.playlist.size
                val coords = listCoords?.takeIf { it.isAttached } ?: return count
                val pointerY = when (val native = event.nativeEvent) {
                    is java.awt.dnd.DropTargetDragEvent -> native.location.y
                    is java.awt.dnd.DropTargetDropEvent -> native.location.y
                    else -> return count
                }
                val y = pointerY * density - coords.positionInRoot().y
                val visibleAt = playlistDropIndex(y, coords.size.height, listState.layoutInfo.visibleItemsInfo, filteredNow.size)
                if (!filteringNow) return visibleAt
                // Lazy indices are into the filtered view — map back to the room playlist.
                return if (visibleAt >= filteredNow.size) count else filteredNow[visibleAt]
            }
            override fun onEntered(event: DragAndDropEvent) { dropAt = indexAt(event) }
            override fun onMoved(event: DragAndDropEvent) { dropAt = indexAt(event) }
            override fun onExited(event: DragAndDropEvent) { dropAt = null }
            override fun onEnded(event: DragAndDropEvent) { dropAt = null }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val at = indexAt(event)
                dropAt = null
                val paths = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { runCatching { Paths.get(URI(it)) }.getOrNull() }
                if (paths.isEmpty()) return false
                addPaths(paths, at)
                return true
            }
        }
    }
    val connected = room.status == ConnectionStatus.CONNECTED
    val canAddCurrent = nowPlaying != null && playlist.none { Filenames.same(it, nowPlaying!!.fileName) }

    LaunchedEffect(room.playlistIndex, filtering) {
        val idx = room.playlistIndex ?: return@LaunchedEffect
        if (filtering && idx !in filtered) return@LaunchedEffect
        val visible = if (filtering) filtered.indexOf(idx) else idx
        if (visible >= 0) listState.animateScrollToItem(visible)
    }

    Column(
        modifier.fillMaxWidth().then(
            if (connected) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop)
            else Modifier
        ),
    ) {
        if (!connected) {
            Text("Join a Syncplay room to use the shared playlist.", color = AppColors.TextDim, modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (!options.sharedPlaylists) {
            Row(Modifier.fillMaxWidth().background(AppColors.NotReady.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Shared playlists are off: you won't follow the room's picks.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton({ saveOptions(sharedPlaylists = true) }) { Text("Turn on") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Filled.Add, "Add videos") { addPaths(DesktopDialogs.pickVideos()) }
            ToolButton(Icons.Filled.CreateNewFolder, "Add the videos in a folder") { DesktopDialogs.pickFolder("Add the videos in a folder")?.let { addPaths(listOf(it)) } }
            ToolButton(Icons.Filled.Link, "Add URLs") { addingUrls = !addingUrls }
            ToolButton(Icons.AutoMirrored.Filled.PlaylistAdd, "Add current", enabled = canAddCurrent) { addCurrent() }
            Box {
                var open by remember { mutableStateOf(false) }
                ToolButton(Icons.Filled.Shuffle, "Shuffle", enabled = playlist.size > 1) { open = true }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Shuffle what's left") }, { open = false; shared.shuffle(entire = false) })
                    DropdownMenuItem({ Text("Shuffle everything") }, { open = false; shared.shuffle(entire = true) })
                }
            }
            ToolButton(Icons.Filled.SortByAlpha, "Sort A to Z", enabled = playlist.size > 1) { shared.sortAlphabetically() }
            ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo (Ctrl+Z)", enabled = canUndo) { shared.undo() }
            if (selected.isNotEmpty()) {
                ToolButton(Icons.Filled.Delete, "Remove selected", enabled = true) { removeSelected() }
            }
            if (playlist.isNotEmpty()) {
                TooltipArea(tooltip = { Tooltip("Empty playlist") }) {
                    IconButton({ confirmEmpty = true }, Modifier.testTag("playlist_empty")) {
                        Icon(Icons.Filled.Delete, contentDescription = "Empty playlist", tint = AppColors.Error)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                var open by remember { mutableStateOf(false) }
                ToolButton(Icons.Filled.MoreVert, "Playlist options") { open = true }
                DropdownMenu(open, { open = false }) {
                    CheckItem("Loop at end of playlist", options.loopPlaylist) { saveOptions(loop = !options.loopPlaylist) }
                    CheckItem("Loop single file", options.loopSingleFile) { saveOptions(single = !options.loopSingleFile) }
                    CheckItem("Enable shared playlists", options.sharedPlaylists) { saveOptions(sharedPlaylists = !options.sharedPlaylists) }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Save playlist…") }, {
                        open = false
                        DesktopDialogs.savePlaylist("${room.room.replace(Regex("[\\\\/:*?\"<>|]"), "-")}.txt")?.let { path ->
                            runCatching { Files.writeString(path, PlaylistEdits.format(playlist)) }
                                .onSuccess { container.sync.postLocal("Saved the playlist to ${path.fileName}") }
                                .onFailure { container.sync.postLocal("Couldn't save the playlist: ${it.message}", isError = true) }
                        }
                    }, enabled = playlist.isNotEmpty())
                    DropdownMenuItem({ Text("Load playlist…") }, {
                        open = false
                        DesktopDialogs.openPlaylist()?.let { path ->
                            runCatching { PlaylistEdits.parse(Files.readString(path)) }
                                .onSuccess { shared.replace(it); container.sync.postLocal("Loaded ${it.size} entries from ${path.fileName}") }
                                .onFailure { container.sync.postLocal("Couldn't read the playlist: ${it.message}", isError = true) }
                        }
                    })
                }
            }
        }
        if (addingUrls) UrlField(onAdd = { text -> container.playlist.addUrlsToRoomPlaylist(text.split(Regex("\\s+"))) > 0 }, onClose = { addingUrls = false })
        if (confirmEmpty) {
            AlertDialog(
                onDismissRequest = { confirmEmpty = false },
                title = { Text("Empty the playlist?") },
                text = { Text("Remove every entry from this room's shared playlist. Everyone in the room sees the change. Undo brings the list back.") },
                confirmButton = {
                    TextButton({ confirmEmpty = false; shared.clear() }, Modifier.testTag("playlist_empty_confirm")) { Text("Empty playlist") }
                },
                dismissButton = { TextButton({ confirmEmpty = false }) { Text("Cancel") } },
            )
        }

        if (playlist.isNotEmpty()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(Icons.Filled.Close, "Clear filter") }
                },
                placeholder = { Text("Filter playlist (Ctrl+F)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(searchFocus)
                    .testTag("playlist_filter")
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { filter = ""; listFocus.requestFocus(); true }
                            else -> false
                        }
                    },
            )
        }

        if (playlist.isEmpty()) {
            Text(
                "The playlist is empty. Add videos, a folder or URLs, or drop videos or folders here.",
                color = AppColors.TextDim, modifier = Modifier.padding(16.dp),
            )
        } else if (filtered.isEmpty()) {
            Text("No entries match \"$filter\".", color = AppColors.TextDim, modifier = Modifier.padding(16.dp))
        }

        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val dropLine = AppColors.Accent
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("playlist_list")
                .onGloballyPositioned { listCoords = it }
                .background(if (dropAt != null) AppColors.Accent.copy(alpha = 0.06f) else Color.Transparent)
                .drawWithContent {
                    drawContent()
                    val at = dropAt ?: return@drawWithContent
                    val rows = listState.layoutInfo.visibleItemsInfo
                    val visibleAt = if (filtering) filtered.indexOf(at).takeIf { it >= 0 } ?: filtered.size else at
                    val y = rows.firstOrNull { it.index == visibleAt }?.offset?.toFloat()
                        ?: rows.lastOrNull()?.let { (it.offset + it.size).toFloat() } ?: 0f
                    val thickness = 2.dp.toPx()
                    val inset = 8.dp.toPx()
                    drawRect(
                        dropLine,
                        topLeft = Offset(inset, (y - thickness / 2).coerceIn(0f, size.height - thickness)),
                        size = Size(size.width - 2 * inset, thickness),
                    )
                }
                .focusRequester(listFocus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = event.isCtrlPressed || event.isMetaPressed
                    when {
                        ctrl && event.key == Key.F -> { searchFocus.requestFocus(); true }
                        event.key == Key.Delete || event.key == Key.Backspace -> { removeSelected(); true }
                        !filtering && event.isAltPressed && event.key == Key.DirectionUp -> {
                            selected = shared.shift(selected, -1); true
                        }
                        !filtering && event.isAltPressed && event.key == Key.DirectionDown -> {
                            selected = shared.shift(selected, 1); true
                        }
                        ctrl && event.key == Key.Z -> { shared.undo(); true }
                        ctrl && event.key == Key.A -> { selected = playlist.indices.toSet(); true }
                        event.key == Key.Enter -> { selected.minOrNull()?.let(::play); true }
                        event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                            if (filtered.isEmpty()) return@onPreviewKeyEvent true
                            val visibleSelected = selected.mapNotNull { filtered.indexOf(it).takeIf { i -> i >= 0 } }
                            val fromVis = anchor?.let { filtered.indexOf(it) }?.takeIf { it >= 0 }
                                ?: visibleSelected.minOrNull() ?: -1
                            val toVis = (fromVis + if (event.key == Key.DirectionUp) -1 else 1).coerceIn(0, filtered.lastIndex)
                            val to = filtered[toVis]
                            selected = if (event.isShiftPressed && anchor != null) {
                                val a = filtered.indexOf(anchor!!).coerceAtLeast(0)
                                filtered.slice(minOf(a, toVis)..maxOf(a, toVis)).toSet()
                            } else setOf(to).also { anchor = to }
                            container.scope.launch { listState.animateScrollToItem(toVis) }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            itemsIndexed(filtered, key = { _, index -> "$index:${playlist[index]}" }) { visible, index ->
                val file = playlist[index]
                val dragging = !filtering && dragFrom == index
                val meta = rememberPlaylistRowMeta(container, file, localIndex, peerDurations, mediaSource)
                Box {
                    PlaylistRow(
                        index = index,
                        file = file,
                        current = index == room.playlistIndex,
                        selected = index in selected,
                        available = available.getOrElse(index) { true },
                        sizeWarning = room.sizeWarning(file),
                        meta = meta,
                        modifier = Modifier
                            .zIndexSafe(dragging)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .onPointerEvent(PointerEventType.Press) { ev ->
                                if (ev.button == PointerButton.Secondary) {
                                    listFocus.requestFocus()
                                    if (index !in selected) {
                                        selected = setOf(index)
                                        anchor = index
                                    }
                                    contextIndex = index
                                }
                            }
                            .onClick(keyboardModifiers = { isCtrlPressed || isMetaPressed }) {
                                listFocus.requestFocus()
                                selected = if (index in selected) selected - index else selected + index
                                anchor = index
                            }
                            .onClick(keyboardModifiers = { isShiftPressed }) {
                                listFocus.requestFocus()
                                selected = rangeOf(anchor ?: index, index)
                            }
                            .onClick(keyboardModifiers = { !isCtrlPressed && !isMetaPressed && !isShiftPressed }) {
                                listFocus.requestFocus()
                                selected = setOf(index)
                                anchor = index
                                if (lastClick.isSecond(index, doubleClickMs)) play(index)
                            }
                            .then(
                                if (filtering) Modifier else Modifier.pointerInput(index, playlist.size) {
                                    detectDragGestures(
                                        onDragStart = { dragFrom = index; dragOffset = 0f },
                                        onDragEnd = {
                                            val to = playlistDragTarget(index, dragOffset, listState.layoutInfo.visibleItemsInfo, playlist.lastIndex)
                                            if (to != index) {
                                                shared.move(index, to)
                                                selected = setOf(to)
                                                anchor = to
                                            }
                                            dragFrom = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = { dragFrom = null; dragOffset = 0f },
                                        onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                                    )
                                },
                            ),
                    )
                    DropdownMenu(contextIndex == index, { contextIndex = null }) {
                        DropdownMenuItem({ Text("Play now") }, { contextIndex = null; play(index) })
                        DropdownMenuItem({ Text("Remove") }, {
                            contextIndex = null
                            shared.remove(setOf(index))
                            selected = emptySet()
                        })
                        if (!filtering) {
                            DropdownMenuItem(
                                { Text("Move up") },
                                { contextIndex = null; selected = shared.shift(setOf(index), -1) },
                                enabled = index > 0,
                            )
                            DropdownMenuItem(
                                { Text("Move down") },
                                { contextIndex = null; selected = shared.shift(setOf(index), 1) },
                                enabled = index < playlist.lastIndex,
                            )
                        }
                        DropdownMenuItem({ Text("Copy name") }, {
                            contextIndex = null
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(file), null)
                        })
                    }
                }
            }
        }
        when {
            dropAt != null -> Text(
                if (dropAt!! < playlist.size) "Release to insert the videos at the line" else "Release to add the videos at the end",
                color = AppColors.Accent, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("playlist_drop_hint"),
            )
            filtering -> Text(
                "${filtered.size} of ${playlist.size} · clear filter to reorder",
                color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            playlist.isNotEmpty() -> Text(
                buildString {
                    if (selected.isNotEmpty()) append("${selected.size} selected · ")
                    append("Double-click plays · Del removes · drag or Alt+↑↓ moves · right-click menu")
                },
                color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun rememberPlaylistRowMeta(
    container: AppContainer,
    file: String,
    localIndex: LocalMatcher.Index?,
    peerDurations: Map<String, Double>,
    mediaSource: com.yarmiplaytv.media.MediaSource?,
): PlaylistRowMeta {
    val thumbnails = container.thumbnails
    return produceState(PlaylistRowMeta(), file, localIndex, peerDurations, mediaSource, thumbnails) {
        if (Filenames.isUrl(file)) {
            value = PlaylistRowMeta(source = PlaylistSourceKind.URL, durationSeconds = peerDurations[Filenames.baseName(file).lowercase()])
            return@produceState
        }
        val local = localIndex?.find(file)
        if (local != null) {
            val thumb = runCatching { thumbnails?.thumbnail(local.file) }.getOrNull()
            value = PlaylistRowMeta(
                source = PlaylistSourceKind.LOCAL,
                durationSeconds = peerDurations[Filenames.baseName(file).lowercase()],
                thumb = thumb,
                localFile = local.file,
            )
            return@produceState
        }
        val peer = peerDurations[Filenames.baseName(file).lowercase()]
        val found = withContext(Dispatchers.IO) {
            runCatching { mediaSource?.findExact(file) }.getOrNull() as? ResolveResult.Found
        }
        if (found != null) {
            val keyKind = found.playable.sourceKey.substringBefore(':').lowercase()
            val kind = when {
                keyKind == "jellyfin" || found.item.id.startsWith("jellyfin", ignoreCase = true) -> PlaylistSourceKind.JELLYFIN
                keyKind == "plex" -> PlaylistSourceKind.PLEX
                mediaSource?.kind == "jellyfin" -> PlaylistSourceKind.JELLYFIN
                mediaSource?.kind == "plex" -> PlaylistSourceKind.PLEX
                keyKind.contains("jellyfin") -> PlaylistSourceKind.JELLYFIN
                keyKind.contains("plex") -> PlaylistSourceKind.PLEX
                else -> PlaylistSourceKind.UNKNOWN
            }
            value = PlaylistRowMeta(
                source = if (kind == PlaylistSourceKind.UNKNOWN && mediaSource?.kind == "all") {
                    when {
                        found.playable.sourceKey.contains("jellyfin", ignoreCase = true) -> PlaylistSourceKind.JELLYFIN
                        found.playable.sourceKey.contains("plex", ignoreCase = true) -> PlaylistSourceKind.PLEX
                        else -> kind
                    }
                } else kind,
                durationSeconds = found.playable.durationSeconds.takeIf { it > 0 } ?: peer,
                thumb = mediaSource?.imageUrl(found.item, maxWidth = 160),
            )
        } else {
            value = PlaylistRowMeta(source = PlaylistSourceKind.UNKNOWN, durationSeconds = peer)
        }
    }.value
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    index: Int,
    file: String,
    current: Boolean,
    selected: Boolean,
    available: Boolean,
    sizeWarning: String?,
    meta: PlaylistRowMeta,
    modifier: Modifier,
) {
    val accent = AppColors.Accent
    val background = when {
        selected -> accent.copy(alpha = 0.28f)
        current -> accent.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .drawWithContent {
                drawContent()
                if (current) {
                    drawRect(
                        accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("playlist_row_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(THUMB_W, THUMB_H).clip(RoundedCornerShape(4.dp)).background(AppColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, null, tint = AppColors.TextDim, modifier = Modifier.size(18.dp))
            when (val thumb = meta.thumb) {
                is File -> AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                is String -> AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (current) Icon(Icons.Filled.PlayArrow, "Playing", tint = AppColors.Accent, modifier = Modifier.size(18.dp))
            else Text("${index + 1}", color = AppColors.TextDim, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current) {
                    Text(
                        "Now",
                        color = AppColors.OnAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Accent)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .testTag("playlist_now_$index"),
                    )
                }
                Text(
                    Filenames.baseName(file),
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    fontStyle = if (available) FontStyle.Normal else FontStyle.Italic,
                    color = if (available) AppColors.Text else AppColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (file != Filenames.baseName(file) || meta.source != PlaylistSourceKind.UNKNOWN) {
                Text(
                    file,
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val source = playlistSourceLabel(meta.source)
        if (source.isNotEmpty()) {
            Text(
                source,
                color = AppColors.Accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("playlist_source_$index"),
            )
        }
        formatPlaylistDuration(meta.durationSeconds)?.let { dur ->
            Text(dur, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("playlist_duration_$index"))
        }
        if (sizeWarning != null) {
            TooltipArea(tooltip = { Tooltip(sizeWarning) }) {
                Icon(Icons.Filled.Warning, sizeWarning, tint = AppColors.NotReady, modifier = Modifier.size(18.dp).testTag("playlist_size_warning_$index"))
            }
        }
        if (!available) {
            TooltipArea(tooltip = { Tooltip("Not in your media folders; looked up on media servers when picked") }) {
                Icon(Icons.Filled.SearchOff, "Not found here", tint = AppColors.TextDim, modifier = Modifier.size(18.dp))
            }
        } else if (meta.source == PlaylistSourceKind.LOCAL) {
            Icon(Icons.Filled.Folder, "Local file", tint = AppColors.TextDim, modifier = Modifier.size(16.dp))
        }
    }
}

private fun Modifier.zIndexSafe(dragging: Boolean) =
    if (dragging) zIndex(1f) else this

private fun rangeOf(a: Int, b: Int): Set<Int> = (minOf(a, b)..maxOf(a, b)).toSet()

/** The last click on a playlist row, to tell a double click from two single ones. */
internal class LastClick(private val now: () -> Long = System::currentTimeMillis) {
    private var index = -1
    private var at = 0L

    fun isSecond(index: Int, withinMs: Long): Boolean {
        val time = now()
        val second = index == this.index && time - at <= withinMs
        this.index = if (second) -1 else index
        at = time
        return second
    }
}

internal fun playlistDragTarget(from: Int, offset: Float, rows: List<LazyListItemInfo>, lastIndex: Int): Int {
    val dragged = rows.firstOrNull { it.index == from } ?: return from
    val middle = dragged.offset + dragged.size / 2f + offset
    val over = rows.firstOrNull { middle < it.offset + it.size } ?: rows.last()
    return over.index.coerceIn(0, lastIndex)
}

internal fun playlistDropIndex(y: Float, height: Int, rows: List<LazyListItemInfo>, count: Int): Int {
    if (y < 0f || y > height) return count
    val row = rows.firstOrNull { y < it.offset + it.size / 2f } ?: return rows.lastOrNull()?.let { it.index + 1 } ?: count
    return row.index
}
