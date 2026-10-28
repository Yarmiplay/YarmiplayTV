package com.yarmiplaytv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.os.SystemClock
import com.yarmiplaytv.voice.TvVoiceKeys
import com.yarmiplaytv.voice.micControlLive
import com.yarmiplaytv.voice.readyControlDescription
import com.yarmiplaytv.voice.voiceMembershipDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.SurfacePlayer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.relay.RelayHint
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.tracking.SkipService
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Dot
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.components.Panel
import com.yarmiplaytv.ui.components.ToastHost
import com.yarmiplaytv.ui.mal.MalPanel
import com.yarmiplaytv.ui.mobile.PictureInPicture
import com.yarmiplaytv.ui.mobile.RelayDownloadBar
import com.yarmiplaytv.ui.mobile.SkipChip
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.playback.playbackAction
import com.yarmiplaytv.ui.mobile.SkipMarks
import com.yarmiplaytv.ui.mobile.LocalTvPlaybackControls
import com.yarmiplaytv.ui.mobile.pictureInPictureLabel
import com.yarmiplaytv.ui.mobile.rememberPictureInPicture
import com.yarmiplaytv.ui.mobile.rememberRelaySave
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.OnVideo
import com.yarmiplaytv.ui.theme.onVideoLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

internal enum class PanelKind(val label: String, val icon: ImageVector) {
    PLAYLIST("Shared playlist", Icons.AutoMirrored.Filled.PlaylistPlay),
    ROOM("Room", Icons.Filled.Groups),
    CHAT("Chat", Icons.Filled.Forum),
    AUDIO("Audio", Icons.Filled.Audiotrack),
    SUBTITLES("Subtitles", Icons.Filled.Subtitles),
    VIDEO("Video", Icons.Filled.Tune),
    MAL("MyAnimeList", Icons.Filled.Bookmarks),
}

internal sealed interface Overlay {
    data object Hidden : Overlay
    data object Controls : Overlay
    /** [focusContent]: the panel's content takes the focus; false when its tab was moved to, which keeps it. */
    data class Side(val kind: PanelKind, val focusContent: Boolean = true) : Overlay
}

