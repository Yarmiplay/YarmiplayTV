package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import com.yarmiplaytv.voice.peerPrefs
import com.yarmiplaytv.ui.shared.relayNote
import com.yarmiplaytv.ui.shared.ROOM_SKIP_TITLE
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.data.SavedRoom
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.player.fileDifferenceNote
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.shared.MANUAL_URL
import com.yarmiplaytv.ui.shared.ROOM_PRIVACY_HINT
import com.yarmiplaytv.ui.shared.SyncplayConnectModel
import com.yarmiplaytv.ui.shared.approvedOnly
import com.yarmiplaytv.ui.shared.connectionStatusLine
import com.yarmiplaytv.ui.shared.lanServerLabel
import com.yarmiplaytv.ui.shared.rememberSyncplayConnectModel
import com.yarmiplaytv.ui.shared.roomsOnServer
import com.yarmiplaytv.ui.shared.savedRoomLabel
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import com.yarmiplaytv.voice.micControlDescription
import com.yarmiplaytv.voice.micControlLive
import kotlinx.coroutines.launch

/**
 * The desktop's Syncplay-style windows (the Syncplay-style windows setting): a join screen, then the room in the
 * main window (chat on the left; who's playing what, the playlist and the room's controls on the right) and the
 * player in a window of its own. This is what both windows share; the desktop app opens them.
 */
object ClassicWindows {
    /** The player window is open. It opens with the room and for anything played; closing it only hides it. */
    var playerOpen by mutableStateOf(false)

    /** Brings the player window to the front; the player window sets this while it's open. */
    var raisePlayer: () -> Unit = {}

    /** Brings the room window to the front; the desktop app sets this. */
    var raiseRoom: () -> Unit = {}

    /** "Show more settings" on the join screen: the settings categories beside the connection. */
    var showMoreSettings by mutableStateOf(false)

    /** A file chosen on the join screen, played once the room is joined (Syncplay's "Path to video"). */
    internal var videoAfterJoin: String? = null

    /** Screens the room window shows over the room (settings, the library, …); [Screen.Room] is the room itself. */
    private val stack = mutableStateListOf<Screen>(Screen.Room)
    val nav = Navigator(stack)

    fun showPlayer() {
        playerOpen = true
        raisePlayer()
    }

    /** Shows [screen] in the room window over the room, or the player window for the player. */
    fun open(screen: Screen) {
        when (screen) {
            Screen.Player -> showPlayer()
            Screen.Room, Screen.SyncplayConnect -> nav.popTo(Screen.Room)
            else -> {
                nav.push(screen)
                raiseRoom()
            }
        }
    }

    /** Esc in the room window: leaves a screen shown over the room; false when the room itself is showing. */
    fun back(): Boolean {
        if (!nav.canGoBack) return false
        nav.back()
        return true
    }

    /** Enter in either window: the room window's chat box. */
    fun focusChat() {
        raiseRoom()
        DesktopRoomPanel.chatFocusRequests++
    }
}

/**
 * The room window's content: the menus, then the join screen until a room is joined and the room after that. Screens
 * opened from the menus or the settings (the library, media servers, …) show over it with a way back.
 */
