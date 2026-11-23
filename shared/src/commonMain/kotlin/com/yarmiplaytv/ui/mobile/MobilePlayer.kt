package com.yarmiplaytv.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.ui.player.AudioStage
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.playback.playbackAction
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.SubtitleScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.TrackType
import com.yarmiplaytv.relay.RelayHint
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.sync.PlaylistStatus
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.player.formatClock
import com.yarmiplaytv.ui.player.playbackInfo
import com.yarmiplaytv.ui.player.playerTitle
import com.yarmiplaytv.ui.theme.LocalIconLabels
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import com.yarmiplaytv.ui.theme.OnVideo
import com.yarmiplaytv.ui.theme.onVideoLabel
import com.yarmiplaytv.voice.MicWithActivity
import com.yarmiplaytv.voice.VoiceSpeakingOverlay
import com.yarmiplaytv.voice.VoiceUiState
import com.yarmiplaytv.voice.audibleSpeakingNames
import com.yarmiplaytv.voice.micControlDescription
import com.yarmiplaytv.voice.micControlLive
import com.yarmiplaytv.voice.voiceMembershipDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.vector.ImageVector

private enum class PlayerSheet(val label: String, val icon: ImageVector, val short: String = label) {
    PLAYLIST("Shared playlist", Icons.AutoMirrored.Filled.PlaylistPlay, "Playlist"),
    ROOM("Room", Icons.Filled.Groups),
    CHAT("Chat", Icons.Filled.Forum),
    AUDIO("Audio", Icons.Filled.Audiotrack),
    SUBTITLES("Subtitles", Icons.Filled.Subtitles),
    VIDEO("Video", Icons.Filled.Tune),
    MAL("MyAnimeList", Icons.Filled.Bookmarks),
}

/**
 * True in the desktop's Syncplay-style player window: the room, chat and playlist are in the other window, so the
 * player leaves out their buttons and side panel.
 */