@Composable
fun PlayerScreen(container: AppContainer, nav: Navigator) {
    val player = container.player as SurfacePlayer
    val state by player.state.collectAsStateWithLifecycle()
    val tracks by player.tracks.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val skipPrompt by container.skips.prompt.collectAsStateWithLifecycle()
    val skipSegments by container.skips.segments.collectAsStateWithLifecycle()
    val status by container.playlist.status.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val baseDensity = LocalDensity.current
    val playerScale = settings.playback.tvPlayerScale.coerceIn(0.5f, 1.5f)
    val scaledDensity = remember(baseDensity, playerScale) {
        Density(density = baseDensity.density * playerScale, fontScale = baseDensity.fontScale)
    }

    var overlay by remember { mutableStateOf<Overlay>(Overlay.Hidden) }
    var interaction by remember { mutableLongStateOf(0L) }
    var seekFlash by remember { mutableLongStateOf(0L) }
    var okDownAt by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val voiceUi by container.voice.ui.collectAsStateWithLifecycle()
    val tabFocus = remember { PanelKind.entries.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val panels = PanelKind.entries.filter { it != PanelKind.MAL || container.mal.client.configured }
    // Up opens the panel used last.
    var lastPanel by remember { mutableStateOf(PanelKind.ROOM) }
    fun openPanel(kind: PanelKind, focusContent: Boolean = true) {
        lastPanel = kind
        overlay = Overlay.Side(kind, focusContent)
    }
    val step = settings.playback.seekStepSeconds.toDouble()
    val hideAfter = settings.playback.controlsHideSeconds
    val inRoom = room.status == ConnectionStatus.CONNECTED
    val pip = rememberPictureInPicture(player, settings.playback.pictureInPicture, inRoom)
    LaunchedEffect(pip.active) { if (pip.active) overlay = Overlay.Hidden }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(overlay) {
        // A superseded run can still wake up after the overlay changed, before it's cancelled.
        val target = overlay
        delay(30)
        if (overlay != target) return@LaunchedEffect
        runCatching {
            when (target) {
                Overlay.Hidden -> rootFocus.requestFocus()
                Overlay.Controls -> controlsFocus.requestFocus()
                // A panel with nothing to pick (no tracks, say) gives its tab the focus.
                is Overlay.Side -> if (target.focusContent && !runCatching { panelFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                    tabFocus.getValue(target.kind).requestFocus()
                }
            }
        }
    }
    LaunchedEffect(overlay, interaction, hideAfter) {
        if (overlay == Overlay.Controls && !state.paused && hideAfter > 0) {
            delay(hideAfter * 1000L)
            overlay = Overlay.Hidden
        }
    }

    // Back closes the controls or a panel, straight back to the video. A status card with Dismiss closes that first.
    BackHandler {
        when {
            overlay != Overlay.Hidden -> overlay = Overlay.Hidden
            backDismissesStatusCard(status) -> container.playlist.dismissStatus()
            else -> nav.back()
        }
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
                val up = ev.type == KeyEventType.KeyUp
                val code = ev.key.nativeKeyCode
                val firstPress = down && ev.nativeKeyEvent.repeatCount == 0
                if (voiceUi.showMicButton && TvVoiceKeys.isVoiceHoldKey(code)) {
                    if (firstPress) {
                        container.voice.noteRemoteToggleKey(TvVoiceKeys.labelFor(code))
                        container.voice.setTalkLatched(true)
                    }
                    if (up) container.voice.setTalkLatched(false)
                    return@onPreviewKeyEvent true
                }
                val global = when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { if (down) togglePause(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.setPaused(false); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.setPaused(true); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(30.0); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-30.0); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (down) container.playlist.skip(true); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down) container.playlist.skip(false); true }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { if (down) cycleSubtitles(); true }
                    else -> false
                }
                if (global) return@onPreviewKeyEvent true
                // A keyboard uses mpv's keys. The D-pad, OK, and Menu stay the remote's own controls.
                val cardOwnsRemote = statusCardTakesRemote(status, nowPlaying == null, state.fileLoaded)
                val remoteNav = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
                    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_MENU ||
                    code == AndroidKeyEvent.KEYCODE_BACK
                // The file-not-found card (and the other status cards) has real buttons. Let the remote move
                // between them and press OK; seeking or opening the bar here never reached Dismiss.
                if (cardOwnsRemote && overlay == Overlay.Hidden && remoteNav) return@onPreviewKeyEvent false
                if (!remoteNav && (overlay == Overlay.Hidden || overlay == Overlay.Controls)) {
                    val action = playbackAction(ev.key, shift = ev.isShiftPressed, ctrl = ev.isCtrlPressed, alt = ev.isAltPressed)
                    if (action != null) {
                        val first = ev.nativeKeyEvent.repeatCount == 0
                        if (down && (action.repeats || first)) {
                            when (action) {
                                PlaybackAction.SkipSegment -> if (container.skips.prompt.value != null) container.skips.skip()
                                PlaybackAction.FocusChat -> openPanel(PanelKind.CHAT)
                                PlaybackAction.TogglePip -> pip.toggle()
                                PlaybackAction.ToggleControls -> overlay = if (overlay == Overlay.Hidden) Overlay.Controls else Overlay.Hidden
                                PlaybackAction.Fullscreen -> Unit
                                else -> container.playback.perform(action)
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // Menu shows and hides the controls, and closes a panel.
                if (overlay != Overlay.Hidden && code == AndroidKeyEvent.KEYCODE_MENU) {
                    if (firstPress) overlay = Overlay.Hidden
                    return@onPreviewKeyEvent true
                }
                if (overlay == Overlay.Controls) {
                    // Past the bar's buttons, Down hides it and Up opens the panel used last.
                    val past = when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                        else -> return@onPreviewKeyEvent false
                    }
                    if (down && !focusManager.moveFocus(past) && firstPress) {
                        if (past == FocusDirection.Down) overlay = Overlay.Hidden else openPanel(lastPanel)
                    }
                    return@onPreviewKeyEvent true
                }
                if (overlay != Overlay.Hidden) return@onPreviewKeyEvent false
                // While the Skip button shows, OK presses it instead of opening the controls.
                val ok = TvVoiceKeys.isOkKey(code)
                if (ok && skipPrompt != null) {
                    if (firstPress) container.skips.skip()
                    return@onPreviewKeyEvent true
                }
                // Long-press OK = mic toggle shortcut; short press opens controls. Prefer the Mic button.
                if (ok && voiceUi.showMicButton) {
                    if (firstPress) {
                        okDownAt = SystemClock.uptimeMillis()
                        return@onPreviewKeyEvent true
                    }
                    if (down) return@onPreviewKeyEvent true
                    if (up) {
                        val held = SystemClock.uptimeMillis() - okDownAt
                        okDownAt = 0L
                        if (held >= TvVoiceKeys.OK_LONG_PRESS_MS) {
                            container.voice.noteRemoteToggleKey("OK (long-press)")
                            container.voice.toggleTalk()
                        } else {
                            overlay = Overlay.Controls
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> { if (firstPress) overlay = Overlay.Controls; true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (firstPress) openPanel(lastPanel); true }
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
        // Picture-in-picture shows only the video; the system draws the window's controls.
        if (pip.active) return@Box

        CompositionLocalProvider(LocalDensity provides scaledDensity, LocalTvPlaybackControls provides true) {
            Box(Modifier.fillMaxSize()) {
                if (state.fileLoaded && FileNames.isAudio(nowPlaying?.fileName ?: state.url.orEmpty())) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier.size(140.dp).clip(RoundedCornerShape(24.dp)).background(AppColors.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Audiotrack, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(72.dp))
                        }
                        Text(nowPlaying?.title ?: FileNames.baseName(state.url.orEmpty()), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        nowPlaying?.episode?.seriesName?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }

                // Status in the middle of the screen (resolving/loading/nothing playing).
                CenterStatus(container, nav, status, nowPlaying == null, state.fileLoaded, inRoom, overlay == Overlay.Hidden)
                RelayOnPlayer(container, state.fileLoaded, overlay != Overlay.Hidden)

                if (state.buffering && state.fileLoaded) {
                    VideoChip("Buffering… %.0fs cached".format(state.cacheSeconds), AppColors.NotReady, Modifier.align(Alignment.TopCenter).padding(24.dp))
                }

                val toasts = remember(container) { container.sync.toasts.filter { it.showsOnVideo(container.settings.value.chatOnVideo) } }
                ToastHost(toasts, Modifier.align(Alignment.TopStart).padding(32.dp))
                TvMalNotice(container, Modifier.align(Alignment.TopEnd).padding(32.dp))
                // Mic live sits on RoomChip (status cluster) while HUD is up — no video overlay badge.
                if (voiceUi.showMicHint) {
                    LaunchedEffect(voiceUi.showMicHint) {
                        delay(12_000)
                        container.voice.dismissMicHint()
                    }
                    TvMicHint(voiceUi, Modifier.align(Alignment.BottomCenter).padding(horizontal = 48.dp).padding(bottom = 120.dp))
                }
                // One place, controls or not: just above the controls bar, so it never jumps. OK presses it while the
                // controls are hidden; with them open, OK belongs to the focused control.
                if (overlay == Overlay.Hidden || overlay == Overlay.Controls) {
                    skipPrompt?.let {
                        SkipChip(
                            it.label,
                            key = it,
                            hint = if (overlay == Overlay.Hidden) "OK" else null,
                            large = true,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = tvSkipBottom()).testTag("skip_segment"),
                        )
                    }
                }

                val flashVisible = remember(seekFlash) { mutableStateOf(seekFlash != 0L) }
                LaunchedEffect(seekFlash) { delay(1800); flashVisible.value = false }
                if (overlay == Overlay.Hidden && flashVisible.value && state.duration > 0) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                        ProgressBar(player.currentPosition(), state.duration, state.cacheSeconds, segments = skipSegments)
                        Text("${formatClock(player.currentPosition())} / ${formatClock(state.duration)}", color = OnVideo.Text, modifier = Modifier.padding(top = 8.dp).onVideoLabel())
                    }
                }

                if (overlay == Overlay.Controls) {
                    ControlsBar(
                        container = container,
                        title = nowPlaying?.title ?: "Nothing playing",
                        controlsFocus = controlsFocus,
                        micFocus = micFocus,
                        voiceUi = voiceUi,
                        pip = pip,
                        onTogglePause = ::togglePause,
                        onSeek = ::seekBy,
                        onOpenMenus = { openPanel(lastPanel) },
                        onHome = { nav.popTo(Screen.Home) },
                        modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { tvControlsHeight = with(scaledDensity) { it.height.toDp() } },
                    )
                    RoomChip(
                        room,
                        playerStreamingLabel(container, controlsVisible = true, state.fileLoaded),
                        Modifier.align(Alignment.TopEnd).padding(32.dp),
                        micLive = voiceUi.micControlLive(),
                    )
                }

                (overlay as? Overlay.Side)?.let { side ->
                    // The panel keeps the focus, so nothing behind it can be reached by mistake.
                    Panel(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(560.dp).padding(vertical = 24.dp, horizontal = 24.dp)
                            .focusProperties { onExit = { cancelFocusChange() } }
                            .focusGroup(),
                    ) {
                        Column {
                            PanelTabs(panels, side.kind, tabFocus, onFocusTab = { if (it != side.kind) openPanel(it, focusContent = false) }, onClickTab = { openPanel(it) })
                            Box(Modifier.weight(1f)) {
                                when (side.kind) {
                                    PanelKind.PLAYLIST -> PlaylistPanel(container, nav, panelFocus)
                                    PanelKind.ROOM -> RoomPanel(container, nav, panelFocus)
                                    PanelKind.CHAT -> ChatPanel(container, panelFocus)
                                    PanelKind.AUDIO -> TracksPanel(container, TrackType.AUDIO, panelFocus)
                                    PanelKind.SUBTITLES -> TracksPanel(container, TrackType.SUBTITLE, panelFocus)
                                    PanelKind.VIDEO -> VideoPanel(container, panelFocus)
                                    PanelKind.MAL -> MalPanel(container, panelFocus)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.RelayOnPlayer(container: AppContainer, fileLoaded: Boolean, controlsVisible: Boolean) {
    val relay = container.relay ?: return
    val relayStatus by relay.status.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val status = relayStatus ?: return
    when {
        status.streamingLineVisible(controlsVisible, fileLoaded) && !controlsVisible -> StreamingChip(
            status.text.withPing(room.rttMillis),
            Modifier.align(Alignment.TopCenter).padding(top = 32.dp).testTag("relay_streaming"),
        )
        status.playbackHint(fileLoaded) == RelayHint.Downloading -> Panel(Modifier.align(Alignment.Center).width(820.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.text, style = MaterialTheme.typography.titleLarge)
                RelayDownloadBar(status.fraction)
            }
        }
    }
}

/** Green dot plus the short streaming line, the same chip as the room ping. */
@Composable
private fun StreamingChip(text: String, modifier: Modifier = Modifier) = VideoChip(text, AppColors.Ready, modifier)

/** A status on the video: a [dot] of colour and white text on see-through black, readable on any picture and theme. */
@Composable
private fun VideoChip(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(OnVideo.Background).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Text(text, color = OnVideo.Text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun playerStreamingLabel(container: AppContainer, controlsVisible: Boolean, fileLoaded: Boolean): String? {
    val relay = container.relay ?: return null
    val status by relay.status.collectAsStateWithLifecycle()
    val current = status ?: return null
    return current.text.takeIf { current.streamingLineVisible(controlsVisible, fileLoaded) }
}

private fun String.withPing(rttMillis: Double): String =
    if (rttMillis > 0) "$this · %.0f ms".format(rttMillis) else this

/**
 * While this is true and the controls are hidden, D-pad and OK belong to the card in the middle of the video
 * instead of seeking or opening the bar.
 */
internal fun statusCardTakesRemote(status: PlaylistStatus, nothingLoaded: Boolean, fileLoaded: Boolean): Boolean = when (status) {
    is PlaylistStatus.NotFound, is PlaylistStatus.Failed, is PlaylistStatus.Untrusted -> true
    PlaylistStatus.Idle -> nothingLoaded && !fileLoaded
    else -> false
}

/** Back closes these cards. "Nothing playing" has no status to dismiss, so Back leaves the player. */
internal fun backDismissesStatusCard(status: PlaylistStatus): Boolean =
    status is PlaylistStatus.NotFound || status is PlaylistStatus.Failed || status is PlaylistStatus.Untrusted

/** The card's buttons, kept together so the remote can't walk off them onto the video. */
@Composable
private fun StatusActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        content = content,
    )
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
    val hideLoading = container.relay?.let { relay ->
        val relayStatus by relay.status.collectAsStateWithLifecycle()
        relayStatus.let { it != null && it.playbackHint(fileLoaded) != RelayHint.None }
    } == true
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (status) {
            is PlaylistStatus.Resolving -> StatusCard("Finding the file…", status.fileName)
            is PlaylistStatus.Loading -> if (!fileLoaded && !hideLoading) StatusCard("Loading…", status.fileName)
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
                        StatusActions(Modifier.padding(top = 8.dp)) {
                            if (container.mediaSource.value != null) {
                                ActionButton("Pick manually", { nav.push(Screen.Search(pickFor = file)) }, icon = Icons.Filled.Search, primary = true)
                            } else {
                                ActionButton("Connect Jellyfin", { nav.push(Screen.JellyfinLogin) }, primary = true)
                                ActionButton("Connect Plex", { nav.push(Screen.PlexLogin) })
                            }
                            ActionButton("Dismiss", { container.playlist.dismissStatus() }, Modifier.focusRequester(focus))
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
                        StatusActions(Modifier.padding(top = 8.dp)) {
                            ActionButton("Open", { container.playlist.playUntrusted() }, primary = true)
                            ActionButton("Dismiss", { container.playlist.dismissStatus() }, Modifier.focusRequester(focus))
                        }
                    }
                }
            }
            PlaylistStatus.Idle -> if (nothingLoaded) {
                LaunchedEffect(canFocus) { if (canFocus) runCatching { delay(50); focus.requestFocus() } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Nothing playing", color = OnVideo.Text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.onVideoLabel())
                    Text(
                        if (inRoom) "Pick something to watch, or wait for someone in the room to choose a file." else "Pick something to watch.",
                        color = OnVideo.Text,
                        modifier = Modifier.onVideoLabel(),
                    )
                    StatusActions {
                        ActionButton("Browse", { nav.popTo(Screen.Home) }, Modifier.focusRequester(focus), icon = Icons.Filled.Home, primary = true)
                        ActionButton("Exit", { container.exitApp() }, icon = Icons.AutoMirrored.Filled.Logout)
                    }
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
internal fun ProgressBar(position: Double, duration: Double, cacheSeconds: Double, modifier: Modifier = Modifier, segments: List<SkipSegment> = emptyList()) {
    val fraction = if (duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val cached = if (duration > 0) ((position + cacheSeconds) / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(cached).fillMaxHeight().background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent))
        SkipMarks(segments, duration, Modifier.matchParentSize())
    }
}

@Composable
private fun ControlsBar(
    container: AppContainer,
    title: String,
    controlsFocus: FocusRequester,
    micFocus: FocusRequester,
    voiceUi: com.yarmiplaytv.voice.VoiceUiState,
    pip: PictureInPicture,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    /** Opens the menu used last; its tabs switch to the others. */
    onOpenMenus: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = OnVideo.Text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(24.dp))
            Text("▲ Menus", style = MaterialTheme.typography.bodyMedium, color = OnVideo.Text, modifier = Modifier.onVideoLabel())
            Spacer(Modifier.width(16.dp))
            Text("▼ Hide", style = MaterialTheme.typography.bodyMedium, color = OnVideo.Text, modifier = Modifier.onVideoLabel())
        }
        Spacer(Modifier.height(12.dp))
        val segments by container.skips.segments.collectAsStateWithLifecycle()
        ProgressBar(position, state.duration, state.cacheSeconds, segments = segments)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(formatClock(position), color = OnVideo.Text, modifier = Modifier.onVideoLabel())
            Spacer(Modifier.weight(1f))
            playbackInfo(state).takeIf { it.isNotEmpty() }?.let { Text(it, color = OnVideo.Text, modifier = Modifier.onVideoLabel()) }
            Spacer(Modifier.width(24.dp))
            Text(formatClock(state.duration), color = OnVideo.Text, modifier = Modifier.onVideoLabel())
        }
        ButtonRow(Modifier.padding(top = 12.dp), start = {
            IconAction(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Play/pause", onTogglePause, Modifier.focusRequester(controlsFocus), tint = OnVideo.Text)
            IconAction(Icons.Filled.Replay10, "Back", { onSeek(-10.0) }, tint = OnVideo.Text)
            IconAction(Icons.Filled.Forward10, "Forward", { onSeek(10.0) }, tint = OnVideo.Text)
            // The room playlist's previous and next entry, when there is one to go to.
            if (inRoom && room.playlist.isNotEmpty()) {
                val previous = container.playlist.skipTarget(room, forward = false)
                val next = container.playlist.skipTarget(room, forward = true)
                IconAction(Icons.Filled.SkipPrevious, "Previous in the playlist", { container.playlist.skip(false) }, Modifier.testTag("skip_previous"), enabled = previous != null, tint = OnVideo.Text)
                IconAction(Icons.Filled.SkipNext, "Next in the playlist", { container.playlist.skip(true) }, Modifier.testTag("skip_next"), enabled = next != null, tint = OnVideo.Text)
            }
            if (inRoom) {
                val ready = room.isReady == true
                IconAction(
                    if (ready) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                    readyControlDescription(ready),
                    { container.sync.toggleReady() },
                    Modifier.testTag("player_ready"),
                    tint = if (ready) AppColors.Ready else AppColors.NotReady,
                )
            }
            // Every remote can focus this and press OK — primary TV talk control (tap toggle).
            TvMicButton(voiceUi, onToggle = { container.voice.toggleTalk() }, focusRequester = micFocus, onVideo = true)
            if (voiceUi.showVoiceMembershipButton) {
                val inVoice = voiceUi.voiceMembershipIsLeave
                IconAction(
                    if (inVoice) Icons.Filled.Headset else Icons.Filled.HeadsetOff,
                    voiceUi.voiceMembershipDescription(),
                    { if (inVoice) container.voice.leave() else container.voice.join() },
                    Modifier.testTag(if (inVoice) "player_voice_leave" else "player_voice_join"),
                    tint = if (inVoice) OnVideo.Text else OnVideo.Text.copy(alpha = 0.72f),
                )
            }
        }, end = {
            rememberRelaySave(container)?.let { save ->
                IconAction(
                    if (save.saving) Icons.Filled.Downloading else Icons.Filled.Download,
                    if (save.saving) "${save.label} (${(save.fraction * 100).toInt()}%)" else save.label,
                    save.onClick,
                    Modifier.testTag("relay_save"),
                    tint = if (save.saving) AppColors.Accent else OnVideo.Text,
                )
            }
            container.screenshots?.takeIf { state.fileLoaded }?.let { screenshots ->
                IconAction(Icons.Filled.PhotoCamera, "Screenshot", screenshots::take, Modifier.testTag("screenshot"), tint = OnVideo.Text)
            }
            if (pip.supported && state.fileLoaded) {
                IconAction(Icons.Filled.PictureInPictureAlt, pictureInPictureLabel, pip.toggle, Modifier.testTag("pip"), tint = OnVideo.Text)
            }
            // Icon-only like Browse/Exit — no pill/label; IconAction's accent focus ring for D-pad.
            IconAction(Icons.Filled.Menu, "Menus", onOpenMenus, Modifier.testTag("menus"), tint = OnVideo.Text)
            IconAction(Icons.Filled.Home, "Browse", onHome, tint = OnVideo.Text)
            IconAction(Icons.AutoMirrored.Filled.Logout, "Exit", { container.exitApp() }, Modifier.testTag("player_exit"), tint = OnVideo.Text)
        })
    }
}

/**
 * The bar's buttons: [start] at the left and [end] at the right. They fit on a 1080p TV; with larger text they follow
 * one another in a row that scrolls to whichever has the focus, with an arrow at each side that has more.
 */
@Composable
private fun ButtonRow(modifier: Modifier = Modifier, start: @Composable RowScope.() -> Unit, end: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        val spacing = Arrangement.spacedBy(10.dp)
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = SCALE_ROOM), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.widthIn(min = width - SCALE_ROOM * 2), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically, content = start)
                Row(Modifier.padding(start = 10.dp), horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically, content = end)
            }
        }
        if (scroll.canScrollBackward) MoreArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart).offset(x = -MORE_ARROW))
        if (scroll.canScrollForward) MoreArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd).offset(x = MORE_ARROW))
    }
}