@Composable
fun ClassicRoomWindow(container: AppContainer, onRegularView: () -> Unit) {
    val room by container.sync.room.collectAsState()
    val state by container.player.collectStateWithoutClock()
    val nav = ClassicWindows.nav
    val inRoom = room.status == ConnectionStatus.CONNECTED || room.status == ConnectionStatus.RECONNECTING
    val saveable = rememberSaveableStateHolder()

    LaunchedEffect(inRoom) {
        if (inRoom) {
            // Like Syncplay starting mpv: the player window comes up with the room, and the join screen's video plays.
            ClassicWindows.playerOpen = true
            ClassicWindows.videoAfterJoin?.let { video ->
                ClassicWindows.videoAfterJoin = null
                container.playlist.playLocal(video, inRoom = false)
            }
        } else if (!container.player.state.value.fileLoaded) {
            ClassicWindows.playerOpen = false
        }
    }
    LaunchedEffect(Unit) {
        // Switched to these windows while something plays: it goes on in the player window.
        if (container.player.state.value.fileLoaded) ClassicWindows.playerOpen = true
        container.playlist.openPlayerRequests.collect { request ->
            if (request <= container.playlist.handledPlayerRequests) return@collect
            container.playlist.handledPlayerRequests = request
            ClassicWindows.showPlayer()
        }
    }
    DisposableEffect(Unit) {
        // Back in the regular view: next time these windows start at the room again, not at a screen left open.
        onDispose { nav.popTo(Screen.Room) }
    }
    LaunchedEffect(Unit) {
        // Anything here that opens the player opens the player window instead.
        snapshotFlow { nav.current }.collect { if (it == Screen.Player) { nav.back(); ClassicWindows.showPlayer() } }
    }

    val over = nav.current.takeIf { it != Screen.Room && it != Screen.SyncplayConnect && it != Screen.Player }
    Column(Modifier.fillMaxSize().background(AppColors.Background).testTag("classic_window")) {
        ClassicMenuBar(container, inRoom, state.fileLoaded, onRegularView)
        // A new version: the regular view shows this on Home, which this window doesn't have. Nothing when there's none.
        if (over == null) UpdateBanner(container, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                over != null -> Column(Modifier.fillMaxSize()) {
                    TextButton({ nav.popTo(Screen.Room) }, Modifier.padding(start = 8.dp, top = 4.dp).testTag("classic_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (inRoom) "Back to the room" else "Back to joining")
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        saveable.SaveableStateProvider(over.key) { AppScreen(container, nav, over, DeviceKind.DESKTOP) }
                    }
                }
                inRoom -> ClassicRoom(container)
                else -> ClassicJoin(container)
            }
        }
    }
    nav.actionItem?.let { item ->
        MobileItemActionsSheet(container, item, onDismiss = { nav.actionItem = null }, onMal = { nav.push(Screen.MalEntry(item)) })
    }
    MobileDeviceAccessDialog(container)
    MobileSecurityPromptDialog(container)
    LaunchedEffect(Unit) {
        // The player window may be in front; the question is asked here.
        container.securityPrompts.current.collect { if (it != null) ClassicWindows.raiseRoom() }
    }
}

/**
 * The player window's content: the app's own player, without the room, chat and playlist buttons (they're in the
 * room window). What the player would open instead of itself (search, the library) opens in the room window.
 */
@Composable
fun ClassicPlayerContent(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home, Screen.Player) }
    val nav = remember { Navigator(stack) }
    LaunchedEffect(nav) {
        snapshotFlow { nav.current }.collect { screen ->
            if (screen == Screen.Player) return@collect
            stack.clear()
            stack.addAll(listOf(Screen.Home, Screen.Player))
            ClassicWindows.open(screen)
        }
    }
    CompositionLocalProvider(LocalRoomElsewhere provides true, LocalContentColor provides AppColors.Text) {
        MobilePlayerScreen(container, nav)
    }
}

// ---- Menus ----