val LocalRoomElsewhere = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilePlayerScreen(container: AppContainer, nav: Navigator) {
    val player = container.player
    // Without the clock: the seek bar polls the position itself, so its ticks don't rebuild the whole screen.
    val state by player.collectStateWithoutClock()
    val room by container.sync.roomView.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val status by container.playlist.status.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val step = settings.playback.seekStepSeconds.toDouble()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    val roomElsewhere = LocalRoomElsewhere.current

    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var lastSheet by remember { mutableStateOf(PlayerSheet.ROOM) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    val barsHover = remember { MutableInteractionSource() }
    val onBars by barsHover.collectIsHoveredAsState()
    val pip = rememberPictureInPicture(player, settings.playback.pictureInPicture, inRoom)

    LaunchedEffect(pip.active) {
        if (pip.active) {
            sheet = null
            controls = false
        } else {
            controls = true
        }
    }

    LaunchedEffect(controls, interaction, state.paused, sheet, onBars) {
        if (controls && !state.paused && sheet == null && !onBars) {
            delay(controlsHideMillis)
            controls = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(700); seekFlash = null } }

    PlatformBackHandler {
        when {
            // Only the desktop's mini player gets Back (Esc): it goes back to the full window first.
            pip.active -> pip.toggle()
            sheet != null -> sheet = null
            else -> nav.back()
        }
    }

    fun touch() { interaction = System.currentTimeMillis() }
    fun togglePause() { player.setPaused(!player.isPaused); touch() }
    fun seekBy(delta: Double) {
        val to = (player.currentPosition() + delta).coerceIn(0.0, player.duration.takeIf { it > 0 } ?: Double.MAX_VALUE)
        player.seekRelative(delta)
        val by = if (delta < 0) "−${(-delta).toInt()} s" else "+${delta.toInt()} s"
        seekFlash = "$by · ${formatClock(to)}"
        touch()
    }

    val input = PlayerInput(
        seekStep = step,
        controlsVisible = controls || sheet != null,
        toggleControls = { controls = !controls; touch() },
        showControls = { controls = true; touch() },
        togglePause = ::togglePause,
        seekBy = ::seekBy,
        skip = { forward -> container.playlist.skip(forward); touch() },
    )
    // RoomSidePanel is only composed in landscape; portrait must use the modal sheet or room/chat/playlist never appear.
    var portraitLayout by remember { mutableStateOf(false) }
    fun openSheet(kind: PlayerSheet) {
        lastSheet = kind
        val tab = when (kind) {
            PlayerSheet.PLAYLIST -> RoomPanelTab.PLAYLIST
            PlayerSheet.ROOM -> RoomPanelTab.ROOM
            PlayerSheet.CHAT -> RoomPanelTab.CHAT
            PlayerSheet.MAL -> RoomPanelTab.MAL
            else -> null
        }
        // Side panel is desktop-landscape only; phone/tablet and portrait always use the modal sheet.
        val useDesktopSidePanel = container.deviceKind == DeviceKind.DESKTOP && !portraitLayout && !roomElsewhere
        if (tab == null || !useDesktopSidePanel || !toggleRoomSidePanel(tab)) sheet = kind
    }
    fun openMenus() = openSheet(lastSheet)
    if (pip.active) {
        if (pictureInPictureWindow) PictureInPictureElsewhere(pip) else PictureInPictureView(container)
        return
    }
    val isAudio = FileNames.isAudio(nowPlaying?.fileName ?: state.url.orEmpty())
    val fullscreenHolder = remember { mutableStateOf<(() -> Unit)?>(null) }
    // The desktop window has the same keys. A text field consumes a key before this sees it.
    val keyboard = if (container.deviceKind == DeviceKind.DESKTOP) {
        Modifier
    } else {
        Modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.isMetaPressed) return@onKeyEvent false
            val action = playbackAction(event.key, shift = event.isShiftPressed, ctrl = event.isCtrlPressed, alt = event.isAltPressed)
                ?: return@onKeyEvent false
            when (action) {
                PlaybackAction.ToggleControls -> input.toggleControls()
                PlaybackAction.SkipSegment -> {
                    if (container.skips.prompt.value == null) return@onKeyEvent false
                    container.skips.skip()
                }
                PlaybackAction.FocusChat -> openSheet(PlayerSheet.CHAT)
                PlaybackAction.TogglePip -> pip.toggle()
                PlaybackAction.Fullscreen -> fullscreenHolder.value?.invoke() ?: return@onKeyEvent false
                PlaybackAction.ToggleMic -> {
                    if (!container.voice.ui.value.showMicButton) return@onKeyEvent false
                    container.voice.toggleMic()
                }
                else -> if (!container.playback.perform(action)) return@onKeyEvent false
            }
            if (action.showsControls) input.showControls()
            true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(keyboard)) {
        val isPortrait = maxWidth < maxHeight
        portraitLayout = isPortrait
        val fullscreenToggle = rememberFullscreenToggle(isPortrait)
        fullscreenHolder.value = fullscreenToggle
        FullscreenLandscape(pictureInPicture = pip.active, isPortrait = isPortrait)
        // The portrait video is a short 16:9 strip. A status card centered in it covers the back
        // button, fullscreen, and toasts, so a short strip shows the card in the middle of the screen.
        val videoStrip = (maxWidth * 9f / 16f).coerceAtMost(maxHeight / 2)
        val liftStatusCard = isPortrait && videoStrip < StatusCardMinStrip &&
            (status is PlaylistStatus.NotFound || status is PlaylistStatus.Failed || status is PlaylistStatus.Untrusted)

        Layout(
            content = {
                // Child 0: The Video Area (ALWAYS kept in composition across orientation changes)
                Box(
                    Modifier
                        .background(Color.Black)
                        .testTag("player")
                        .playerScreenInput(input),
                ) {
                    CompositionLocalProvider(LocalContentColor provides OnVideo.Text) {
                        VideoSurface(player, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().testTag("player_gestures").videoGestures(input))

                        AudioStage(
                            player,
                            state,
                            fileName = nowPlaying?.fileName ?: state.url.orEmpty(),
                            title = nowPlaying?.let(::playerTitle) ?: FileNames.baseName(state.url.orEmpty()),
                            subtitle = nowPlaying?.episode?.seriesName,
                            compact = isPortrait,
                        )

                        if (!liftStatusCard) {
                            CenterStatus(container, nav, status, nowPlaying == null, state.fileLoaded, inRoom, compact = isPortrait)
                        }
                        RelayOnPlayer(container, state.fileLoaded, controls, isPortrait = isPortrait)

                        if (state.buffering && state.fileLoaded) {
                            Chip("Buffering… %.0fs cached".format(state.cacheSeconds), AppColors.NotReady, Modifier.align(Alignment.TopCenter).padding(top = if (isPortrait) 16.dp else 24.dp))
                        }
                        seekFlash?.let { Chip(it, AppColors.Accent, Modifier.align(Alignment.Center)) }
                        PlayerToasts(container, Modifier.align(Alignment.TopStart).padding(if (isPortrait) 8.dp else 16.dp))
                        if (!isPortrait) {
                            MalNoticeCard(container, onPick = { openSheet(PlayerSheet.MAL) }, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp))
                        }
                        SkipSegmentButton(
                            container,
                            if (isPortrait) {
                                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
                            } else {
                                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = SkipButtonBottom)
                            },
                            hint = skipKeyHint,
                        )

                        val voiceUi by container.voice.ui.collectAsStateWithLifecycle()
                        val speakingNames = remember(voiceUi, settings) { voiceUi.audibleSpeakingNames(settings) }
                        // Edge speaking list — only while someone is heard; nothing when idle.
                        VoiceSpeakingOverlay(
                            speakingNames,
                            Modifier
                                .align(if (isPortrait) Alignment.TopStart else Alignment.CenterStart)
                                .padding(start = 12.dp, top = if (isPortrait) 56.dp else 0.dp),
                        )

                        androidx.compose.animation.AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().testTag(if (isPortrait) "video_overlay" else "player_controls")) {
                                Row(
                                    Modifier.fillMaxWidth().hoverable(barsHover)
                                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // A player window of its own has nothing to go back to.
                                    if (roomElsewhere) {
                                        Spacer(Modifier.width(12.dp))
                                    } else {
                                        IconButton(nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                                    }
                                    if (isPortrait) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        Text(nowPlaying?.let(::playerTitle) ?: "Nothing playing", color = OnVideo.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    }
                                    if (!isPortrait) {
                                        // Status cluster (room · ready · ping · compact mic); hides with HUD — no video Mic badge.
                                        LiveRoomStatus(
                                            container,
                                            room,
                                            playerStreamingLabel(container, controls, state.fileLoaded)?.takeIf { controls },
                                        )
                                    }
                                    if (pip.supported && state.fileLoaded) {
                                        IconButton(pip.toggle, Modifier.testTag("pip")) {
                                            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = pictureInPictureLabel, tint = Color.White)
                                        }
                                    }
                                    fullscreenToggle?.let { toggle ->
                                        IconButton({ toggle(); touch() }, Modifier.testTag("fullscreen")) {
                                            Icon(
                                                if (isPortrait) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
                                                contentDescription = if (isPortrait) "Full screen" else "Exit full screen",
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                    if (!isPortrait && hideControlsButton) {
                                        TextButton(
                                            { controls = false },
                                            Modifier.testTag("hide_controls"),
                                            colors = ButtonDefaults.textButtonColors(containerColor = OnVideo.Background, contentColor = OnVideo.Text),
                                        ) {
                                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnVideo.Text)
                                            Text("Hide (H)", color = OnVideo.Text)
                                        }
                                    }
                                }

                                if (!isPortrait) {
                                    if (state.fileLoaded) {
                                        val skips = inRoom && room.playlist.isNotEmpty()
                                        Row(
                                            Modifier.align(Alignment.Center),
                                            horizontalArrangement = Arrangement.spacedBy(if (skips) 16.dp else 32.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (skips) {
                                                RoundButton(Icons.Filled.SkipPrevious, "Previous in the playlist", enabled = container.playlist.skipTarget(room, forward = false) != null, modifier = Modifier.testTag("skip_previous")) { input.skip(false) }
                                            }
                                            RoundButton(Icons.Filled.FastRewind, "Back ${step.toInt()} seconds") { seekBy(-step) }
                                            RoundButton(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "Play" else "Pause", big = true, modifier = Modifier.testTag("play_pause")) { togglePause() }
                                            RoundButton(Icons.Filled.FastForward, "Forward ${step.toInt()} seconds") { seekBy(step) }
                                            if (skips) {
                                                RoundButton(Icons.Filled.SkipNext, "Next in the playlist", enabled = container.playlist.skipTarget(room, forward = true) != null, modifier = Modifier.testTag("skip_next")) { input.skip(true) }
                                            }
                                        }
                                    }
                                    BottomControls(
                                        container = container,
                                        inRoom = inRoom,
                                        ready = room.isReady == true,
                                        voiceUi = voiceUi,
                                        onInteract = ::touch,
                                        onSheet = { openSheet(it); touch() },
                                        onMenus = { openMenus(); touch() },
                                        modifier = Modifier.align(Alignment.BottomCenter).hoverable(barsHover),
                                    )
                                }
                            }
                        }
                    }
                }

                // Child 1: Portrait bottom section (title, details, controls) or Landscape RoomSidePanel
                if (isPortrait) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        // fillMaxWidth (not fillMaxSize): scroll must measure content taller than the viewport.
                        Column(
                            Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Media Info Card with expandable full title and media details
                            var titleExpanded by remember(nowPlaying?.url) { mutableStateOf(false) }
                            Surface(
                                color = AppColors.Surface, contentColor = AppColors.OnSurface,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("title_card")
                                    .clickable { titleExpanded = !titleExpanded },
                            ) {
                                SurfaceInk {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                nowPlaying?.let(::playerTitle) ?: "No media loaded",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = AppColors.Text,
                                                maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.testTag("full_title"),
                                            )
                                            val subtitle = nowPlaying?.episode?.seriesName ?: if (isAudio) "Music" else ""
                                            if (subtitle.isNotEmpty()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AppColors.TextDim,
                                                    maxLines = if (titleExpanded) Int.MAX_VALUE else 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { titleExpanded = !titleExpanded },
                                            modifier = Modifier.size(32.dp).testTag("title_expand"),
                                        ) {
                                            Icon(
                                                if (titleExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (titleExpanded) "Collapse title" else "Expand title",
                                                tint = AppColors.TextDim,
                                            )
                                        }
                                    }
                                    if (titleExpanded) {
                                        val fileName = nowPlaying?.fileName
                                        if (!fileName.isNullOrBlank() && fileName != nowPlaying?.let(::playerTitle)) {
                                            Text(
                                                fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppColors.TextDim,
                                                modifier = Modifier.testTag("full_filename"),
                                            )
                                        }
                                        val specs = listOfNotNull(
                                            if (state.duration > 0) formatClock(state.duration) else nowPlaying?.durationHint?.takeIf { it > 0 }?.let { formatClock(it) },
                                            nowPlaying?.sizeBytes?.takeIf { it > 0 }?.let { formatSize(it) },
                                            playbackInfo(state, showStats = settings.playback.showPlaybackStats).takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        if (specs.isNotBlank()) {
                                            Text(
                                                specs,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AppColors.TextDim.copy(alpha = 0.8f),
                                                modifier = Modifier.testTag("full_specs"),
                                            )
                                        }
                                    }
                                }
                                }
                            }

                            // Dedicated Room Status & Ready Card (always visible when connected to a room, no need to tap the video)
                            val portraitVoice by container.voice.ui.collectAsStateWithLifecycle()
                            if (room.status != ConnectionStatus.DISCONNECTED) {
                                Surface(
                                    color = AppColors.Surface, contentColor = AppColors.OnSurface,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("portrait_room_card"),
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .clickable { openSheet(PlayerSheet.ROOM) },
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(if (inRoom) AppColors.Ready else AppColors.NotReady)
                                                )
                                                Text(
                                                    ManagedRooms.label(room.room),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = AppColors.Text,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (inRoom) RoomPing(container)
                                            }
                                            val subtitleText = if (inRoom) {
                                                val readyCount = room.users.count { it.isReady == true }
                                                val streamingPeer = playerStreamingLabel(container, controlsVisible = true, state.fileLoaded)
                                                val memberSummary = "${room.users.size} watching · $readyCount ready"
                                                if (streamingPeer != null) "$memberSummary · $streamingPeer" else memberSummary
                                            } else {
                                                "Connecting…"
                                            }
                                            Text(
                                                subtitleText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppColors.TextDim,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (inRoom) {
                                            ReadyIconButton(
                                                ready = room.isReady == true,
                                                onClick = { container.sync.toggleReady(); touch() },
                                                onVideo = false,
                                            )
                                        }
                                        // Mic beside Ready — full 48dp target (not crushed by the volume row).
                                        LiveMicButton(
                                            container,
                                            portraitVoice,
                                            onToggle = { container.voice.toggleMic(); touch() },
                                            onVideo = false,
                                            showKeyboardShortcut = false,
                                        )
                                        PlayerVoiceMembershipButton(
                                            portraitVoice,
                                            onJoin = { container.voice.join(); touch() },
                                            onLeave = { container.voice.leave(); touch() },
                                            onVideo = false,
                                        )
                                    }
                                }
                            }

                            MalNoticeCard(container, onPick = { openSheet(PlayerSheet.MAL) }, Modifier.fillMaxWidth())

                            // Portrait player controls card (stays visible so controls never vanish in portrait)
                            Surface(
                                color = AppColors.Surface, contentColor = AppColors.OnSurface,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("player_controls"),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    PolledSeekBar(
                                        container,
                                        state,
                                        ::touch,
                                        onVideo = false,
                                        showStats = settings.playback.showPlaybackStats,
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val skips = inRoom && room.playlist.isNotEmpty()
                                        if (skips) {
                                            IconButton(
                                                onClick = { input.skip(false) },
                                                enabled = container.playlist.skipTarget(room, forward = false) != null,
                                                modifier = Modifier.testTag("skip_previous"),
                                            ) {
                                                Icon(Icons.Filled.SkipPrevious, "Previous in the playlist", tint = AppColors.Text)
                                            }
                                        }
                                        Surface(
                                            onClick = { seekBy(-step) },
                                            shape = RoundedCornerShape(50),
                                            color = AppColors.SurfaceHigh,
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.FastRewind, "Back ${step.toInt()} seconds", tint = AppColors.Text)
                                            }
                                        }
                                        FilledIconButton(
                                            onClick = { togglePause() },
                                            modifier = Modifier.size(60.dp).testTag("play_pause"),
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = AppColors.Accent),
                                        ) {
                                            Icon(
                                                if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                                contentDescription = if (state.paused) "Play" else "Pause",
                                                tint = Color.Black,
                                                modifier = Modifier.size(32.dp),
                                            )
                                        }
                                        Surface(
                                            onClick = { seekBy(step) },
                                            shape = RoundedCornerShape(50),
                                            color = AppColors.SurfaceHigh,
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.FastForward, "Forward ${step.toInt()} seconds", tint = AppColors.Text)
                                            }
                                        }
                                        if (skips) {
                                            IconButton(
                                                onClick = { input.skip(true) },
                                                enabled = container.playlist.skipTarget(room, forward = true) != null,
                                                modifier = Modifier.testTag("skip_next"),
                                            ) {
                                                Icon(Icons.Filled.SkipNext, "Next in the playlist", tint = AppColors.Text)
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    // One row: shrinking volume left, screenshot/Menus (or desktop sheets) right.
                                    // Mic stays beside Ready on the room card (or here when disconnected) — leave its size alone.
                                    // Labels under icons only when the row has room for them beside a usable volume slider
                                    // (a 360dp-wide window doesn't); otherwise these stay icons.
                                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    CompositionLocalProvider(LocalIconLabels provides (LocalIconLabels.current && maxWidth >= PortraitLabelsMinWidth)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // When the room card is hidden, keep Ready/Mic/voice reachable here.
                                        if (room.status == ConnectionStatus.DISCONNECTED) {
                                            if (inRoom) {
                                                ReadyIconButton(
                                                    ready = room.isReady == true,
                                                    onClick = { container.sync.toggleReady(); touch() },
                                                    modifier = Modifier.testTag("player_ready_controls"),
                                                    onVideo = false,
                                                )
                                            }
                                            LiveMicButton(
                                                container,
                                                portraitVoice,
                                                onToggle = { container.voice.toggleMic(); touch() },
                                                onVideo = false,
                                                showKeyboardShortcut = false,
                                            )
                                            PlayerVoiceMembershipButton(
                                                portraitVoice,
                                                onJoin = { container.voice.join(); touch() },
                                                onLeave = { container.voice.leave(); touch() },
                                                onVideo = false,
                                            )
                                        }
                                        PlayerVolumeControl(
                                            ::touch,
                                            onVideo = false,
                                            modifier = Modifier.weight(1f),
                                        )
                                        rememberRelaySave(container)?.let { RelaySaveButton(it, ::touch, tint = AppColors.Text) }
                                        container.screenshots?.takeIf { state.fileLoaded }?.let { screenshots ->
                                            LabeledIconButton(
                                                Icons.Filled.PhotoCamera,
                                                "Screenshot",
                                                { screenshots.take(); touch() },
                                                Modifier.testTag("screenshot"),
                                                tint = AppColors.Text,
                                            )
                                        }
                                        // Menus (TV-style) is phone/tablet only; desktop keeps direct sheet buttons.
                                        if (container.deviceKind == DeviceKind.PHONE || container.deviceKind == DeviceKind.TABLET) {
                                            LabeledIconButton(
                                                Icons.Filled.Menu,
                                                "Menus",
                                                { openMenus(); touch() },
                                                Modifier.testTag("player_menus"),
                                                tint = AppColors.Text,
                                            )
                                        } else {
                                            PlayerSheet.entries
                                                .filter { (!roomElsewhere || it.inPlayerWindow) && (it != PlayerSheet.MAL || container.mal.client.configured) }
                                                .forEach { SheetButton(it, tint = AppColors.Text, onVideo = false) { openSheet(it) } }
                                        }
                                    }
                                    }
                                    }
                                }
                            }
                        }
                    }
                } else if (!roomElsewhere) {
                    RoomSidePanel(container)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background),
        ) { measurables, constraints ->
            val videoMeasurable = measurables[0]
            val secondMeasurable = measurables.getOrNull(1)

            if (isPortrait) {
                val videoWidth = constraints.maxWidth
                val videoHeight = (videoWidth * 9 / 16).coerceAtMost(constraints.maxHeight / 2)
                val videoPlaceable = videoMeasurable.measure(
                    Constraints.fixed(videoWidth, videoHeight)
                )

                val bottomHeight = (constraints.maxHeight - videoHeight).coerceAtLeast(0)
                val bottomPlaceable = secondMeasurable?.measure(
                    Constraints.fixed(constraints.maxWidth, bottomHeight)
                )

                layout(constraints.maxWidth, constraints.maxHeight) {
                    videoPlaceable.placeRelative(0, 0)
                    bottomPlaceable?.placeRelative(0, videoHeight)
                }
            } else {
                // Landscape
                val sidePanelPlaceable = secondMeasurable?.measure(
                    constraints.copy(minWidth = 0)
                )
                val sidePanelWidth = sidePanelPlaceable?.width ?: 0
                val videoWidth = (constraints.maxWidth - sidePanelWidth).coerceAtLeast(0)
                val videoPlaceable = videoMeasurable.measure(
                    Constraints.fixed(videoWidth, constraints.maxHeight)
                )

                layout(constraints.maxWidth, constraints.maxHeight) {
                    videoPlaceable.placeRelative(0, 0)
                    sidePanelPlaceable?.placeRelative(videoWidth, 0)
                }
            }
        }
        if (liftStatusCard) {
            PhonePortraitStatusCard {
                CenterStatus(container, nav, status, nowPlaying == null, state.fileLoaded, inRoom, compact = true, onVideo = false)
            }
        }
    }

    sheet?.let { kind ->
        val useMenus = container.deviceKind == DeviceKind.PHONE || container.deviceKind == DeviceKind.TABLET
        val menuTabs = PlayerSheet.entries.filter { it != PlayerSheet.MAL || container.mal.client.configured }
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Surface,
            contentColor = AppColors.OnSurface,
            modifier = Modifier.testTag("player_sheet").exposeTestTags(),
        ) {
            SurfaceInk {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                // Tab strip only with Menus on phone/tablet; desktop opens one sheet at a time.
                if (useMenus) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        menuTabs.forEach { tab ->
                            val selected = tab == kind
                            LabeledIconButton(
                                tab.icon,
                                tab.label,
                                onClick = { openSheet(tab) },
                                modifier = Modifier.testTag("sheet_tab_${tab.name.lowercase()}"),
                                tint = if (selected) AppColors.Accent else AppColors.TextDim,
                                shortLabel = tab.short,
                            )
                        }
                    }
                }
                when (kind) {
                    PlayerSheet.PLAYLIST -> PlaylistContent(container, onBrowse = { sheet = null; nav.push(Screen.LocalFiles) })
                    PlayerSheet.ROOM -> RoomUsersContent(container)
                    PlayerSheet.CHAT -> ChatContent(container, maxHeight = 220.dp)
                    PlayerSheet.AUDIO -> TracksContent(container, TrackType.AUDIO, onPicked = { sheet = null })
                    PlayerSheet.SUBTITLES -> TracksContent(container, TrackType.SUBTITLE, onPicked = { sheet = null })
                    PlayerSheet.VIDEO -> VideoAdjustments(container, Modifier.padding(horizontal = 16.dp))
                    PlayerSheet.MAL -> {
                        val mal by container.mal.state.collectAsStateWithLifecycle()
                        MalContent(container, mal, onSignIn = { sheet = null; nav.push(Screen.MalLogin) })
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
private fun BottomControls(
    container: AppContainer,
    inRoom: Boolean,
    ready: Boolean,
    voiceUi: VoiceUiState,
    onInteract: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    onMenus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by container.player.collectStateWithoutClock()
    val settings by container.settings.collectAsStateWithLifecycle()
    val useMenus = container.deviceKind == DeviceKind.PHONE || container.deviceKind == DeviceKind.TABLET
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        PolledSeekBar(
            container,
            state,
            onInteract,
            onVideo = true,
            showStats = settings.playback.showPlaybackStats,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerVolumeControl(onInteract, Modifier.padding(end = 8.dp))
            if (inRoom) {
                ReadyIconButton(
                    ready = ready,
                    onClick = { container.sync.toggleReady(); onInteract() },
                    onVideo = true,
                )
            }
            // Beside Ready — same toggleMic() path as the Room tab (OPEN_MIC mute / TOGGLE latch).
            LiveMicButton(
                container,
                voiceUi,
                onToggle = { container.voice.toggleMic(); onInteract() },
                onVideo = true,
                showKeyboardShortcut = container.deviceKind == DeviceKind.DESKTOP,
            )
            PlayerVoiceMembershipButton(
                voiceUi,
                onJoin = { container.voice.join(); onInteract() },
                onLeave = { container.voice.leave(); onInteract() },
                onVideo = true,
            )
            Spacer(Modifier.weight(1f))
            rememberRelaySave(container)?.let { RelaySaveButton(it, onInteract) }
            container.screenshots?.takeIf { state.fileLoaded }?.let { screenshots ->
                LabeledIconButton(Icons.Filled.PhotoCamera, "Screenshot", { screenshots.take(); onInteract() }, Modifier.testTag("screenshot"), tint = Color.White, onVideo = true)
            }
            if (useMenus) {
                LabeledIconButton(Icons.Filled.Menu, "Menus", { onMenus(); onInteract() }, Modifier.testTag("player_menus"), tint = Color.White, onVideo = true)
            } else {
                // The Syncplay-style room window has these; its player window leaves them out.
                val roomElsewhere = LocalRoomElsewhere.current
                PlayerSheet.entries
                    .filter { (!roomElsewhere || it.inPlayerWindow) && (it != PlayerSheet.MAL || container.mal.client.configured) }
                    .forEach { SheetButton(it) { onSheet(it) } }
            }
        }
    }
}

/** Room chip that follows the mic meter without recomposing the video. */
@Composable
private fun LiveRoomStatus(container: AppContainer, room: RoomState, streaming: String?) {
    val voiceUi by container.voice.ui.collectAsStateWithLifecycle()
    val level by container.voice.captureLevel.collectAsStateWithLifecycle()
    val ping by container.sync.pingMillis.collectAsStateWithLifecycle()
    RoomStatusChip(room, streaming, ping, micLive = voiceUi.micControlLive(), micLevel = level)
}

/** Our ping, in its own composable: it changes about once a second, and the header around it shouldn't. */
@Composable
private fun RoomPing(container: AppContainer) {
    val ping by container.sync.pingMillis.collectAsStateWithLifecycle()
    if (ping > 0) {
        Text(
            "%.0f ms".format(ping),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextDim,
        )
    }
}

/** Mic button that follows the capture meter without recomposing the player. */
@Composable
private fun LiveMicButton(
    container: AppContainer,
    ui: VoiceUiState,
    onToggle: () -> Unit,
    onVideo: Boolean,
    showKeyboardShortcut: Boolean,
) {
    val level by container.voice.captureLevel.collectAsStateWithLifecycle()
    PlayerMicButton(ui, onToggle, onVideo, showKeyboardShortcut, level)
}

/** Seek bar clock. Kept in its own composable so the 4 Hz poll does not rebuild the player. */
@Composable
private fun PolledSeekBar(
    container: AppContainer,
    state: PlaybackState,
    onInteract: () -> Unit,
    onVideo: Boolean,
    showStats: Boolean,
) {
    val segments by container.skips.segments.collectAsStateWithLifecycle()
    var position by remember { mutableDoubleStateOf(container.player.currentPosition()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = container.player.currentPosition()
            delay(250)
        }
    }
    TvSeekBar(
        state,
        position,
        onInteract,
        container.seekFrames,
        segments,
        onVideo = onVideo,
        showStats = showStats,
    ) { container.player.seek(it) }
}

/** Player Mic toggle beside Ready; visual state matches Room tab via [micControlLive]. */
@Composable
private fun PlayerMicButton(
    ui: VoiceUiState,
    onToggle: () -> Unit,
    onVideo: Boolean,
    showKeyboardShortcut: Boolean = false,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    if (!ui.showMicButton) return
    val live = ui.micControlLive()
    val tint = when {
        live -> AppColors.Ready
        onVideo -> Color.White
        else -> AppColors.Text
    }
    // requiredSize keeps a 48dp touch target even when a tight parent would otherwise crush the IconButton.
    IconButton(onToggle, modifier.requiredSize(48.dp).testTag("player_mic_toggle")) {
        MicWithActivity(
            live = live,
            level = level,
            tint = tint,
            contentDescription = ui.micControlDescription(showKeyboardShortcut),
            iconSize = 26.dp,
            ringSize = 40.dp,
        )
    }
}

/** Headset Join / Leave voice on the player bar — distinct from the Mic mute icon. */
@Composable
private fun PlayerVoiceMembershipButton(
    ui: VoiceUiState,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!ui.showVoiceMembershipButton) return
    val inVoice = ui.voiceMembershipIsLeave
    val tint = when {
        inVoice && onVideo -> Color.White
        inVoice -> AppColors.Text
        onVideo -> Color.White.copy(alpha = 0.72f)
        else -> AppColors.TextDim
    }
    IconButton(
        onClick = { if (inVoice) onLeave() else onJoin() },
        modifier = modifier
            .requiredSize(48.dp)
            .testTag(if (inVoice) "player_voice_leave" else "player_voice_join"),
    ) {
        Icon(
            if (inVoice) Icons.Filled.Headset else Icons.Filled.HeadsetOff,
            contentDescription = ui.voiceMembershipDescription(),
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** The portrait controls row is at least this wide before its icons get labels. */
private val PortraitLabelsMinWidth = 330.dp

/** The playlist, room and chat are in the Syncplay-style room window; its player window has only these. */
private val PlayerSheet.inPlayerWindow: Boolean
    get() = this != PlayerSheet.PLAYLIST && this != PlayerSheet.ROOM && this != PlayerSheet.CHAT

/** One of the desktop bar's sheet buttons, named under the icon while Labels under icons is on. */
@Composable
private fun SheetButton(
    sheet: PlayerSheet,
    tint: Color = Color.White,
    onVideo: Boolean = true,
    onClick: () -> Unit,
) {
    LabeledIconButton(sheet.icon, sheet.label, onClick, Modifier.testTag("sheet_${sheet.label}"), tint = tint, onVideo = onVideo, shortLabel = sheet.short)
}

/** The player as Android's picture-in-picture window: only the video (the system adds its own controls). */
@Composable
private fun PictureInPictureView(container: AppContainer) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_pip")) {
        VideoSurface(container.player, Modifier.fillMaxSize())
    }
}

/**
 * The player screen while the video plays in the desktop's picture-in-picture window (only one place can show it):
 * a note, and the way to bring the video back here.
 */
@Composable
private fun PictureInPictureElsewhere(pip: PictureInPicture) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_pip_elsewhere"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null, tint = OnVideo.Text, modifier = Modifier.size(48.dp))
            Text("Playing in picture-in-picture", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Button(pip.toggle, Modifier.testTag("pip_back_here")) { Text("Show the video here") }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    big: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f), modifier = modifier.size(if (big) 72.dp else 56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(if (big) 44.dp else 32.dp))
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
        Text("  $text", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoomStatusChip(
    room: RoomState,
    streaming: String? = null,
    pingMillis: Double = 0.0,
    micLive: Boolean = false,
    micLevel: Float = 0f,
) {
    when (room.status) {
        ConnectionStatus.CONNECTED -> {
            val ready = room.users.count { it.isReady == true }
            val via = streaming?.let { " · ${it.withPing(pingMillis)}" }.orEmpty()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (micLive) Modifier.testTag("status_mic_live") else Modifier,
            ) {
                Chip(
                    "${ManagedRooms.label(room.room)} · ${room.users.size} watching · $ready ready$via",
                    AppColors.Ready,
                    if (streaming != null) Modifier.widthIn(max = 420.dp).testTag("relay_streaming") else Modifier,
                )
                if (micLive) {
                    com.yarmiplaytv.voice.MicActivityRing(
                        level = micLevel,
                        active = true,
                        color = AppColors.Ready,
                        modifier = Modifier.padding(start = 8.dp),
                        size = 18.dp,
                        stroke = 1.5.dp,
                    )
                }
            }
        }
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Chip("Connecting…", AppColors.NotReady)
        ConnectionStatus.DISCONNECTED -> Chip("Not in a room", OnVideo.Text.copy(alpha = 0.6f))
    }
}

/** The short streaming line, while it should be on screen. Null for a download or a finished copy. */
@Composable
private fun playerStreamingLabel(container: AppContainer, controlsVisible: Boolean, fileLoaded: Boolean): String? {
    val relay = container.relay ?: return null
    val status by relay.status.collectAsStateWithLifecycle()
    val current = status ?: return null
    return current.text.takeIf { current.streamingLineVisible(controlsVisible, fileLoaded) }
}

/** Our ping, on the same chip as the peer's name. */
private fun String.withPing(rttMillis: Double): String =
    if (rttMillis > 0) "$this · %.0f ms".format(rttMillis) else this

@Composable
private fun PlayerToasts(container: AppContainer, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<List<Pair<Long, FeedMessage>>>(emptyList()) }
    LaunchedEffect(Unit) {
        container.sync.toasts.collect { msg ->
            if (!msg.showsOnVideo(container.settings.value.chatOnVideo)) return@collect
            val id = System.nanoTime()
            shown = (shown + (id to msg)).takeLast(3)
        }
    }
    LaunchedEffect(shown) {
        val first = shown.firstOrNull()
        if (first != null) {
            val duration = if (first.second.text.contains("jumped")) 2000L else 4000L
            delay(duration)
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
private fun BoxScope.RelayOnPlayer(
    container: AppContainer,
    fileLoaded: Boolean,
    controlsVisible: Boolean,
    isPortrait: Boolean = false,
) {
    val relay = container.relay ?: return
    val relayStatus by relay.status.collectAsStateWithLifecycle()
    val ping by container.sync.pingMillis.collectAsStateWithLifecycle()
    val status = relayStatus ?: return
    when {
        !isPortrait && status.streamingLineVisible(controlsVisible, fileLoaded) && !controlsVisible -> Chip(
            status.text.withPing(ping),
            AppColors.Ready,
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp).widthIn(max = 280.dp).testTag("relay_streaming"),
        )
        status.playbackHint(fileLoaded) == RelayHint.Downloading -> Surface(
            color = AppColors.Surface.copy(alpha = 0.9f),
            contentColor = AppColors.OnSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.Center).widthIn(max = 560.dp).testTag("relay_download"),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.text, style = MaterialTheme.typography.titleMedium)
                RelayDownloadBar(status.fraction)
            }
        }
    }
}

