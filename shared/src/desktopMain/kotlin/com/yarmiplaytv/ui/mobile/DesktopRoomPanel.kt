package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.shared.HIDDEN_CHAT_NOTE
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The desktop side panel's state; the window's Enter shortcut calls [focusChat]. */
object DesktopRoomPanel {
    var open by mutableStateOf(false)
    var tab by mutableStateOf(RoomPanelTab.CHAT)
    /** Session width of the docked panel (drag the left edge to resize). */
    var widthDp by mutableFloatStateOf(PANEL_WIDTH_DEFAULT)
    /** Chat messages from others that arrived while the chat tab wasn't showing. */
    internal var unread by mutableIntStateOf(0)
    internal var chatFocusRequests by mutableIntStateOf(0)
    /** Sent messages, oldest first, for Up/Down in the chat box. */
    internal val sentHistory = mutableStateListOf<String>()
    var draft by mutableStateOf(TextFieldValue(""))

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

internal const val PANEL_WIDTH_DEFAULT = 400f
internal const val PANEL_WIDTH_MIN = 300f
internal const val PANEL_WIDTH_MAX = 640f

/**
 * New messages beside the Chat tab's name: a count in the theme's accent, level with the name. A circle for one or two
 * digits ("99+" a pill as tall), with the digits in its middle both ways.
 */
@Composable
private fun UnreadCount(count: Int) {
    val text = if (count > 99) "99+" else "$count"
    val round = text.length <= 2
    Box(
        Modifier.height(BADGE_SIZE).then(if (round) Modifier.width(BADGE_SIZE) else Modifier.widthIn(min = BADGE_SIZE))
            .clip(RoundedCornerShape(50)).background(AppColors.Accent)
            .then(if (round) Modifier else Modifier.padding(horizontal = 5.dp))
            .semantics(mergeDescendants = true) { contentDescription = if (count == 1) "1 new message" else "$count new messages" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.digitsCentered(BADGE_FONT_SIZE),
            color = AppColors.OnAccent,
            style = TextStyle(fontSize = BADGE_FONT_SIZE, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/**
 * Moves the text so its digits, which stand on the baseline about 0.7 em tall, have their middle where the middle of
 * its box was: a font's line box has room for accents above and descenders below, which would leave them off-centre.
 */
private fun Modifier.digitsCentered(fontSize: TextUnit) = layout { measurable, constraints ->
    val text = measurable.measure(constraints)
    val baseline = text[FirstBaseline]
    val shift = if (baseline == AlignmentLine.Unspecified) 0 else (text.height / 2f + DIGIT_HEIGHT_EM / 2 * fontSize.toPx() - baseline).roundToInt()
    layout(text.width, text.height) { text.place(0, shift) }
}

private const val DIGIT_HEIGHT_EM = 0.7f
private val BADGE_FONT_SIZE = 11.sp
private val BADGE_SIZE = 20.dp
private val TAB_HEIGHT = 48.dp
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

    val density = LocalDensity.current
    Row(Modifier.fillMaxHeight().testTag("room_panel")) {
        // Drag the left edge to resize — video keeps the remaining width beside the panel.
        val resizeHover = remember { MutableInteractionSource() }
        val resizeHovered by resizeHover.collectIsHoveredAsState()
        Box(
            Modifier
                .width(8.dp)
                .fillMaxHeight()
                .testTag("room_panel_resize")
                .hoverable(resizeHover)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        val deltaDp = amount.x / density.density
                        panel.widthDp = (panel.widthDp - deltaDp).coerceIn(PANEL_WIDTH_MIN, PANEL_WIDTH_MAX)
                    }
                }
                .background(
                    if (resizeHovered) AppColors.Accent.copy(alpha = 0.45f)
                    else AppColors.SurfaceHigh.copy(alpha = 0.55f),
                ),
        )
        Surface(
            Modifier.width(panel.widthDp.dp).fillMaxHeight()
                // Keys the focused field or list didn't use stay in the panel, so typing never reaches the
                // player's shortcuts. Esc still goes on to leave full screen or go back.
                .onKeyEvent { it.key != Key.Escape && !it.isCtrlPressed && !it.isAltPressed && !it.isMetaPressed },
            color = AppColors.Surface,
            contentColor = AppColors.OnSurface,
        ) {
            SurfaceInk {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabRow(selectedTabIndex = panel.tab.ordinal, containerColor = AppColors.Surface, modifier = Modifier.weight(1f)) {
                        RoomPanelTab.entries.forEach { tab ->
                            // Less side padding than a text tab's, so the Chat tab's count fits beside its name at full size.
                            Tab(selected = panel.tab == tab, onClick = { panel.tab = tab }, modifier = Modifier.testTag("panel_tab_${tab.name}")) {
                                Row(
                                    Modifier.height(TAB_HEIGHT).padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(tab.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    if (tab == RoomPanelTab.CHAT && panel.unread > 0) UnreadCount(panel.unread)
                                }
                            }
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
                    RoomPanelTab.MAL -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        val mal by container.mal.state.collectAsState()
                        MalContent(container, mal)
                        Spacer(Modifier.height(16.dp))
                        MalFindSection(container)
                    }
                }
            }
            }
        }
    }
}

@Composable
internal fun UrlField(onAdd: (String) -> Boolean, onClose: () -> Unit) {
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
    val settings by container.settings.collectAsState()
    val showChat = settings.showRoomChat
    val panel = DesktopRoomPanel
    var message by panel::draft
    var recalled by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    // requestScrollToItem waits for the next layout. scrollToItem measures immediately, and a
    // message that arrives during layout makes that call throw.
    LaunchedEffect(feed.size) { if (feed.isNotEmpty()) listState.requestScrollToItem(feed.lastIndex) }
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
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showChat) Spacer(Modifier.weight(1f))
            else Text(HIDDEN_CHAT_NOTE, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton({ container.scope.launch { container.settingsStore.saveShowRoomChat(!showChat) } }, Modifier.testTag("toggle_chat")) {
                Text(if (showChat) "Hide chat" else "Show chat")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(feed) { msg ->
                ChatLine(msg, onBlock = msg.from?.takeIf { it != room.username }?.let { from -> { container.sync.block(from) } })
            }
        }
        if (feed.isEmpty()) Text("No messages yet.", color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp))
        if (showChat) OutlinedTextField(
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
private fun ChatLine(msg: FeedMessage, onBlock: (() -> Unit)?) {
    val time = Instant.ofEpochMilli(msg.at).atZone(ZoneId.systemDefault()).format(timeFormat)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(if (onBlock != null) Modifier.clickable { menu = true } else Modifier) {
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
        if (onBlock != null) {
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Block ${msg.from} (hide their messages)") }, { menu = false; onBlock() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.testTag("playlist_$label")) { Icon(icon, contentDescription = label) }
    }
}

@Composable
internal fun Tooltip(text: String) {
    Surface(color = AppColors.Background, shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun CheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
    )
}