@Composable
private fun ClassicMenuBar(container: AppContainer, inRoom: Boolean, fileLoaded: Boolean, onRegularView: () -> Unit) {
    val room by container.sync.room.collectAsState()
    val uri = LocalUriHandler.current
    val openVideo = rememberVideoPicker { container.playlist.playLocal(it, inRoom = false) }
    Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, modifier = Modifier.fillMaxWidth().testTag("classic_menu_bar")) {
        SurfaceInk {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MenuButton("File") { close ->
                    MenuItem("Open media file…") { close(); openVideo() }
                    MenuItem("Browse your library") { close(); ClassicWindows.open(Screen.Home) }
                    MenuItem("Search") { close(); ClassicWindows.open(Screen.Search()) }
                    HorizontalDivider()
                    MenuItem("Exit") { close(); container.exitApp() }
                }
                MenuButton("Playback") { close ->
                    val paused = container.player.state.value.paused
                    MenuItem(if (paused) "Play" else "Pause", enabled = fileLoaded) { close(); container.player.setPaused(!paused) }
                    MenuItem("Previous in playlist", enabled = container.playlist.skipTarget(room, forward = false) != null) {
                        close(); container.playlist.skip(false)
                    }
                    MenuItem("Next in playlist", enabled = container.playlist.skipTarget(room, forward = true) != null) {
                        close(); container.playlist.skip(true)
                    }
                    HorizontalDivider()
                    MenuItem("Show the player window") { close(); ClassicWindows.showPlayer() }
                }
                if (inRoom) {
                    var details by remember { mutableStateOf(false) }
                    val skip by container.roomAutoSkip.state.collectAsState()
                    MenuButton("Room") { close ->
                        CheckItem("I'm ready to watch", room.isReady == true) { close(); container.sync.toggleReady() }
                        if (skip.inRoom) {
                            DropdownMenuItem(
                                { Text(ROOM_SKIP_TITLE) },
                                { close(); container.roomAutoSkip.set(!skip.on) },
                                Modifier.testTag("classic_room_skip"),
                                leadingIcon = { if (skip.on) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                                enabled = skip.canChange,
                            )
                        }
                        MenuItem("Room details, voice and managed rooms…", Modifier.testTag("classic_room_details_item")) { close(); details = true }
                        HorizontalDivider()
                        MenuItem("Leave the room") { close(); container.sync.disconnect() }
                    }
                    if (details) RoomDetailsDialog(container) { details = false }
                }
                MenuButton("View") { close ->
                    MenuItem("Settings…") { close(); ClassicWindows.open(Screen.Settings) }
                    MenuItem("Show the player window") { close(); ClassicWindows.showPlayer() }
                    HorizontalDivider()
                    MenuItem("Switch to the regular view", Modifier.testTag("classic_regular_view")) { close(); onRegularView() }
                }
                MenuButton("Help") { close ->
                    MenuItem("User manual") { close(); runCatching { uri.openUri(MANUAL_URL) } }
                    MenuItem("What's new") { close(); ClassicWindows.open(Screen.WhatsNew) }
                }
                Spacer(Modifier.weight(1f))
                if (inRoom) {
                    val server = container.sync.server.orEmpty()
                    if (room.tls) Icon(Icons.Filled.Lock, contentDescription = "Encrypted connection", tint = AppColors.Ready, modifier = Modifier.size(16.dp))
                    Text(
                        listOfNotNull(server.takeIf { it.isNotEmpty() }, room.serverVersion?.let { "Syncplay $it" }).joinToString(" · "),
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            { open = true },
            Modifier.heightIn(min = 32.dp).pointerHoverIcon(PointerIcon.Hand).testTag("classic_menu_${label.lowercase()}"),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(label, color = AppColors.Text, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

@Composable
private fun MenuItem(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem({ Text(label) }, onClick, modifier, enabled = enabled)
}

@Composable
private fun RoomDetailsDialog(container: AppContainer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room details") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp).verticalScroll(rememberScrollState())) {
                RoomUsersContent(container)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        modifier = Modifier.testTag("classic_room_details"),
    )
}

// ---- The room ----

/** The joined room: chat on the left; who's playing what, the shared playlist and the room's controls on the right. */
@Composable
private fun ClassicRoom(container: AppContainer) {
    Row(Modifier.fillMaxSize().padding(12.dp).testTag("classic_room"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ClassicPanel("Chat", Modifier.weight(0.85f).fillMaxHeight()) {
            DesktopChat(container, Modifier.weight(1f))
        }
        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ClassicPanel("Who's playing what", Modifier.fillMaxWidth().weight(0.9f), actions = { VoiceControls(container) }) {
                PeopleTable(container, Modifier.weight(1f))
            }
            ClassicPanel("Shared playlist", Modifier.fillMaxWidth().weight(1.1f)) {
                DesktopPlaylist(container, Modifier.weight(1f))
            }
            RoomBar(container)
            ReadyToWatch(container)
        }
    }
}

@Composable
private fun ClassicPanel(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        SurfaceInk {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title.uppercase(), color = AppColors.TextDim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    actions()
                }
                content()
            }
        }
    }
}

/** The room's voice in the people panel's header: the mic and joining or leaving voice. */
@Composable
private fun VoiceControls(container: AppContainer) {
    val voiceUi by container.voice.ui.collectAsState()
    if (!voiceUi.showVoiceMembershipButton) return
    if (voiceUi.showMicButton) {
        val live = voiceUi.micControlLive()
        IconButton({ container.voice.toggleMic() }, Modifier.size(32.dp).testTag("classic_mic")) {
            Icon(
                if (live) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = voiceUi.micControlDescription(showKeyboardShortcut = true),
                tint = if (live) AppColors.Ready else AppColors.TextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (voiceUi.voiceMembershipIsLeave) {
        TextButton({ container.voice.leave() }, Modifier.testTag("classic_voice_leave")) { Text("Leave voice") }
    } else {
        TextButton({ container.voice.join() }, Modifier.testTag("classic_voice_join")) { Text("Join voice") }
    }
}

/** Syncplay's "List of who is playing what": the room, then each person with their ready state and file. */
@Composable
private fun PeopleTable(container: AppContainer, modifier: Modifier = Modifier) {
    val room by container.sync.room.collectAsState()
    val voiceUi by container.voice.ui.collectAsState()
    val blocked by container.sync.blocked.collectAsState()
    val settings by container.settings.collectAsState()
    var details by remember { mutableStateOf(false) }
    if (details) RoomDetailsDialog(container) { details = false }
    val me = room.users.firstOrNull { it.name == room.username }
    Column(modifier.fillMaxWidth()) {
        // YarmiplayServerTV relaying a file: who streams it, how far the download is.
        container.relay?.let { relay ->
            val status by relay.status.collectAsState()
            status?.let { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("classic_relay")) {
                    Text(s.text, color = AppColors.Accent, style = MaterialTheme.typography.bodySmall)
                    if (s.downloading && !s.complete) RelayDownloadBar(s.fraction)
                    s.error?.let { Text(it, color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            HeaderCell("Person", Modifier.weight(0.9f).padding(start = 26.dp))
            HeaderCell("Size", Modifier.width(72.dp))
            HeaderCell("Length", Modifier.width(64.dp))
            HeaderCell("File", Modifier.weight(1.4f))
        }
        HorizontalDivider(color = AppColors.SurfaceHigh)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("classic_people")) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MeetingRoom, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ManagedRooms.label(room.room), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val ready = room.users.count { it.isReady == true }
                    Text("$ready of ${room.users.size} ready", color = AppColors.TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
            items(room.users, key = { it.name }) { user ->
                val speaking = voiceUi.showUi && voiceUi.peers.firstOrNull { it.username == user.name }?.participantId?.let { it in voiceUi.speakingIds } == true
                val voiceMuted = if (voiceUi.showUi && user.name != room.username) settings.peerPrefs(user.name).muted else null
                PersonRow(container, room, user, me, speaking, user.name in blocked, voiceMuted, onDetails = { details = true })
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = modifier)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PersonRow(
    container: AppContainer,
    room: RoomState,
    user: RoomUser,
    me: RoomUser?,
    speaking: Boolean,
    isBlocked: Boolean,
    /** Whether their voice is muted here; null without voice. */
    voiceMuted: Boolean?,
    onDetails: () -> Unit,
) {
    val isMe = user.name == room.username
    val difference = if (isMe) null else fileDifferenceNote(user.file, me?.file)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed && !isMe) menu = true }
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("classic_person_${user.name}"),
            verticalAlignment = Alignment.Top,
        ) {
            Row(Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (user.isReady) {
                        true -> Icons.Filled.CheckCircle
                        false -> Icons.Filled.RadioButtonUnchecked
                        null -> Icons.Filled.Remove
                    },
                    contentDescription = when (user.isReady) { true -> "Ready"; false -> "Not ready"; null -> null },
                    tint = when (user.isReady) { true -> AppColors.Ready; false -> AppColors.NotReady; null -> AppColors.TextDim },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    user.name + (if (user.isController) " ★" else ""),
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBlocked) AppColors.Error else AppColors.Text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (speaking) Icon(Icons.Filled.Mic, contentDescription = "Speaking", tint = AppColors.Accent, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            }
            val file = user.file
            val dim = if (difference != null) AppColors.NotReady else AppColors.TextDim
            Text(file?.let { formatFileSize(it.size) } ?: "", color = dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(72.dp))
            Text(file?.takeIf { it.duration > 0 }?.let { formatClock(it.duration) } ?: "", color = dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1.4f)) {
                if (file != null) {
                    Text(file.name, color = if (difference != null) AppColors.NotReady else AppColors.Text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("(No file being played)", color = AppColors.Accent, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (difference != null) Text(difference, color = AppColors.NotReady, style = MaterialTheme.typography.labelSmall)
                relayNote(room, user)?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (voiceMuted != null) {
                MenuItem(if (voiceMuted) "Unmute ${user.name}'s voice" else "Mute ${user.name}'s voice (only for you)") {
                    menu = false
                    container.voice.setPeerMuted(user.name, !voiceMuted)
                }
            }
            MenuItem(if (isBlocked) "Unblock ${user.name}" else "Block ${user.name} (hide their messages)") {
                menu = false
                if (isBlocked) container.sync.unblock(user.name) else container.sync.block(user.name)
            }
            if (room.isOperator) {
                val markReady = user.isReady != true
                MenuItem(if (markReady) "Mark ${user.name} ready" else "Mark ${user.name} not ready") {
                    menu = false
                    container.sync.setOthersReady(user.name, markReady)
                }
            }
            MenuItem("Voice volume and more…") { menu = false; onDetails() }
        }
    }
}

/** Switching rooms: the room's name (with the rooms you know on this server) and Join room; saving this room. */
@Composable
private fun RoomBar(container: AppContainer) {
    val room by container.sync.room.collectAsState()
    val settings by container.settings.collectAsState()
    var text by remember(room.room) { mutableStateOf(room.room) }
    var pick by remember { mutableStateOf(false) }
    val others = roomsOnServer(settings.savedRooms, container.sync.server, room.room)
    fun join() {
        val to = text.trim()
        if (to.isNotEmpty() && to != room.room) container.sync.changeRoom(to)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                text, { text = it.take(60) },
                label = { Text("Room") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { join() }),
                trailingIcon = if (others.isEmpty()) null else ({
                    IconButton({ pick = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Rooms you know") }
                }),
                modifier = Modifier.fillMaxWidth().testTag("classic_room_field"),
            )
            DropdownMenu(pick, { pick = false }) {
                others.forEach { r ->
                    MenuItem(savedRoomLabel(r) + if (r.saved) " ★" else "") { pick = false; text = r.room; container.sync.changeRoom(r.room) }
                }
            }
        }
        Button(::join, enabled = text.isNotBlank() && text.trim() != room.room, modifier = Modifier.testTag("classic_change_room")) { Text("Join room") }
        SaveCurrentRoom(container)
    }
}

/** Syncplay's "I'm ready to watch!", across the bottom. */
@Composable
private fun ReadyToWatch(container: AppContainer) {
    val room by container.sync.room.collectAsState()
    val ready = room.isReady == true
    Surface(
        onClick = { container.sync.toggleReady() },
        color = if (ready) AppColors.Ready.copy(alpha = 0.18f) else AppColors.Surface,
        contentColor = AppColors.OnSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (ready) AppColors.Ready.copy(alpha = 0.6f) else AppColors.SurfaceHigh),
        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).testTag("classic_ready"),
    ) {
        SurfaceInk {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ready, { container.sync.toggleReady() })
                Text("I'm ready to watch!", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (ready) "Ready" else "Not ready", color = if (ready) AppColors.Ready else AppColors.NotReady, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

/** "192 MB", "1.4 GB"; empty when the size isn't known. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}

// ---- Joining ----

/**
 * Syncplay's configuration window in the app's design: the connection, auto-join and a video to play once joined;
 * "Show more settings" adds the app's settings categories beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassicJoin(container: AppContainer) {
    val model = rememberSyncplayConnectModel(container)
    val settings by container.settings.collectAsState()
    val room by container.sync.room.collectAsState()
    val showMore = ClassicWindows.showMoreSettings
    var section by remember { mutableStateOf<SettingsCategory?>(null) }
    val categories = remember(container) { SettingsCategory.entries.filter { it != SettingsCategory.MAL || container.mal.client.configured } }
    var video by remember { mutableStateOf(ClassicWindows.videoAfterJoin) }
    val connecting = room.status == ConnectionStatus.CONNECTING || room.status == ConnectionStatus.RECONNECTING
    fun join() {
        ClassicWindows.videoAfterJoin = video
        model.connect()
    }

    Box(Modifier.fillMaxSize().testTag("classic_join"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = if (showMore) 1040.dp else 720.dp).fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesktopAppMark(44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Join a room", style = MaterialTheme.typography.headlineSmall, color = AppColors.Text)
                    Text(
                        "The room opens in this window and the player in a window of its own",
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (showMore) {
                    Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("classic_settings_nav")) {
                        SideNavItem("Basics", Icons.Filled.Groups, section == null, { section = null }, Modifier.testTag("classic_section_basics"))
                        categories.forEach { c ->
                            SideNavItem(c.title, c.icon, section == c, { section = c }, Modifier.testTag("classic_section_${c.name.lowercase()}"))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                }
                Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).fillMaxHeight()) {
                    SurfaceInk {
                        val category = section?.takeIf { showMore }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (category == null) {
                                // After an update, its notes, like Home's card; it scrolls with the form.
                                WhatsNewCard(container, ClassicWindows.nav, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), desktop = true)
                                JoinBasics(container, model, showMore, video, onVideo = { video = it }, onJoin = ::join)
                            } else {
                                Text(category.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                                CategorySettings(category, container, ClassicWindows.nav)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // The buttons go under the checkboxes when the window (or a larger font) leaves no room beside them.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckRow("Show more settings", showMore, Modifier.testTag("classic_show_more")) { ClassicWindows.showMoreSettings = it }
                    Spacer(Modifier.width(12.dp))
                    CheckRow("Enable shared playlists", settings.sync.sharedPlaylists, Modifier.testTag("classic_shared_playlists")) { on ->
                        val sync = container.settings.value.sync
                        container.scope.launch { container.settingsStore.savePlaylistOptions(on, sync.loopPlaylist, sync.loopSingleFile) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton({ container.sync.disconnect() }, Modifier.testTag("classic_cancel")) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                }
                if (!model.showsOperatorPassword) {
                    // Joins the room above, then makes it a managed one with you as its operator.
                    OutlinedButton(
                        { ClassicWindows.videoAfterJoin = video; model.createManaged() },
                        enabled = !connecting,
                        modifier = Modifier.testTag("classic_create_managed"),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create managed room")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(::join, enabled = !connecting, modifier = Modifier.testTag("classic_join_button")) { Text("Join room") }
                }
            }
        }
    }
    if (model.askRules) RoomRulesDialog(onAgree = { model.acceptRules() }, onDecline = model::declineRules)
}

/** Syncplay's "Basics": the server, your name, the room, auto-join and a video to open. */
@Composable
private fun JoinBasics(
    container: AppContainer,
    model: SyncplayConnectModel,
    showMore: Boolean,
    video: String?,
    onVideo: (String?) -> Unit,
    onJoin: () -> Unit,
) {
    val settings by container.settings.collectAsState()
    val room by container.sync.room.collectAsState()
    val connectionError by container.sync.connectionError.collectAsState()
    val pickVideo = rememberVideoPicker { onVideo(it) }
    val enter = KeyboardActions(onGo = { onJoin() })
    val go = KeyboardOptions(imeAction = ImeAction.Go)
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Connection")
        ServerField(container, model, enter)
        if (!approvedOnly(settings.knownServers, model.host, model.port)) {
            OutlinedTextField(
                model.password, { model.password = it },
                label = { Text("Server password (if any)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = enter,
                modifier = Modifier.fillMaxWidth().testTag("classic_server_password"),
            )
        }
        OutlinedTextField(
            model.username, { model.username = it.take(16) },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = go,
            keyboardActions = enter,
            modifier = Modifier.fillMaxWidth().testTag("classic_name"),
        )
        RoomField(container, model, enter)
        if (model.showsOperatorPassword) {
            OutlinedTextField(
                model.operatorPassword, { model.operatorPassword = it.uppercase().take(20) },
                label = { Text("Operator password (optional)") },
                placeholder = { Text("AB-123-456") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = enter,
                modifier = Modifier.fillMaxWidth().testTag("classic_operator_password"),
            )
        }
        if (showMore) {
            SwitchRow("Encrypted connection (TLS)", "Syncplay's public servers and YarmiplayServerTV both support it", model.tls, Modifier.testTag("classic_tls")) { model.tls = it }
            CheckRow("Save this room once joined", model.saveRoom, Modifier.testTag("classic_save_room")) { model.saveRoom = it }
            model.serverKey?.takeIf(container::hasDeviceKey)?.let { server ->
                TextButton({ container.forgetDeviceKey(server) }) { Text("Forget this device's key") }
            }
        }

        SectionLabel("Joining")
        SwitchRow(
            "Auto-join",
            "Join this room as soon as YarmiplayTV starts, without this screen",
            model.autoConnect,
            Modifier.testTag("classic_auto_join"),
        ) { on ->
            model.autoConnect = on
            container.scope.launch { container.settingsStore.setAutoConnect(on) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                video?.let { uriFileName(it) ?: it }.orEmpty(), {},
                readOnly = true,
                label = { Text("Video to play once joined (optional)") },
                singleLine = true,
                trailingIcon = if (video == null) null else ({
                    IconButton({ onVideo(null) }) { Icon(Icons.Filled.Clear, contentDescription = "No video") }
                }),
                modifier = Modifier.weight(1f).testTag("classic_video"),
            )
            OutlinedButton(pickVideo, Modifier.testTag("classic_video_browse")) { Text("Browse") }
        }

        model.error?.let { Text(it, color = AppColors.Error, modifier = Modifier.testTag("classic_error")) }
        connectionStatusLine(room.status, connectionError)?.let {
            Text(it, color = if (room.status == ConnectionStatus.DISCONNECTED) AppColors.Error else AppColors.TextDim, modifier = Modifier.testTag("classic_status"))
        }
        Text("Use the same server and room as your friends' Syncplay. $ROOM_PRIVACY_HINT", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** "host:port" in one field, like Syncplay, with the servers you've used, found on your network, and public ones. */
@Composable
private fun ServerField(container: AppContainer, model: SyncplayConnectModel, enter: KeyboardActions) {
    val settings by container.settings.collectAsState()
    var text by remember { mutableStateOf(formatServerAddress(model.host, model.port)) }
    var pick by remember { mutableStateOf(false) }
    // Picking a room or a found server changes the form; the field follows unless it already says the same.
    LaunchedEffect(model.host, model.port) {
        val (host, port) = parseServerAddress(text)
        if (host != model.host || (port ?: DEFAULT_SYNCPLAY_PORT).toString() != model.port) text = formatServerAddress(model.host, model.port)
    }
    val used = remember(settings.savedRooms) { settings.savedRooms.distinctBy { "${it.host.lowercase()}:${it.port}" } }
    Box {
        OutlinedTextField(
            text,
            { value ->
                text = value
                val (host, port) = parseServerAddress(value)
                model.host = host
                model.port = (port ?: DEFAULT_SYNCPLAY_PORT).toString()
            },
            label = { Text("Server address") },
            placeholder = { Text("syncplay.pl:8999") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = enter,
            trailingIcon = { IconButton({ pick = true }, Modifier.testTag("classic_server_pick")) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Servers") } },
            modifier = Modifier.fillMaxWidth().testTag("classic_server"),
        )
        DropdownMenu(pick, { pick = false }) {
            used.forEach { r ->
                MenuItem(formatServerAddress(r.host, r.port.toString())) {
                    pick = false
                    model.host = r.host
                    model.port = r.port.toString()
                    model.tls = r.useTls
                }
            }
            model.discovered.forEach { server ->
                MenuItem(lanServerLabel(server)) { pick = false; model.pick(server) }
            }
            if (used.isNotEmpty() || model.discovered.isNotEmpty()) HorizontalDivider()
            (8995..8999).forEach { port ->
                MenuItem("syncplay.pl:$port (public)", Modifier.testTag("classic_public_$port")) {
                    pick = false
                    model.host = "syncplay.pl"
                    model.port = port.toString()
                }
            }
        }
    }
}

/** The room, with the saved and recent rooms (on this server first) to pick from. */
@Composable
private fun RoomField(container: AppContainer, model: SyncplayConnectModel, enter: KeyboardActions) {
    val settings by container.settings.collectAsState()
    var pick by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<SavedRoom?>(null) }
    var renaming by remember { mutableStateOf<SavedRoom?>(null) }
    val here = settings.savedRooms.filter { it.host.equals(model.host.trim(), ignoreCase = true) && it.port.toString() == model.port }
    val rooms: List<SavedRoom> = (here + settings.savedRooms.sortedByDescending { it.saved }).distinctBy { it.key }
    Box {
        OutlinedTextField(
            model.room, { model.room = it.take(60) },
            label = { Text("Room") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = enter,
            trailingIcon = if (rooms.isEmpty()) null else ({
                IconButton({ pick = true }, Modifier.testTag("classic_room_pick")) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Rooms you know") }
            }),
            modifier = Modifier.fillMaxWidth().testTag("classic_room"),
        )
        DropdownMenu(pick, { pick = false }) {
            rooms.forEach { r ->
                val elsewhere = r !in here
                DropdownMenuItem(
                    { Text(savedRoomLabel(r) + if (elsewhere) "  ·  ${formatServerAddress(r.host, r.port.toString())}" else "") },
                    { pick = false; model.pick(r) },
                    Modifier.testTag("classic_known_${r.room}"),
                    trailingIcon = {
                        Row {
                            IconButton({ pick = false; naming = r }, Modifier.size(32.dp).testTag("classic_star_${r.room}")) {
                                Icon(
                                    if (r.saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = if (r.saved) "Rename or unsave" else "Save this room",
                                    tint = if (r.saved) AppColors.Accent else AppColors.TextDim,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                { container.scope.launch { container.settingsStore.forgetSavedRoom(r.key) } },
                                Modifier.size(32.dp).testTag("classic_forget_${r.room}"),
                            ) { Icon(Icons.Filled.Close, contentDescription = "Forget", tint = AppColors.TextDim, modifier = Modifier.size(18.dp)) }
                        }
                    },
                )
            }
        }
    }
    naming?.let { r ->
        if (r.saved) {
            // Saved: rename it, or take it off the saved list.
            AlertDialog(
                onDismissRequest = { naming = null },
                title = { Text(savedRoomLabel(r)) },
                text = { Text("Rename this saved room, or take it off the saved list (it stays under recent rooms).") },
                confirmButton = { TextButton({ naming = null; renaming = r }) { Text("Rename") } },
                dismissButton = {
                    TextButton({ naming = null; container.scope.launch { container.settingsStore.setRoomSaved(r.key, false) } }) { Text("Unsave") }
                },
            )
        } else {
            RoomNameDialog("Save this room", "") { label ->
                naming = null
                if (label != null) container.scope.launch { container.settingsStore.setRoomSaved(r.key, true, label) }
            }
        }
    }
    renaming?.let { r ->
        RoomNameDialog("Rename room", r.label) { label ->
            renaming = null
            if (label != null) container.scope.launch { container.settingsStore.setRoomSaved(r.key, true, label) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = AppColors.TextDim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Text(label, color = AppColors.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.Text)
            Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

internal const val DEFAULT_SYNCPLAY_PORT = 8999

/** "syncplay.pl:8999", with an IPv6 address in brackets; just the host when [port] is empty. */
internal fun formatServerAddress(host: String, port: String): String {
    val h = host.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
    return if (port.isBlank()) h else "$h:$port"
}

/**
 * Splits "host:port" as typed; the port is null when there's none (or it isn't a number). Takes "[::1]:8999", and a
 * bare IPv6 address without a port.
 */
internal fun parseServerAddress(text: String): Pair<String, Int?> {
    val t = text.trim()
    if (t.startsWith("[")) {
        val end = t.indexOf(']')
        if (end < 0) return t.removePrefix("[") to null
        val host = t.substring(1, end)
        val port = t.substring(end + 1).removePrefix(":").toIntOrNull()
        return host to port
    }
    val colons = t.count { it == ':' }
    if (colons != 1) return t to null
    val host = t.substringBefore(':')
    return host to t.substringAfter(':').trim().toIntOrNull()
}
