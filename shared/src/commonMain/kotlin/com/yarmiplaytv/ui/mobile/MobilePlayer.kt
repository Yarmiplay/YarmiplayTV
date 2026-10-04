package com.yarmiplaytv.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerSheet { PLAYLIST, ROOM, CHAT, AUDIO, SUBTITLES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilePlayerScreen(container: AppContainer, nav: Navigator) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val status by container.playlist.status.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val step = settings.playback.seekStepSeconds.toDouble()
    val inRoom = room.status == ConnectionStatus.CONNECTED

    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    val barsHover = remember { MutableInteractionSource() }
    val onBars by barsHover.collectIsHoveredAsState()

    FullscreenLandscape()

    LaunchedEffect(controls, interaction, state.paused, sheet, onBars) {
        if (controls && !state.paused && sheet == null && !onBars) {
            delay(controlsHideMillis)
            controls = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(700); seekFlash = null } }

    PlatformBackHandler {
        when {
            sheet != null -> sheet = null
            else -> nav.back()
        }
    }

    fun touch() { interaction = System.currentTimeMillis() }
    fun togglePause() { player.setPaused(!player.isPaused); touch() }
    fun seekBy(delta: Double) {
        player.seekRelative(delta)
        seekFlash = if (delta < 0) "−${(-delta).toInt()} s" else "+${delta.toInt()} s"
        touch()
    }

    val input = PlayerInput(
        seekStep = step,
        controlsVisible = controls || sheet != null,
        toggleControls = { controls = !controls; touch() },
        showControls = { controls = true; touch() },
        togglePause = ::togglePause,
        seekBy = ::seekBy,
    )
    fun openSheet(kind: PlayerSheet) {
        val tab = when (kind) {
            PlayerSheet.PLAYLIST -> RoomPanelTab.PLAYLIST
            PlayerSheet.ROOM -> RoomPanelTab.ROOM
            PlayerSheet.CHAT -> RoomPanelTab.CHAT
            else -> null
        }
        if (tab == null || !toggleRoomSidePanel(tab)) sheet = kind
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black).testTag("player").playerScreenInput(input)) {
            VideoSurface(player, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().testTag("player_gestures").videoGestures(input))

            CenterStatus(container, nav, status, nowPlaying == null, state.fileLoaded, inRoom)

            if (state.buffering && state.fileLoaded) {
                Chip("Buffering… %.0fs cached".format(state.cacheSeconds), AppColors.NotReady, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            }
            seekFlash?.let { Chip(it, AppColors.Accent, Modifier.align(Alignment.Center)) }
            PlayerToasts(container, Modifier.align(Alignment.TopStart).padding(16.dp))

            androidx.compose.animation.AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().testTag("player_controls")) {
                    Row(
                        Modifier.fillMaxWidth().hoverable(barsHover)
                            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Text(nowPlaying?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        RoomStatusChip(room)
                        if (hideControlsButton) {
                            TextButton({ controls = false }, Modifier.testTag("hide_controls")) {
                                Icon(Icons.Filled.KeyboardArrowDown, null, tint = AppColors.TextDim)
                                Text("Hide (H)", color = AppColors.TextDim)
                            }
                        }
                    }
                    if (state.fileLoaded) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Icons.Filled.FastRewind, "Back ${step.toInt()} seconds") { seekBy(-step) }
                            RoundButton(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "Play" else "Pause", big = true, modifier = Modifier.testTag("play_pause")) { togglePause() }
                            RoundButton(Icons.Filled.FastForward, "Forward ${step.toInt()} seconds") { seekBy(step) }
                        }
                    }
                    BottomControls(
                        container = container,
                        inRoom = inRoom,
                        ready = room.isReady == true,
                        onInteract = ::touch,
                        onSheet = ::openSheet,
                        modifier = Modifier.align(Alignment.BottomCenter).hoverable(barsHover),
                    )
                }
            }
        }
        RoomSidePanel(container)
    }

    sheet?.let { kind ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Surface,
            modifier = Modifier.testTag("player_sheet").exposeTestTags(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (kind) {
                    PlayerSheet.PLAYLIST -> PlaylistContent(container, onBrowse = { sheet = null; nav.popTo(Screen.Home) })
                    PlayerSheet.ROOM -> RoomUsersContent(container)
                    PlayerSheet.CHAT -> ChatContent(container, maxHeight = 220.dp)
                    PlayerSheet.AUDIO -> TracksContent(container, TrackType.AUDIO, onPicked = { sheet = null })
                    PlayerSheet.SUBTITLES -> TracksContent(container, TrackType.SUBTITLE, onPicked = { sheet = null })
                }
            }
        }
    }
}

