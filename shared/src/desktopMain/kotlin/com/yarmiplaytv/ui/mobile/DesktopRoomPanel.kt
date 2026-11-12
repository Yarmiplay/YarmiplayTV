package com.yarmiplaytv.ui.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.ui.chat.ChatMessageText
import com.yarmiplaytv.ui.chat.chatLineAnnotated
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
    var unread by mutableIntStateOf(0)
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
 * New messages beside the Chat tab's name: an optically centered count in the theme's accent.
 * A circle for 1-digit, pill for 2-digit and 99+, with tabular figures, centered alignment,
 * and no extra line-height offset.
 */
@Composable
private fun UnreadCount(count: Int, isSelected: Boolean = false) {
    val text = if (count > 99) "99+" else "$count"
    val singleDigit = text.length == 1
    val badgeBg = if (isSelected) AppColors.Surface else AppColors.Accent
    val badgeFg = if (isSelected) AppColors.Accent else AppColors.OnAccent
    Box(
        modifier = Modifier
            .height(BADGE_SIZE)
            .then(
                if (singleDigit) Modifier.width(BADGE_SIZE)
                else Modifier.widthIn(min = BADGE_SIZE)
            )
            .clip(CircleShape)
            .background(badgeBg)
            .then(
                if (!singleDigit) Modifier.padding(horizontal = if (text.length == 2) 4.dp else 5.dp)
                else Modifier
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (count == 1) "1 new message" else "$count new messages"
            },
        contentAlignment = Alignment.Center,
    ) {
        @Suppress("DEPRECATION")
        Text(
            text = text,
            color = badgeFg,
            style = TextStyle(
                fontSize = BADGE_FONT_SIZE,
                lineHeight = BADGE_FONT_SIZE,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontFeatureSettings = "tnum",
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val BADGE_FONT_SIZE = 10.sp
private val BADGE_SIZE = 18.dp
private val TAB_BAR_HEIGHT = 38.dp
private val ROW_HEIGHT = 40.dp

/**
 * Modern segmented/pill tab bar for the side panel (Room, Playlist, Chat, MAL).
 * Highlight pill animates smoothly between tabs, vector icons next to each label,
 * hover states, hand cursor on desktop, and proportional sizing so labels never wrap or truncate.
 */
@Composable
fun SegmentedTabBar(
    selectedTab: RoomPanelTab,
    onTabSelected: (RoomPanelTab) -> Unit,
    unreadCount: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .height(TAB_BAR_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Background)
            .border(1.dp, AppColors.SurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        val totalWidth = maxWidth
        val hasUnread = unreadCount > 0
        val weights = if (hasUnread) {
            floatArrayOf(1.0f, 1.35f, 1.35f, 0.9f)
        } else {
            floatArrayOf(1.05f, 1.45f, 1.05f, 0.95f)
        }
        val totalWeight = weights.sum()

        val tabWidths = RoomPanelTab.entries.mapIndexed { index, _ ->
            totalWidth * (weights[index] / totalWeight)
        }

        val tabOffsets = buildList {
            var current = 0.dp
            for (w in tabWidths) {
                add(current)
                current += w
            }
        }

        val selectedIndex = selectedTab.ordinal
        val targetOffset = tabOffsets[selectedIndex]
        val targetWidth = tabWidths[selectedIndex]

        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "tab_pill_offset",
        )
        val animatedWidth by animateDpAsState(
            targetValue = targetWidth,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "tab_pill_width",
        )

        // Pill highlight indicator
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(animatedWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(AppColors.Accent),
        )

        // Tabs
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoomPanelTab.entries.forEachIndexed { index, tab ->
                val isSelected = tab == selectedTab
                val tabWidth = tabWidths[index]

                SegmentedTabItem(
                    tab = tab,
                    isSelected = isSelected,
                    unreadCount = if (tab == RoomPanelTab.CHAT) unreadCount else 0,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.width(tabWidth).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun SegmentedTabItem(
    tab: RoomPanelTab,
    isSelected: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> AppColors.OnAccent
            isHovered || isFocused -> AppColors.Text
            else -> AppColors.TextDim
        },
        animationSpec = tween(durationMillis = 150),
        label = "tab_content_color",
    )

    val icon: ImageVector = when (tab) {
        RoomPanelTab.ROOM -> Icons.Filled.Groups
        RoomPanelTab.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
        RoomPanelTab.CHAT -> Icons.Filled.Forum
        RoomPanelTab.MAL -> Icons.Filled.Bookmarks
    }

    Box(
        modifier = modifier
            .testTag("panel_tab_${tab.name}")
            .clip(RoundedCornerShape(7.dp))
            .then(
                if (!isSelected && isHovered) {
                    Modifier.background(AppColors.SurfaceHigh.copy(alpha = 0.45f))
                } else if (!isSelected && isFocused) {
                    Modifier.background(AppColors.SurfaceHigh.copy(alpha = 0.35f))
                        .border(1.dp, AppColors.Accent.copy(alpha = 0.8f), RoundedCornerShape(7.dp))
                } else {
                    Modifier
                }
            )
            .hoverable(interactionSource)
            .focusable(interactionSource = interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = tab.label,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            if (tab == RoomPanelTab.CHAT && unreadCount > 0) {
                Spacer(Modifier.width(4.dp))
                UnreadCount(unreadCount, isSelected = isSelected)
            }
        }
    }
}

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SegmentedTabBar(
                        selectedTab = panel.tab,
                        onTabSelected = { panel.tab = it },
                        unreadCount = panel.unread,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { panel.open = false },
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close the panel",
                            modifier = Modifier.size(18.dp),
                            tint = AppColors.TextDim,
                        )
                    }
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
    val from = msg.from
    Row(Modifier.fillMaxWidth()) {
        Text(time, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        ChatMessageText(
            chatLineAnnotated(from, msg.text, AppColors.Accent, openLinks = true, italic = from == null),
            Modifier.weight(1f),
            color = when {
                msg.isError -> AppColors.Error
                from == null -> AppColors.TextDim
                else -> AppColors.Text
            },
            style = MaterialTheme.typography.bodyMedium,
            blockLabel = if (onBlock != null && from != null) "Block $from" else null,
            onBlock = onBlock,
        )
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