/** A 16:9 strip shorter than this can't show the status card clear of the top controls and toasts. */
private val StatusCardMinStrip = 300.dp

/**
 * Phone portrait (and any similarly short strip) draws the missing-file card here, centered on the
 * screen and kept below the video, instead of on top of the picture.
 */
@Composable
private fun PhonePortraitStatusCard(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val card = measurables[0].measure(
            Constraints(
                maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - margin).coerceAtLeast(0),
            ),
        )
        val videoBottom = minOf(constraints.maxWidth * 9 / 16, constraints.maxHeight / 2)
        val maxTop = (constraints.maxHeight - card.height).coerceAtLeast(0)
        val centered = (constraints.maxHeight - card.height) / 2
        val belowVideo = (videoBottom + 8.dp.roundToPx()).coerceAtMost(maxTop)
        val y = centered.coerceAtLeast(belowVideo).coerceAtMost(maxTop)
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.placeRelative((constraints.maxWidth - card.width) / 2, y)
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
    compact: Boolean = false,
    onVideo: Boolean = true,
) {
    val hideLoading = container.relay?.let { relay ->
        val relayStatus by relay.status.collectAsStateWithLifecycle()
        relayStatus.let { it != null && it.playbackHint(fileLoaded) != RelayHint.None }
    } == true
    val mediaSource by container.mediaSource.collectAsStateWithLifecycle()
    val pickFile = rememberVideoPicker { uri -> container.playlist.resolveManuallyLocal(uri) }
    val playFile = rememberVideoPicker { uri -> container.playlist.playLocal(uri, inRoom) }
    val titleStyle = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
    val innerPadding = if (compact) 14.dp else 20.dp
    val itemSpacing = if (compact) 6.dp else 8.dp
    val frame = if (onVideo) {
        Modifier.fillMaxSize().padding(if (compact) 12.dp else 24.dp)
    } else {
        Modifier.fillMaxWidth().widthIn(max = 560.dp)
    }
    Box(frame, contentAlignment = Alignment.Center) {
        when (status) {
            is PlaylistStatus.Resolving -> StatusBox("Looking for the file…", status.fileName)
            is PlaylistStatus.Loading -> if (!fileLoaded && !hideLoading) StatusBox("Loading…", status.fileName)
            is PlaylistStatus.NotFound, is PlaylistStatus.Failed -> {
                val (file, reason) = when (status) {
                    is PlaylistStatus.NotFound -> status.fileName to status.reason
                    is PlaylistStatus.Failed -> status.fileName to status.reason
                    else -> "" to ""
                }
                Surface(
                    color = AppColors.Surface.copy(alpha = 0.95f),
                    contentColor = AppColors.OnSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .then(if (onVideo) Modifier else Modifier.fillMaxWidth().holdClicks())
                        .testTag("not_found"),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                        Text(if (status is PlaylistStatus.NotFound) "Couldn't find this file" else "Couldn't play this file", style = titleStyle)
                        Text(file, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        Text(reason, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        StatusButtons(Modifier.padding(top = 2.dp)) {
                            if (status is PlaylistStatus.NotFound) {
                                Button(pickFile, Modifier.testTag("pick_file")) { ActionLabel("Pick file") }
                                if (mediaSource != null) {
                                    FilledTonalButton({ nav.push(Screen.Search(pickFor = file)) }, Modifier.testTag("search_servers")) {
                                        ActionLabel("Search servers")
                                    }
                                }
                            }
                            TextButton({ container.playlist.dismissStatus() }, Modifier.testTag("dismiss_status")) { ActionLabel("Dismiss") }
                        }
                    }
                }
            }
            is PlaylistStatus.Untrusted -> {
                Surface(
                    color = AppColors.Surface.copy(alpha = 0.95f),
                    contentColor = AppColors.OnSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .then(if (onVideo) Modifier else Modifier.fillMaxWidth().holdClicks())
                        .testTag("untrusted_url"),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                        Text("Open this link?", style = titleStyle)
                        Text(status.url, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        Text("The room picked it, but it isn't on one of your trusted domains.", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        StatusButtons(Modifier.padding(top = 2.dp)) {
                            Button({ container.playlist.playUntrusted() }, Modifier.testTag("open_link")) { ActionLabel("Open") }
                            status.domain?.let { domain ->
                                FilledTonalButton({
                                    val s = container.settings.value
                                    container.scope.launch { container.settingsStore.saveTrustedDomains(s.trustedDomains + domain, s.onlySwitchToTrustedDomains) }
                                    container.playlist.playUntrusted()
                                }, Modifier.testTag("trust_domain")) {
                                    Text("Always trust $domain", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton({ container.playlist.dismissStatus() }, Modifier.testTag("dismiss_status")) { ActionLabel("Dismiss") }
                        }
                    }
                }
            }
            PlaylistStatus.Idle -> if (nothingLoaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    Text("Nothing playing", color = OnVideo.Text, style = titleStyle, modifier = Modifier.onVideoLabel())
                    Text(
                        if (inRoom) "Pick something, or wait for someone in the room to choose a file." else "Pick a video to watch.",
                        color = OnVideo.Text,
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.onVideoLabel(),
                    )
                    StatusButtons {
                        Button(playFile, Modifier.testTag("open_video")) { ActionLabel("Open a video") }
                        FilledTonalButton({ nav.push(Screen.LocalFiles) }, Modifier.testTag("browse")) { ActionLabel("Browse") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/**
 * One row when the labels fit, otherwise a stack of equal-width buttons.
 * Labels stay on one line either way, so a narrow phone never breaks "Dismiss" in half.
 */
@Composable
private fun StatusButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val spacing = 8.dp.roundToPx()
        val measurables = subcompose(Unit, content)
        if (measurables.isEmpty()) {
            layout(0, 0) {}
        } else {
            val widths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
            val needed = widths.sum() + spacing * (widths.size - 1)
            val stack = constraints.hasBoundedWidth && needed > constraints.maxWidth
            val placeables = if (stack) {
                val width = constraints.maxWidth
                measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight)) }
            } else {
                measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)) }
            }
            if (stack) {
                val height = placeables.sumOf { it.height } + spacing * (placeables.size - 1)
                layout(constraints.maxWidth, height) {
                    var y = 0
                    placeables.forEach { placeable ->
                        placeable.placeRelative(0, y)
                        y += placeable.height + spacing
                    }
                }
            } else {
                val width = placeables.sumOf { it.width } + spacing * (placeables.size - 1)
                val height = placeables.maxOf { it.height }
                layout(width, height) {
                    var x = 0
                    placeables.forEach { placeable ->
                        placeable.placeRelative(x, (height - placeable.height) / 2)
                        x += placeable.width + spacing
                    }
                }
            }
        }
    }
}

/** Clicks on the card stay on the card. The screen around it still reaches the video controls. */
private fun Modifier.holdClicks(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun StatusBox(title: String, detail: String) {
    Surface(color = AppColors.Surface.copy(alpha = 0.9f), contentColor = AppColors.OnSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
