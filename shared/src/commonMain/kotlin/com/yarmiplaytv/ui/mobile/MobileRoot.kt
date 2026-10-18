package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.media.FileNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Home", Icons.Filled.Home),
    // Opens the player over the current screen from anywhere, whether something is playing or not.
    Tab(Screen.Player, "Player", Icons.Filled.PlayCircle),
    Tab(Screen.Search(), "Search", Icons.Filled.Search),
    Tab(Screen.SyncplayConnect, "Room", Icons.Filled.Groups),
    Tab(Screen.Settings, "Settings", Icons.Filled.Settings),
)

/** How the sections are reached: a bar at the bottom (phones), a rail of icons (tablets) or a side list (desktop). */
private enum class SectionNav { BAR, RAIL, SIDEBAR }

/**
 * The phone, tablet and desktop UI: bottom navigation on phones, a navigation rail on tablets, and on desktop a side
 * list like the Settings categories. [stack] is the screen stack; Android keeps it outside the activity so a
 * recreated activity comes back on the same screen.
 */
@Composable
fun MobileRoot(container: AppContainer, kind: DeviceKind, stack: SnapshotStateList<Screen> = remember { mutableStateListOf(Screen.Home) }) {
    val saveable = rememberSaveableStateHolder()
    val nav = remember { Navigator(stack) { saveable.removeState(it.key) } }
    val snackbar = remember { SnackbarHostState() }

    val pip = rememberPictureInPicture(container.player, false, false)
    val desktopPip = pictureInPictureWindow && pip.active

    LaunchedEffect(desktopPip) {
        if (desktopPip && nav.current == Screen.Player) {
            if (nav.canGoBack) nav.back() else nav.switchTab(Screen.Home)
        }
    }

    LaunchedEffect(Unit) {
        container.playlist.openPlayerRequests.collect { request ->
            if (request <= container.playlist.handledPlayerRequests) return@collect
            container.playlist.handledPlayerRequests = request
            if (nav.current != Screen.Player && nav.current !is Screen.Search && !(pictureInPictureWindow && PlayerPictureInPicture.active)) {
                nav.push(Screen.Player)
            }
        }
    }
    LaunchedEffect(Unit) {
        // Only the latest message is shown. The player replaces the Scaffold (and its SnackbarHost),
        // so a snackbar left pending there would otherwise pop up stale after leaving the player.
        var showing: Job? = null
        launch { snapshotFlow { nav.current }.collect { if (it == Screen.Player) showing?.cancel() } }
        container.sync.toasts.collect { msg ->
            showing?.cancel()
            if (nav.current == Screen.Player) return@collect
            // The join form shows connection problems itself; a snackbar there would cover its buttons.
            if (nav.current == Screen.SyncplayConnect && msg.from == null && container.sync.room.value.status != ConnectionStatus.CONNECTED) return@collect
            showing = launch { snackbar.showSnackbar(msg.from?.let { "$it: ${msg.text}" } ?: msg.text) }
        }
    }
    PlatformBackHandler(enabled = nav.canGoBack && nav.current != Screen.Player) { nav.back() }

    val screen = nav.current
    // The player covers the tabs while it's showing in landscape; in portrait, the player shows with the tabs.
    val sections = tabs.filter { it.screen != Screen.Player }
    val selectedTab = if (screen == Screen.Player) {
        tabs.first { it.screen == Screen.Player }
    } else {
        stack.lastOrNull { s -> sections.any { it.screen::class == s::class } }?.let { s -> sections.first { it.screen::class == s::class } }
            ?: tabs.first()
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().exposeTestTags()) {
            val isPortrait = maxWidth < maxHeight
            val style = when {
                kind == DeviceKind.DESKTOP -> SectionNav.SIDEBAR
                screen == Screen.Player && isPortrait -> SectionNav.BAR
                kind.isLarge -> SectionNav.RAIL
                else -> SectionNav.BAR
            }
            if (screen == Screen.Player && kind == DeviceKind.DESKTOP && !desktopPip) {
                // On desktop the player is fullscreen, covering the window
                CompositionLocalProvider(LocalContentColor provides AppColors.Text) { MobilePlayerScreen(container, nav) }
            } else {
                MobileScaffold(
                    container = container,
                    snackbar = snackbar,
                    selected = selectedTab,
                    style = style,
                    screen = screen,
                    isPortrait = isPortrait,
                    desktopPip = desktopPip,
                    onTab = {
                        if (it.screen == Screen.Player) {
                            if (nav.current != Screen.Player) nav.push(Screen.Player)
                        } else {
                            nav.switchTab(it.screen)
                        }
                    },
                    onOpenPlayer = {
                        if (desktopPip) pip.toggle()
                        nav.push(Screen.Player)
                    },
                ) {
                    if (screen == Screen.Player) {
                        CompositionLocalProvider(LocalContentColor provides AppColors.Text) { MobilePlayerScreen(container, nav) }
                    } else {
                    saveable.SaveableStateProvider(screen.key) {
                        when (screen) {
                            Screen.Home -> MobileHomeScreen(container, nav, kind)
                            Screen.SyncplayConnect, Screen.Room -> MobileRoomScreen(container, nav)
                            Screen.JellyfinLogin -> MobileJellyfinScreen(container, nav)
                            Screen.PlexLogin -> MobilePlexScreen(container, nav)
                            Screen.Servers -> MobileServersScreen(container, nav)
                            Screen.Settings -> MobileSettingsScreen(container, nav)
                            Screen.LocalFiles -> MobileLocalFilesScreen(container, nav)
                            Screen.Licenses -> MobileLicensesScreen(nav)
                            Screen.MalLogin -> MobileMalLoginScreen(container, nav)
                            Screen.Mal -> MobileMalScreen(container, nav)
                            Screen.WhatsNew -> MobileWhatsNewScreen(container, nav)
                            is Screen.MalEntry -> MobileMalEntryScreen(container, nav, screen.item)
                            is Screen.Browse -> MobileBrowseScreen(container, nav, screen.item, kind)
                            is Screen.Search -> MobileSearchScreen(container, nav, screen.initialQuery, screen.pickFor, kind)
                            Screen.Player -> Unit
                        }
                    }
                }
            }
        }
            // A dialog would fill the small picture-in-picture window.
            if (!PlayerPictureInPicture.active || pictureInPictureWindow) {
                nav.actionItem?.let { item ->
                    MobileItemActionsSheet(container, item, onDismiss = { nav.actionItem = null }, onMal = { nav.push(Screen.MalEntry(item)) })
                }
                MobileDeviceAccessDialog(container)
            }
        }
    }
}

