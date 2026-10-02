package com.syncplaytv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.syncplaytv.AppContainer
import com.syncplaytv.player.SurfacePlayer
import com.syncplaytv.player.TrackType
import com.syncplaytv.syncplay.ConnectionStatus
import com.syncplaytv.sync.PlaylistStatus
import com.syncplaytv.ui.nav.Navigator
import com.syncplaytv.ui.nav.Screen
import com.syncplaytv.ui.components.ActionButton
import com.syncplaytv.ui.components.IconAction
import com.syncplaytv.ui.components.Panel
import com.syncplaytv.ui.components.Pill
import com.syncplaytv.ui.components.ToastHost
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay

internal enum class PanelKind { PLAYLIST, ROOM, CHAT, AUDIO, SUBTITLES }

internal sealed interface Overlay {
    data object Hidden : Overlay
    data object Controls : Overlay
    data class Side(val kind: PanelKind) : Overlay
}

@Composable
fun PlayerScreen(container: AppContainer, nav: Navigator) {
    val player = container.player as SurfacePlayer
    val state by player.state.collectAsStateWithLifecycle()
    val tracks by player.tracks.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val status by container.playlist.status.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf<Overlay>(Overlay.Hidden) }
    var interaction by remember { mutableLongStateOf(0L) }
    var seekFlash by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val step = settings.playback.seekStepSeconds.toDouble()
    val inRoom = room.status == ConnectionStatus.CONNECTED

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(overlay) {
        delay(30)
        runCatching {
            when (overlay) {
                Overlay.Hidden -> rootFocus.requestFocus()
                Overlay.Controls -> controlsFocus.requestFocus()
                is Overlay.Side -> panelFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(overlay, interaction) {
        if (overlay == Overlay.Controls && !state.paused) {
            delay(6000)
            overlay = Overlay.Hidden
        }
    }

    BackHandler {
        if (overlay != Overlay.Hidden) overlay = Overlay.Hidden else nav.back()
    }

    fun togglePause() = player.setPaused(!player.isPaused)
    fun seekBy(delta: Double) {
        player.seekRelative(delta)
        seekFlash = System.currentTimeMillis()
    }
    fun cycleSubtitles() {
        val subs = tracks.filter { it.type == TrackType.SUBTITLE }
        if (subs.isEmpty()) return
        val current = subs.indexOfFirst { it.selected }
        val next = if (current + 1 >= subs.size) null else subs[current + 1]
        player.selectTrack(TrackType.SUBTITLE, next?.id)
        player.showText("Subtitles: ${next?.label ?: "off"}")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                interaction = System.currentTimeMillis()
                val down = ev.type == KeyEventType.KeyDown
                val code = ev.key.nativeKeyCode
                val global = when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { if (down) togglePause(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.setPaused(false); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.setPaused(true); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(30.0); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-30.0); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (down) room.playlistIndex?.let { container.playlist.selectIndex(it + 1) }; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down) room.playlistIndex?.takeIf { it > 0 }?.let { container.playlist.selectIndex(it - 1) }; true }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { if (down) cycleSubtitles(); true }
                    else -> false
                }
                if (global) return@onPreviewKeyEvent true
                if (overlay != Overlay.Hidden) return@onPreviewKeyEvent false
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> { if (down) overlay = Overlay.Controls; true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down) overlay = Overlay.Side(PanelKind.ROOM); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) seekBy(-step); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) seekBy(step); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(player.surfaceCallback) } },
            onRelease = { it.holder.removeCallback(player.surfaceCallback) },
            modifier = Modifier.fillMaxSize(),
        )

        // Status in the middle of the screen (resolving/loading/nothing playing).
        CenterStatus(container, nav, status, nowPlaying == null, state.fileLoaded, inRoom, overlay == Overlay.Hidden)

        if (state.buffering && state.fileLoaded) {
            Pill("Buffering… %.0fs cached".format(state.cacheSeconds), AppColors.NotReady, Modifier.align(Alignment.TopCenter).padding(24.dp))
        }

        ToastHost(container.sync.toasts, Modifier.align(Alignment.TopStart).padding(32.dp))

        val flashVisible = remember(seekFlash) { mutableStateOf(seekFlash != 0L) }
        LaunchedEffect(seekFlash) { delay(1800); flashVisible.value = false }
        if (overlay == Overlay.Hidden && flashVisible.value && state.duration > 0) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                ProgressBar(player.currentPosition(), state.duration, state.cacheSeconds)
                Text("${formatClock(player.currentPosition())} / ${formatClock(state.duration)}", modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (overlay == Overlay.Controls || overlay is Overlay.Side) {
            ControlsBar(
                container = container,
                title = nowPlaying?.title ?: "Nothing playing",
                controlsFocus = controlsFocus,
                onTogglePause = ::togglePause,
                onSeek = ::seekBy,
                onOpenPanel = { overlay = Overlay.Side(it) },
                onHome = { nav.popTo(Screen.Home) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            RoomChip(room, Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        (overlay as? Overlay.Side)?.let { side ->
            Panel(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(560.dp).padding(vertical = 24.dp, horizontal = 24.dp)) {
                when (side.kind) {
                    PanelKind.PLAYLIST -> PlaylistPanel(container, nav, panelFocus)
                    PanelKind.ROOM -> RoomPanel(container, nav, panelFocus)
                    PanelKind.CHAT -> ChatPanel(container, panelFocus)
                    PanelKind.AUDIO -> TracksPanel(container, TrackType.AUDIO, panelFocus)
                    PanelKind.SUBTITLES -> TracksPanel(container, TrackType.SUBTITLE, panelFocus)
                }
            }
        }
    }
}

@Composable
private fun CenterStatus(
    container: AppContainer,
    nav: Navigator,
    status: PlaylistStatus,
    nothingLoaded: Boolean,
    fileLoaded: Boolean,
    inRoom: Boolean,
    canFocus: Boolean,
) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (status) {
            is PlaylistStatus.Resolving -> StatusCard("Finding in Jellyfin…", status.fileName)
            is PlaylistStatus.Loading -> if (!fileLoaded) StatusCard("Loading…", status.fileName)
            is PlaylistStatus.NotFound, is PlaylistStatus.Failed -> {
                val (file, reason) = when (status) {
                    is PlaylistStatus.NotFound -> status.fileName to status.reason
                    is PlaylistStatus.Failed -> status.fileName to status.reason
                    else -> "" to ""
                }
                LaunchedEffect(status, canFocus) { if (canFocus) runCatching { delay(50); focus.requestFocus() } }
                Panel(Modifier.width(820.dp)) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (status is PlaylistStatus.NotFound) "Couldn't find this file" else "Couldn't play this file", style = MaterialTheme.typography.headlineSmall)
                        Text(file, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(reason, color = AppColors.TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                            if (container.mediaSource.value != null) {
                                ActionButton("Pick manually", { nav.push(Screen.Search(pickFor = file)) }, Modifier.focusRequester(focus), icon = Icons.Filled.Search, primary = true)
                            } else {
                                ActionButton("Connect Jellyfin", { nav.push(Screen.JellyfinLogin) }, Modifier.focusRequester(focus), primary = true)
                            }
                            ActionButton("Dismiss", { container.playlist.dismissStatus() })
                        }
                    }
                }
            }
            is PlaylistStatus.Untrusted -> {
                LaunchedEffect(status, canFocus) { if (canFocus) runCatching { delay(50); focus.requestFocus() } }
                Panel(Modifier.width(820.dp)) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Open this link?", style = MaterialTheme.typography.headlineSmall)
                        Text(status.url, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("The room picked it, but it isn't on one of your trusted domains.", color = AppColors.TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                            ActionButton("Open", { container.playlist.playUntrusted() }, Modifier.focusRequester(focus), primary = true)
                            ActionButton("Dismiss", { container.playlist.dismissStatus() })
                        }
                    }
                }
            }
            PlaylistStatus.Idle -> if (nothingLoaded) {
                LaunchedEffect(canFocus) { if (canFocus) runCatching { delay(50); focus.requestFocus() } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Nothing playing", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (inRoom) "Pick something from Jellyfin, or wait for someone in the room to choose a file." else "Pick something from Jellyfin to watch.",
                        color = AppColors.TextDim,
                    )
                    ActionButton("Browse", { nav.popTo(Screen.Home) }, Modifier.focusRequester(focus), icon = Icons.Filled.Home, primary = true)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String) {
    Panel(Modifier.width(820.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ProgressBar(position: Double, duration: Double, cacheSeconds: Double, modifier: Modifier = Modifier) {
    val fraction = if (duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val cached = if (duration > 0) ((position + cacheSeconds) / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(cached).fillMaxHeight().background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent))
    }
}

@Composable
private fun ControlsBar(
    container: AppContainer,
    title: String,
    controlsFocus: FocusRequester,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onOpenPanel: (PanelKind) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    // Re-read the extrapolated position a few times a second while visible.
    var position by remember { mutableStateOf(container.player.currentPosition()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = container.player.currentPosition()
            delay(250)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = 56.dp, vertical = 32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        ProgressBar(position, state.duration, state.cacheSeconds)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(formatClock(position), color = AppColors.TextDim)
            Spacer(Modifier.weight(1f))
            val info = listOfNotNull(
                state.videoCodec?.substringBefore(' ')?.uppercase(),
                if (state.videoHeight > 0) "${state.videoHeight}p" else null,
                state.hwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { "HW" } ?: if (state.fileLoaded) "SW" else null,
                if (state.speed != 1.0) "%.2fx".format(state.speed) else null,
            ).joinToString(" · ")
            Text(info, color = AppColors.TextDim)
            Spacer(Modifier.width(24.dp))
            Text(formatClock(state.duration), color = AppColors.TextDim)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Play/pause", onTogglePause, Modifier.focusRequester(controlsFocus))
            IconAction(Icons.Filled.Replay10, "Back", { onSeek(-10.0) })
            IconAction(Icons.Filled.Forward10, "Forward", { onSeek(10.0) })
            if (inRoom) {
                val ready = room.isReady == true
                ActionButton(
                    if (ready) "Ready" else "Not ready",
                    { container.sync.toggleReady() },
                    icon = if (ready) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    tint = if (ready) AppColors.Ready else AppColors.NotReady,
                )
            }
            Spacer(Modifier.weight(1f))
            IconAction(Icons.AutoMirrored.Filled.PlaylistPlay, "Shared playlist", { onOpenPanel(PanelKind.PLAYLIST) })
            IconAction(Icons.Filled.Groups, "Room", { onOpenPanel(PanelKind.ROOM) })
            IconAction(Icons.Filled.Forum, "Chat", { onOpenPanel(PanelKind.CHAT) })
            IconAction(Icons.Filled.Audiotrack, "Audio", { onOpenPanel(PanelKind.AUDIO) })
            IconAction(Icons.Filled.Subtitles, "Subtitles", { onOpenPanel(PanelKind.SUBTITLES) })
            IconAction(Icons.Filled.Home, "Browse", onHome)
        }
    }
}

@Composable
private fun RoomChip(room: com.syncplaytv.syncplay.RoomState, modifier: Modifier = Modifier) {
    val text: String
    val color: Color
    when (room.status) {
        ConnectionStatus.CONNECTED -> {
            val ready = room.users.count { it.isReady == true }
            text = "${room.room} · ${room.users.size} watching · $ready ready" + if (room.rttMillis > 0) " · %.0f ms".format(room.rttMillis) else ""
            color = AppColors.Ready
        }
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> { text = "Connecting to Syncplay…"; color = AppColors.NotReady }
        ConnectionStatus.DISCONNECTED -> { text = "Not in a room"; color = AppColors.TextDim }
    }
    Pill(text, color, modifier)
}