/** Room for a focused button to grow at the row's ends. */
private val SCALE_ROOM = 4.dp
private val MORE_ARROW = 36.dp

@Composable
private fun MoreArrow(icon: ImageVector, modifier: Modifier) =
    Icon(icon, contentDescription = null, tint = OnVideo.Text, modifier = modifier.size(32.dp))

/**
 * The panels' tabs, at the top of the open one: moving along them switches panel, and OK goes into it. Coming up from
 * the panel, the focus lands on its own tab, not whichever is above.
 */
@Composable
private fun PanelTabs(
    panels: List<PanelKind>,
    selected: PanelKind,
    tabFocus: Map<PanelKind, FocusRequester>,
    onFocusTab: (PanelKind) -> Unit,
    onClickTab: (PanelKind) -> Unit,
) {
    Row(
        Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)
            .focusProperties { onEnter = { tabFocus.getValue(selected).requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        panels.forEach { kind ->
            val current = kind == selected
            Surface(
                onClick = { onClickTab(kind) },
                modifier = Modifier.size(48.dp).focusRequester(tabFocus.getValue(kind)).onFocusChanged { if (it.isFocused) onFocusTab(kind) }.testTag("panel_tab_${kind.name.lowercase()}"),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (current) AppColors.Accent.copy(alpha = 0.25f) else Color.Transparent,
                    contentColor = if (current) AppColors.Accent else AppColors.TextDim,
                    focusedContainerColor = AppColors.Accent,
                    focusedContentColor = AppColors.OnAccent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            ) {
                Icon(kind.icon, contentDescription = kind.label, modifier = Modifier.align(Alignment.Center).size(26.dp))
            }
        }
    }
}

@Composable
private fun RoomChip(
    room: com.yarmiplaytv.syncplay.RoomState,
    streaming: String? = null,
    modifier: Modifier = Modifier,
    micLive: Boolean = false,
) {
    val text: String
    val color: Color
    when (room.status) {
        ConnectionStatus.CONNECTED -> {
            val ready = room.users.count { it.isReady == true }
            val ping = if (room.rttMillis > 0) " · %.0f ms".format(room.rttMillis) else ""
            val mic = if (micLive) " · Mic" else ""
            text = "${ManagedRooms.label(room.room)} · ${room.users.size} watching · $ready ready$ping" +
                (streaming?.let { " · $it" } ?: "") + mic
            color = AppColors.Ready
        }
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> { text = "Connecting to Syncplay…"; color = AppColors.NotReady }
        ConnectionStatus.DISCONNECTED -> { text = "Not in a room"; color = OnVideo.Text.copy(alpha = 0.6f) }
    }
    val tag = when {
        streaming != null && room.status == ConnectionStatus.CONNECTED -> modifier.testTag("relay_streaming")
        micLive -> modifier.testTag("status_mic_live")
        else -> modifier
    }
    if (streaming != null && room.status == ConnectionStatus.CONNECTED) {
        StreamingChip(text, tag)
    } else {
        VideoChip(text, color, tag)
    }
}

/** The controls bar's height as last laid out, so the Skip button can sit just above it. */
private var tvControlsHeight by mutableStateOf(0.dp)

/**
 * Where the Skip button sits on TV: above the controls bar, whether it shows or not. The bar's height comes from
 * its rows (one line of title, the seek bar, the times, the buttons), or from its last layout if that's taller.
 */
@Composable
private fun tvSkipBottom(): Dp {
    val t = MaterialTheme.typography
    val estimate = with(LocalDensity.current) {
        val line = maxOf(t.bodyLarge.lineHeight.toDp(), t.bodyMedium.lineHeight.toDp())
        32.dp + t.titleLarge.lineHeight.toDp() + 12.dp + 6.dp + 6.dp + line + 12.dp + 48.dp + 32.dp
    }
    return maxOf(estimate, tvControlsHeight) + 16.dp
}

/** MyAnimeList's message on TV: text only (a remote can't reach it); Undo and the picker are in the panel. */
@Composable
private fun TvMalNotice(container: AppContainer, modifier: Modifier = Modifier) {
    val notice by container.mal.notice.collectAsStateWithLifecycle()
    val n = notice ?: return
    LaunchedEffect(n) {
        delay(n.showMillis)
        container.mal.dismissNotice()
    }
    val hint = if (n.confirm != null) " · answer in the MyAnimeList panel" else ""
    Text(
        n.text + hint,
        maxLines = if (n.question) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        style = if (n.question) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        modifier = modifier.widthIn(max = if (n.question) 640.dp else 420.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color(0xB3161A21)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