@Composable
private fun MobileScaffold(
    container: AppContainer,
    snackbar: SnackbarHostState,
    selected: Tab,
    style: SectionNav,
    screen: Screen,
    isPortrait: Boolean,
    desktopPip: Boolean = false,
    onTab: (Tab) -> Unit,
    onOpenPlayer: () -> Unit,
    content: @Composable () -> Unit,
) {
    val showBottomBar = style == SectionNav.BAR && (screen != Screen.Player || isPortrait)
    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (screen != Screen.Player) {
                        MiniPlayerBar(container, onOpenPlayer)
                    }
                    NavigationBar(containerColor = AppColors.Surface) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selected,
                                onClick = { onTab(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("tab_${tab.label}"),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val contentPadding = if (screen == Screen.Player && !isPortrait) PaddingValues(0.dp) else padding
        Row(Modifier.fillMaxSize().padding(contentPadding)) {
            when (style) {
                SectionNav.RAIL -> if (screen != Screen.Player) {
                    NavigationRail(containerColor = AppColors.Surface) {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == selected,
                                onClick = { onTab(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("tab_${tab.label}"),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = false,
                            onClick = { container.exitApp() },
                            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Exit YarmiplayTV") },
                            label = { Text("Exit") },
                            modifier = Modifier.testTag("rail_exit"),
                        )
                    }
                }
                SectionNav.SIDEBAR -> if (screen != Screen.Player || desktopPip) {
                    Sidebar(container, selected, onTab)
                    VerticalDivider(color = AppColors.SurfaceHigh)
                }
                SectionNav.BAR -> Unit
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize()) { content() }
                if (style != SectionNav.BAR && screen != Screen.Player) {
                    MiniPlayerBar(container, onOpenPlayer)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(container: AppContainer, onOpenPlayer: () -> Unit) {
    val state by container.player.state.collectAsStateWithLifecycle()
    if (!state.fileLoaded) return

    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    var position by remember { mutableDoubleStateOf(container.player.currentPosition()) }
    LaunchedEffect(state.fileLoaded, state.paused) {
        while (state.fileLoaded) {
            position = container.player.currentPosition()
            delay(500)
        }
    }

    val isAudio = FileNames.isAudio(nowPlaying?.fileName ?: state.url.orEmpty())
    val title = nowPlaying?.title ?: FileNames.baseName(state.url.orEmpty()).ifEmpty { "Playing" }
    val subtitle = nowPlaying?.episode?.seriesName ?: if (isAudio) "Music" else "Video"

    Surface(
        color = AppColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer)
            .testTag("mini_player"),
    ) {
        Column {
            val progress = if (state.duration > 0) (position / state.duration).toFloat().coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AppColors.Accent,
                trackColor = AppColors.SurfaceHigh,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isAudio) Icons.Filled.Audiotrack else Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = AppColors.Accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val inRoom = room.status == ConnectionStatus.CONNECTED
                val skips = inRoom && room.playlist.isNotEmpty()
                if (skips) {
                    IconButton(
                        onClick = { container.playlist.skip(false) },
                        enabled = container.playlist.skipTarget(room, forward = false) != null,
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track", tint = AppColors.Text)
                    }
                }
                IconButton(
                    onClick = { container.player.setPaused(!state.paused) },
                    modifier = Modifier.testTag("mini_play_pause"),
                ) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.paused) "Play" else "Pause",
                        tint = AppColors.Text,
                    )
                }
                if (skips) {
                    IconButton(
                        onClick = { container.playlist.skip(true) },
                        enabled = container.playlist.skipTarget(room, forward = true) != null,
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next track", tint = AppColors.Text)
                    }
                }
            }
        }
    }
}

/** Desktop: the app's name, then the sections as a list like the Settings categories. */
@Composable
private fun Sidebar(container: AppContainer, selected: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp).testTag("sidebar")) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(appLogoPainter(), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Text("YarmiplayTV", style = MaterialTheme.typography.titleLarge)
        }
        tabs.forEach { tab -> SideNavItem(tab.label, tab.icon, tab == selected, { onTab(tab) }, Modifier.testTag("tab_${tab.label}")) }
        Spacer(Modifier.weight(1f))
        SideNavItem("Exit", Icons.AutoMirrored.Filled.Logout, false, { container.exitApp() }, Modifier.testTag("sidebar_exit"))
    }
}

private val SIDEBAR_WIDTH = 220.dp