@Composable
private fun BottomControls(
    container: AppContainer,
    inRoom: Boolean,
    ready: Boolean,
    onInteract: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by container.player.state.collectAsStateWithLifecycle()
    var position by remember { mutableDoubleStateOf(container.player.currentPosition()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = container.player.currentPosition()
            delay(250)
        }
    }
    val duration = state.duration
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(dragging?.toDouble() ?: position), color = AppColors.Text, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = (dragging ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(0.01f)),
                onValueChange = { dragging = it; onInteract() },
                onValueChangeFinished = {
                    dragging?.let { container.player.seek(it.toDouble()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
                enabled = duration > 0,
                colors = SliderDefaults.colors(thumbColor = AppColors.Accent, activeTrackColor = AppColors.Accent),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("seek_slider"),
            )
            Text(formatClock(duration), color = AppColors.Text, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (inRoom) ReadyChip(ready, { container.sync.toggleReady(); onInteract() }, Modifier.testTag("player_ready"))
            Spacer(Modifier.weight(1f))
            SheetButton(Icons.AutoMirrored.Filled.PlaylistPlay, "Shared playlist") { onSheet(PlayerSheet.PLAYLIST) }
            SheetButton(Icons.Filled.Groups, "Room") { onSheet(PlayerSheet.ROOM) }
            SheetButton(Icons.Filled.Forum, "Chat") { onSheet(PlayerSheet.CHAT) }
            SheetButton(Icons.Filled.Audiotrack, "Audio") { onSheet(PlayerSheet.AUDIO) }
            SheetButton(Icons.Filled.Subtitles, "Subtitles") { onSheet(PlayerSheet.SUBTITLES) }
        }
    }
}

@Composable
private fun SheetButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.testTag("sheet_$label")) { Icon(icon, contentDescription = label, tint = Color.White) }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f), modifier = modifier.size(if (big) 72.dp else 56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(if (big) 44.dp else 32.dp))
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text("  $text", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun RoomStatusChip(room: RoomState) {
    when (room.status) {
        ConnectionStatus.CONNECTED -> {
            val ready = room.users.count { it.isReady == true }
            Chip("${room.room} · ${room.users.size} · $ready ready", AppColors.Ready)
        }
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Chip("Connecting…", AppColors.NotReady)
        ConnectionStatus.DISCONNECTED -> Chip("Not in a room", AppColors.TextDim)
    }
}

@Composable
private fun PlayerToasts(container: AppContainer, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<List<Pair<Long, FeedMessage>>>(emptyList()) }
    LaunchedEffect(Unit) {
        container.sync.toasts.collect { msg ->
            val id = System.nanoTime()
            shown = (shown + (id to msg)).takeLast(3)
        }
    }
    LaunchedEffect(shown) {
        if (shown.isNotEmpty()) {
            delay(4000)
            shown = shown.drop(1)
        }
    }
    Column(modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (_, msg) ->
            Text(
                (msg.from?.let { "$it: " } ?: "") + msg.text,
                color = if (msg.isError) AppColors.Error else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CenterStatus(container: AppContainer, nav: Navigator, status: PlaylistStatus, nothingLoaded: Boolean, fileLoaded: Boolean, inRoom: Boolean) {
    val pickFile = rememberVideoPicker { uri -> container.playlist.resolveManuallyLocal(uri) }
    val playFile = rememberVideoPicker { uri -> container.playlist.playLocal(uri, inRoom) }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            is PlaylistStatus.Resolving -> StatusBox("Looking for the file…", status.fileName)
            is PlaylistStatus.Loading -> if (!fileLoaded) StatusBox("Loading…", status.fileName)
            is PlaylistStatus.NotFound, is PlaylistStatus.Failed -> {
                val (file, reason) = when (status) {
                    is PlaylistStatus.NotFound -> status.fileName to status.reason
                    is PlaylistStatus.Failed -> status.fileName to status.reason
                    else -> "" to ""
                }
                Surface(color = AppColors.Surface.copy(alpha = 0.95f), shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp).testTag("not_found")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (status is PlaylistStatus.NotFound) "Couldn't find this file" else "Couldn't play this file", style = MaterialTheme.typography.titleLarge)
                        Text(file, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(reason, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (status is PlaylistStatus.NotFound) {
                                Button(pickFile, Modifier.testTag("pick_file")) { Text("Pick file") }
                                if (container.mediaSource.value != null) {
                                    FilledTonalButton({ nav.push(Screen.Search(pickFor = file)) }) { Text("Search servers") }
                                }
                            }
                            TextButton({ container.playlist.dismissStatus() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            is PlaylistStatus.Untrusted -> {
                Surface(color = AppColors.Surface.copy(alpha = 0.95f), shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp).testTag("untrusted_url")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Open this link?", style = MaterialTheme.typography.titleLarge)
                        Text(status.url, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("The room picked it, but it isn't on one of your trusted domains.", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button({ container.playlist.playUntrusted() }) { Text("Open") }
                            status.domain?.let { domain ->
                                FilledTonalButton({
                                    val s = container.settings.value
                                    container.scope.launch { container.settingsStore.saveTrustedDomains(s.trustedDomains + domain, s.onlySwitchToTrustedDomains) }
                                    container.playlist.playUntrusted()
                                }) { Text("Always trust $domain", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            TextButton({ container.playlist.dismissStatus() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            PlaylistStatus.Idle -> if (nothingLoaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nothing playing", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (inRoom) "Pick something, or wait for someone in the room to choose a file." else "Pick a video to watch.",
                        color = AppColors.TextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(playFile) { Text("Open a video") }
                        FilledTonalButton({ nav.popTo(Screen.Home) }) { Text("Browse") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBox(title: String, detail: String) {
    Surface(color = AppColors.Surface.copy(alpha = 0.9f), shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
