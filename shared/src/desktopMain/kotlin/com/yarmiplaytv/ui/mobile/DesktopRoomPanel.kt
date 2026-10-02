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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.sync.PlaylistEdits
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The desktop side panel's state; the window's Enter shortcut calls [focusChat]. */
object DesktopRoomPanel {
    var open by mutableStateOf(false)
    var tab by mutableStateOf(RoomPanelTab.CHAT)
    /** Chat messages from others that arrived while the chat tab wasn't showing. */
    internal var unread by mutableIntStateOf(0)
    internal var chatFocusRequests by mutableIntStateOf(0)
    /** Sent messages, oldest first, for Up/Down in the chat box. */
    internal val sentHistory = mutableStateListOf<String>()

    val showsPlaylist: Boolean get() = open && tab == RoomPanelTab.PLAYLIST

    fun focusChat() {
        open = true
        tab = RoomPanelTab.CHAT
        chatFocusRequests++
    }
}

actual fun toggleRoomSidePanel(tab: RoomPanelTab): Boolean {
    with(DesktopRoomPanel) {
        if (open && this.tab == tab) open = false else {
            open = true
            this.tab = tab
        }
    }
    return true
}

private val PANEL_WIDTH = 380.dp
private val ROW_HEIGHT = 40.dp

@Composable
actual fun RoomSidePanel(container: AppContainer) {
    val panel = DesktopRoomPanel
    val chatShowing = panel.open && panel.tab == RoomPanelTab.CHAT
    LaunchedEffect(container) {
        container.sync.toasts.collect { msg ->
            val fromOther = msg.from != null && msg.from != container.sync.room.value.username
            if (fromOther && !(panel.open && panel.tab == RoomPanelTab.CHAT)) panel.unread++
        }
    }
    LaunchedEffect(chatShowing) { if (chatShowing) panel.unread = 0 }
    if (!panel.open) return

    Surface(
        Modifier.width(PANEL_WIDTH).fillMaxHeight().testTag("room_panel")
            // Keys the focused field or list didn't use stay in the panel, so typing never reaches the
            // player's shortcuts. Esc still goes on to leave full screen or go back.
            .onKeyEvent { it.key != Key.Escape && !it.isCtrlPressed && !it.isAltPressed && !it.isMetaPressed },
        color = AppColors.Surface,
        contentColor = AppColors.Text,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabRow(selectedTabIndex = panel.tab.ordinal, containerColor = AppColors.Surface, modifier = Modifier.weight(1f)) {
                    RoomPanelTab.entries.forEach { tab ->
                        Tab(selected = panel.tab == tab, onClick = { panel.tab = tab }, modifier = Modifier.testTag("panel_tab_${tab.name}"), text = {
                            val label = tab.name.lowercase().replaceFirstChar { it.uppercase() }
                            if (tab == RoomPanelTab.CHAT && panel.unread > 0) {
                                BadgedBox(badge = { Badge { Text(if (panel.unread > 99) "99+" else "${panel.unread}") } }) { Text(label) }
                            } else {
                                Text(label)
                            }
                        })
                    }
                }
                IconButton({ panel.open = false }) { Icon(Icons.Filled.Close, "Close the panel") }
            }
            when (panel.tab) {
                RoomPanelTab.ROOM -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    RoomUsersContent(container)
                }
                RoomPanelTab.PLAYLIST -> DesktopPlaylist(container, Modifier.weight(1f))
                RoomPanelTab.CHAT -> DesktopChat(container, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DesktopPlaylist(container: AppContainer, modifier: Modifier) {
    val room by container.sync.room.collectAsState()
    val settings by container.settings.collectAsState()
    val localFiles by container.local.files.collectAsState()
    val canUndo by container.playlist.shared.canUndo.collectAsState()
    val shared = container.playlist.shared
    val playlist = room.playlist
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var addingUrls by remember { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(playlist) { selected = selected.filter { it in playlist.indices }.toSet() }
    val available = remember(playlist, localFiles) { playlist.map(container.playlist::isAvailable) }
    val options = settings.sync

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

    // Where dragged-in files would be inserted while they hover over the tab, null otherwise.
    var dropAt by remember { mutableStateOf<Int?>(null) }
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density by rememberUpdatedState(LocalDensity.current.density)
    val drop = remember(container) {
        object : DragAndDropTarget {
            fun indexAt(event: DragAndDropEvent): Int {
                val count = container.sync.room.value.playlist.size
                val coords = listCoords?.takeIf { it.isAttached } ?: return count
                // AWT reports the pointer in points within the window's Compose content, whose root is in pixels.
                val pointerY = when (val native = event.nativeEvent) {
                    is DropTargetDragEvent -> native.location.y
                    is DropTargetDropEvent -> native.location.y
                    else -> return count
                }
                val y = pointerY * density - coords.positionInRoot().y
                return playlistDropIndex(y, coords.size.height, listState.layoutInfo.visibleItemsInfo, count)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Filled.Add, "Add videos") { addPaths(DesktopDialogs.pickVideos()) }
            ToolButton(Icons.Filled.CreateNewFolder, "Add the videos in a folder") { DesktopDialogs.pickFolder("Add the videos in a folder")?.let { addPaths(listOf(it)) } }
            ToolButton(Icons.Filled.Link, "Add URLs") { addingUrls = !addingUrls }
            Box {
                var open by remember { mutableStateOf(false) }
                ToolButton(Icons.Filled.Shuffle, "Shuffle", enabled = playlist.size > 1) { open = true }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Shuffle what's left") }, { open = false; shared.shuffle(entire = false) })
                    DropdownMenuItem({ Text("Shuffle everything") }, { open = false; shared.shuffle(entire = true) })
                }
            }
            ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo (Ctrl+Z)", enabled = canUndo) { shared.undo() }
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
                        DesktopDialogs.savePlaylist("${room.room}.txt")?.let { path ->
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

        if (playlist.isEmpty()) {
            Text(
                "The playlist is empty. Add videos, a folder or URLs, or drop videos or folders here.",
                color = AppColors.TextDim, modifier = Modifier.padding(16.dp),
            )
        }
        val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("playlist_list")
                .onGloballyPositioned { listCoords = it }
                .background(if (dropAt != null) AppColors.Accent.copy(alpha = 0.06f) else Color.Transparent)
                .drawWithContent {
                    drawContent()
                    val at = dropAt ?: return@drawWithContent
                    val rows = listState.layoutInfo.visibleItemsInfo
                    val y = rows.firstOrNull { it.index == at }?.offset?.toFloat()
                        ?: rows.lastOrNull()?.let { (it.offset + it.size).toFloat() } ?: 0f
                    val thickness = 2.dp.toPx()
                    val inset = 8.dp.toPx()
                    drawRect(
                        AppColors.Accent,
                        topLeft = Offset(inset, (y - thickness / 2).coerceIn(0f, size.height - thickness)),
                        size = Size(size.width - 2 * inset, thickness),
                    )
                }
                .focusRequester(listFocus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = event.isCtrlPressed || event.isMetaPressed
                    when {
                        event.key == Key.Delete || event.key == Key.Backspace -> removeSelected()
                        event.isAltPressed && event.key == Key.DirectionUp -> selected = shared.shift(selected, -1)
                        event.isAltPressed && event.key == Key.DirectionDown -> selected = shared.shift(selected, 1)
                        ctrl && event.key == Key.Z -> shared.undo()
                        ctrl && event.key == Key.A -> selected = playlist.indices.toSet()
                        event.key == Key.Enter -> selected.minOrNull()?.let(::play)
                        event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                            if (playlist.isEmpty()) return@onPreviewKeyEvent true
                            val from = anchor ?: selected.minOrNull() ?: -1
                            val to = (from + if (event.key == Key.DirectionUp) -1 else 1).coerceIn(0, playlist.lastIndex)
                            selected = if (event.isShiftPressed && anchor != null) rangeOf(anchor!!, to) else setOf(to).also { anchor = to }
                            container.scope.launch { listState.animateScrollToItem(to) }
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            itemsIndexed(playlist, key = { index, file -> "$index:$file" }) { index, file ->
                val dragging = dragFrom == index
                PlaylistRow(
                    index = index,
                    file = file,
                    current = index == room.playlistIndex,
                    selected = index in selected,
                    available = available.getOrElse(index) { true },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .onClick(keyboardModifiers = { isCtrlPressed || isMetaPressed }) {
                            listFocus.requestFocus()
                            selected = if (index in selected) selected - index else selected + index
                            anchor = index
                        }
                        .onClick(keyboardModifiers = { isShiftPressed }) {
                            listFocus.requestFocus()
                            selected = rangeOf(anchor ?: index, index)
                        }
                        .onClick(
                            keyboardModifiers = { !isCtrlPressed && !isMetaPressed && !isShiftPressed },
                            onDoubleClick = { play(index) },
                        ) {
                            listFocus.requestFocus()
                            selected = setOf(index)
                            anchor = index
                        }
                        .pointerInput(index, playlist.size) {
                            detectDragGestures(
                                onDragStart = { dragFrom = index; dragOffset = 0f },
                                onDragEnd = {
                                    val to = (index + (dragOffset / rowPx).roundToInt()).coerceIn(0, playlist.lastIndex)
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
                )
            }
        }
        if (dropAt != null) {
            Text(
                if (dropAt!! < playlist.size) "Release to insert the videos at the line" else "Release to add the videos at the end",
                color = AppColors.Accent, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("playlist_drop_hint"),
            )
        } else if (playlist.isNotEmpty()) {
            Text(
                "Double-click plays · Del removes · drag or Alt+↑↓ moves",
                color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private fun rangeOf(a: Int, b: Int): Set<Int> = (minOf(a, b)..maxOf(a, b)).toSet()

/**
 * The index files dropped at [y] (from the top of a list [height] tall showing [rows]) are inserted at: before the
 * row whose upper half is under the pointer. Outside the list they go at the end.
 */
internal fun playlistDropIndex(y: Float, height: Int, rows: List<LazyListItemInfo>, count: Int): Int {
    if (y < 0f || y > height) return count
    val row = rows.firstOrNull { y < it.offset + it.size / 2f } ?: return rows.lastOrNull()?.let { it.index + 1 } ?: count
    return row.index
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(index: Int, file: String, current: Boolean, selected: Boolean, available: Boolean, modifier: Modifier) {
    val background = when {
        selected -> AppColors.Accent.copy(alpha = 0.30f)
        current -> AppColors.Accent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Row(
        modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).background(background)
            .padding(horizontal = 8.dp).testTag("playlist_row_$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) Icon(Icons.Filled.PlayArrow, "Playing", tint = AppColors.Accent, modifier = Modifier.width(28.dp))
        else Text("${index + 1}.", color = AppColors.TextDim, modifier = Modifier.width(28.dp))
        Text(
            file,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (available) FontStyle.Normal else FontStyle.Italic,
            color = if (available) AppColors.Text else AppColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        if (!available) {
            TooltipArea(tooltip = { Tooltip("Not in your media folders; it's looked up on Jellyfin when it's picked") }) {
                Icon(Icons.Filled.SearchOff, "Not found here", tint = AppColors.TextDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UrlField(onAdd: (String) -> Boolean, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        text, { text = it },
        placeholder = { Text("https://… (Enter adds, several separated by spaces)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag("playlist_url_field")
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { if (onAdd(text)) { text = ""; onClose() }; true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
    )
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DesktopChat(container: AppContainer, modifier: Modifier) {
    val feed by container.sync.feed.collectAsState()
    val room by container.sync.room.collectAsState()
    val panel = DesktopRoomPanel
    var message by remember { mutableStateOf(TextFieldValue("")) }
    var recalled by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(feed.size) { if (feed.isNotEmpty()) listState.scrollToItem(feed.lastIndex) }
    LaunchedEffect(panel.chatFocusRequests) { if (panel.chatFocusRequests > 0) runCatching { focus.requestFocus() } }
    val connected = room.status == ConnectionStatus.CONNECTED
    val max = container.sync.maxChatLength

    fun send() {
        val text = message.text.trim()
        if (text.isEmpty()) return
        container.sync.sendChat(text)
        if (panel.sentHistory.lastOrNull() != text) panel.sentHistory.add(text)
        while (panel.sentHistory.size > 100) panel.sentHistory.removeAt(0)
        message = TextFieldValue("")
        recalled = null
    }
    fun recall(step: Int) {
        val history = panel.sentHistory
        if (history.isEmpty()) return
        val next = ((recalled ?: history.size) + step).coerceIn(0, history.size)
        recalled = next.takeIf { it < history.size }
        val text = history.getOrNull(next) ?: ""
        message = TextFieldValue(text, TextRange(text.length))
    }

    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(feed) { ChatLine(it) }
        }
        if (feed.isEmpty()) Text("No messages yet.", color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(
            message, { message = it.copy(text = it.text.take(max)) },
            placeholder = { Text(if (connected) "Message (Enter sends, Esc leaves)" else "Join a room to chat") },
            singleLine = true,
            enabled = connected,
            supportingText = if (message.text.length > max - 20) ({ Text("${message.text.length}/$max") }) else null,
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus).testTag("chat_field")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> send()
                        Key.Escape -> focusManager.clearFocus()
                        Key.DirectionUp -> recall(-1)
                        Key.DirectionDown -> recall(1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
    }
}

@Composable
private fun ChatLine(msg: FeedMessage) {
    val time = Instant.ofEpochMilli(msg.at).atZone(ZoneId.systemDefault()).format(timeFormat)
    Row {
        Text(time, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                if (msg.from != null) {
                    withStyle(SpanStyle(color = AppColors.Accent, fontWeight = FontWeight.Bold)) { append(msg.from) }
                    append(": ")
                    append(msg.text)
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(msg.text) }
                }
            },
            color = when {
                msg.isError -> AppColors.Error
                msg.from == null -> AppColors.TextDim
                else -> AppColors.Text
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.testTag("playlist_$label")) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun Tooltip(text: String) {
    Surface(color = AppColors.Background, shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
    )
}
