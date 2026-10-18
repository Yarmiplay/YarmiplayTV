package com.yarmiplaytv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.browse.openItem
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Dot
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.components.Pill
import com.yarmiplaytv.ui.components.PosterCard
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.TvTile
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.settings.TvWhatsNewCard
import com.yarmiplaytv.ui.settings.whatsNewCardVersion
import com.yarmiplaytv.ui.shared.ServerRecent
import com.yarmiplaytv.ui.shared.readyButtonLabel
import com.yarmiplaytv.ui.shared.recentByServer
import com.yarmiplaytv.ui.shared.savedRoomLabel
import com.yarmiplaytv.ui.shared.serversDetail
import com.yarmiplaytv.ui.shared.serversTitle
import com.yarmiplaytv.ui.shared.updateStateLine
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.PlayStore
import com.yarmiplaytv.update.StoreUpdates
import com.yarmiplaytv.update.UpdateState
import com.yarmiplaytv.update.openStoreApp
import kotlinx.coroutines.flow.map

@Composable
fun HomeScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val jellyfin by container.jellyfinServers.collectAsStateWithLifecycle()
    val plex by container.plexServers.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()
    val storeUpdate by container.updates.storeUpdate.collectAsStateWithLifecycle()
    var libraries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var serverRecents by remember { mutableStateOf<List<ServerRecent>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }

    LaunchedEffect(source) {
        val s = source ?: run { libraries = emptyList(); recent = emptyList(); serverRecents = emptyList(); return@LaunchedEffect }
        loadError = null
        runCatching { libraries = s.libraries() }.onFailure { loadError = it.message }
        if (s is CompositeMediaSource) {
            recent = emptyList()
            serverRecents = recentByServer(s.sources)
        } else {
            serverRecents = emptyList()
            runCatching { recent = s.recent() }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(com.yarmiplaytv.shared.R.drawable.ic_logo), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Text("YarmiplayTV", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                // The tiles under these buttons would otherwise win over the full-width update notice.
                val toNotice = if (update != null || storeUpdate != null) Modifier.focusProperties { down = updateFocus } else Modifier
                if (source != null) IconAction(Icons.Filled.Search, "Search", { nav.push(Screen.Search()) }, toNotice)
                Spacer(Modifier.width(8.dp))
                IconAction(Icons.Filled.Settings, "Settings", { nav.push(Screen.Settings) }, toNotice)
                Spacer(Modifier.width(8.dp))
                IconAction(Icons.AutoMirrored.Filled.Logout, "Exit", { container.exitApp() }, toNotice)
            }
        }
        update?.let { u ->
            item(key = "update") {
                UpdateNotice(container, u, {
                    container.updates.dismiss()
                    runCatching { firstFocus.requestFocus() }
                }, Modifier.padding(horizontal = 48.dp), Modifier.focusRequester(updateFocus))
            }
        }
        storeUpdate?.let { store ->
            item(key = "store-update") {
                StoreUpdateNotice(container, store, {
                    container.updates.dismissStoreUpdate()
                    runCatching { firstFocus.requestFocus() }
                }, Modifier.padding(horizontal = 48.dp), Modifier.focusRequester(updateFocus))
            }
        }
        // Only with something to show: an empty item would still take the list's gap above the tiles.
        if (whatsNewCardVersion(settings.lastSeenVersion, container.appVersion) != null) {
            item(key = "whats-new") { TvWhatsNewCard(container, nav, Modifier.padding(horizontal = 48.dp)) }
        }
        item(key = "tiles") {
            // Three to a row, sharing its width inside the TV-safe area, so their text has room to show in full.
            TileGrid(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)) {
                val (title, detail, color) = when (room.status) {
                    ConnectionStatus.CONNECTED -> Triple(
                        "Room: ${ManagedRooms.label(room.room)}",
                        "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                        AppColors.Ready,
                    )
                    ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Triple("Connecting…", container.sync.client?.config?.host ?: "", AppColors.NotReady)
                    ConnectionStatus.DISCONNECTED -> Triple(
                        "Join a Syncplay room",
                        container.settings.value.savedRooms.firstOrNull()?.let { "Rejoin ${savedRoomLabel(it)}" } ?: "Watch in sync with friends",
                        AppColors.TextDim,
                    )
                }
                StatusTile(Icons.Filled.Groups, title, detail, color, Modifier.focusRequester(firstFocus)) {
                    nav.push(if (room.status == ConnectionStatus.CONNECTED) Screen.Room else Screen.SyncplayConnect)
                }
                nowPlaying?.let { np ->
                    StatusTile(Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent) { nav.push(Screen.Player) }
                }
                StatusTile(
                    Icons.Filled.Cloud,
                    serversTitle(JellyfinSource.KIND, jellyfin),
                    if (jellyfin.isNotEmpty()) serversDetail(jellyfin) else "Browse your media server",
                    if (jellyfin.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                ) { nav.push(if (jellyfin.isEmpty()) Screen.JellyfinLogin else Screen.Servers) }
                StatusTile(
                    Icons.Filled.Cloud,
                    serversTitle(PlexSource.KIND, plex),
                    if (plex.isNotEmpty()) serversDetail(plex) else "Browse your Plex server",
                    if (plex.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                ) { nav.push(if (plex.isEmpty()) Screen.PlexLogin else Screen.Servers) }
                if (nowPlaying == null) {
                    StatusTile(
                        Icons.Filled.PlayCircle,
                        "Player",
                        "Open player",
                        AppColors.TextDim,
                    ) { nav.push(Screen.Player) }
                }
                if (container.mal.client.configured) {
                    val malAccount by container.settings.map { it.mal?.userName }.collectAsStateWithLifecycle(container.settings.value.mal?.userName)
                    val user = malAccount
                    StatusTile(
                        Icons.Filled.Bookmarks,
                        "MyAnimeList",
                        user?.let { "Signed in as $it" } ?: "Keep your anime list up to date",
                        if (user != null) AppColors.Ready else AppColors.TextDim,
                        Modifier.testTag("card_mal"),
                    ) { nav.push(if (user == null) Screen.MalLogin else Screen.Mal) }
                }
                StatusTile(
                    Icons.AutoMirrored.Filled.Logout,
                    "Exit YarmiplayTV",
                    "Close the app completely",
                    AppColors.NotReady,
                    Modifier.testTag("card_exit"),
                ) { container.exitApp() }
            }
        }
        if (loadError != null) {
            item(key = "load_error") { Text("Couldn't load libraries: $loadError", color = AppColors.Error, modifier = Modifier.padding(horizontal = 48.dp)) }
        }
        if (libraries.isNotEmpty()) {
            item(key = "libraries") {
                Column {
                    SectionTitle("Libraries", Modifier.padding(horizontal = 48.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
                        items(libraries, key = { it.uniqueKey }) { lib ->
                            PosterCard(
                                title = lib.name,
                                subtitle = (source as? CompositeMediaSource)?.sourceOf(lib)?.displayName,
                                imageUrl = source?.imageUrl(lib, 480),
                                aspectRatio = 16f / 9f,
                                placeholderIcon = when (lib.collectionType) {
                                    "tvshows" -> Icons.Filled.Tv
                                    "movies" -> Icons.Filled.Movie
                                    else -> Icons.Filled.Folder
                                },
                                onClick = { nav.push(Screen.Browse(lib)) },
                                modifier = Modifier.width(260.dp),
                                titleLines = Int.MAX_VALUE,
                                subtitleLines = Int.MAX_VALUE,
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") { RecentRow(container, nav, "Continue watching & recently added", recent, source) }
        }
        serverRecents.forEach { row ->
            if (row.items.isNotEmpty()) {
                item(key = "recent_${row.server.key}") { RecentRow(container, nav, row.title, row.items, row.server) }
            } else if (row.error != null) {
                item(key = "recent_${row.server.key}") {
                    Text("Couldn't load ${row.server.displayName}: ${row.error}", color = AppColors.Error, modifier = Modifier.padding(horizontal = 48.dp))
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecentRow(container: AppContainer, nav: Navigator, title: String, items: List<MediaItem>, source: MediaSource?) {
    Column {
        SectionTitle(title, Modifier.padding(horizontal = 48.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
            items(items, key = { it.uniqueKey }) { item ->
                PosterCard(
                    title = item.seriesName ?: item.name,
                    subtitle = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.year?.toString(),
                    imageUrl = source?.imageUrl(item, 480),
                    aspectRatio = 16f / 9f,
                    placeholderIcon = Icons.Filled.Movie,
                    onClick = { openItem(container, nav, item) },
                    modifier = Modifier.width(260.dp),
                    titleLines = Int.MAX_VALUE,
                    subtitleLines = Int.MAX_VALUE,
                )
            }
        }
    }
}

/**
 * A newer version. The download-page APK installs it from here: OK downloads it and hands it to Android's
 * installer, with "Not now" beside it. Without an installer (TVs rarely have a browser) it says how to get it with
 * Downloader, like the download page, and OK dismisses it. A full-width tile, so the D-pad reaches it from the
 * tiles below.
 */
@Composable
private fun UpdateNotice(container: AppContainer, state: UpdateState, onDismiss: () -> Unit, modifier: Modifier = Modifier, tileModifier: Modifier = Modifier) {
    val updates = container.updates
    val canInstall = updates.installer != null
    val context = LocalContext.current
    val hasStore = remember(context) { PlayStore.canLaunchStore(context) }
    val shortLink = state.update.pageUrl.substringAfter("://").trimEnd('/') + "/a"
    // Coming back from Android's "install unknown apps" setting changes what the notice says.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    key(lifecycleState) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTile(
                onClick = {
                    when {
                        !canInstall -> if (hasStore) PlayStore.launchStore(context) else onDismiss()
                        state is UpdateState.Ready -> updates.restartToInstall()
                        state is UpdateState.Downloading -> Unit
                        else -> updates.download(thenRestart = true)
                    }
                },
                modifier = tileModifier.weight(1f).testTag("update_banner"),
                focusedScale = 1.02f,
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("YarmiplayTV ${state.update.version} is available", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (canInstall) updateStateLine(updates, state)
                            else if (hasStore) "Select to update in Google Play Store"
                            else "To install it, open Downloader and enter $shortLink",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextDim,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    when {
                        !canInstall -> if (hasStore) Pill("Update", AppColors.Accent) else Pill("Not now", AppColors.TextDim)
                        state is UpdateState.Ready -> Pill(readyButtonLabel(updates), AppColors.Accent)
                        state is UpdateState.Downloading -> Pill("${(state.progress * 100).toInt()}%", AppColors.TextDim)
                        else -> Pill(if (state is UpdateState.Failed) "Try again" else "Install", AppColors.Accent)
                    }
                }
            }
            if (canInstall) {
                ActionButton(if (state is UpdateState.Downloading) "Cancel" else "Not now", {
                    if (state is UpdateState.Downloading) updates.dismiss(remember = false) else onDismiss()
                })
            } else if (hasStore) {
                ActionButton("Not now", onDismiss)
            }
        }
    }
}

/** A home tile: what it is and how it stands, all of it shown (the text wraps rather than being cut off). */
/**
 * A newer version in the store that installed the app (Google Play): OK opens its page in the store's app, where
 * Update is (its in-app update where the store app can't be opened), or not now.
 */
@Composable
private fun StoreUpdateNotice(container: AppContainer, store: StoreUpdates, onDismiss: () -> Unit, modifier: Modifier = Modifier, tileModifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTile(
            onClick = {
                if (!store.update()) {
                    if (store.pageUrl?.let { openStoreApp(context, it) } != true) container.updates.updateFromStore(uriHandler::openUri)
                }
            },
            modifier = tileModifier.weight(1f).testTag("update_banner"),
            focusedScale = 1.02f,
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Text("A new version of YarmiplayTV is available in ${store.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Pill("Update", AppColors.Accent)
            }
        }
        ActionButton("Not now", onDismiss)
    }
}

@Composable
private fun StatusTile(icon: ImageVector, title: String, detail: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvTile(onClick = onClick, modifier = modifier.heightIn(min = 120.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Dot(color)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
        }
    }
}

/** The home tiles, [TILES_PER_ROW] to a row and as tall as the row's tile with the most text. */
@Composable
private fun TileGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = TILE_GAP.roundToPx()
        val width = constraints.maxWidth
        val cellWidth = (width - gap * (TILES_PER_ROW - 1)) / TILES_PER_ROW
        val rows = measurables.chunked(TILES_PER_ROW).map { row ->
            val height = row.maxOf { it.minIntrinsicHeight(cellWidth) }
            row.map { it.measure(Constraints.fixed(cellWidth, height)) }
        }
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        layout(width, rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)) {
            var y = 0
            rows.forEachIndexed { i, row ->
                row.forEachIndexed { j, tile -> tile.placeRelative(j * (cellWidth + gap), y) }
                y += rowHeights[i] + gap
            }
        }
    }
}

private const val TILES_PER_ROW = 3
private val TILE_GAP = 20.dp
