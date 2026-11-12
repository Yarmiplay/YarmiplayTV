package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.ServerRecent
import com.yarmiplaytv.ui.shared.recentByServer
import com.yarmiplaytv.ui.shared.savedRoomLabel
import com.yarmiplaytv.ui.shared.serversDetail
import com.yarmiplaytv.ui.shared.serversTitle
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import kotlinx.coroutines.flow.map

private val ContentMaxWidth = 1520.dp
private val MinCardWidth = 400.dp
private val CardGap = 16.dp
private val PosterWidth = 240.dp

/**
 * Desktop home: a page title and a grid of the same cards as the phone, kept to a readable width so a
 * maximized window doesn't stretch them edge to edge. The side list carries the logo and the app name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopHomeScreen(container: AppContainer, nav: Navigator) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()
    val jellyfin by container.jellyfinServers.collectAsStateWithLifecycle()
    val plex by container.plexServers.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val localFiles by container.local.files.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    var libraries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var serverRecents by remember { mutableStateOf<List<ServerRecent>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

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

    Box(Modifier.fillMaxSize().testTag("home")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pad = when {
                maxWidth < 760.dp -> 20.dp
                maxWidth < 1100.dp -> 28.dp
                else -> 36.dp
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = pad)
                        .padding(bottom = 28.dp)
                        .testTag("desktop_home_content"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Home",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.Text,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Watch in sync with friends",
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextDim,
                            )
                        }
                        TextButton(
                            onClick = { container.exitApp() },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("home_exit_app"),
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Text),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Exit", maxLines = 1, softWrap = false)
                        }
                    }
                    UpdateBanner(container, Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    WhatsNewCard(container, nav, Modifier.fillMaxWidth().padding(bottom = 16.dp), desktop = true)
                    DesktopCardGrid {
                        val (title, detail, color) = when (room.status) {
                            ConnectionStatus.CONNECTED -> Triple(
                                "Room: ${ManagedRooms.label(room.room)}",
                                "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                                AppColors.Ready,
                            )
                            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Triple(
                                "Connecting…",
                                container.sync.client?.config?.host ?: "",
                                AppColors.NotReady,
                            )
                            ConnectionStatus.DISCONNECTED -> Triple(
                                "Join a Syncplay room",
                                settings.savedRooms.firstOrNull()?.let { "Rejoin ${savedRoomLabel(it)} on ${it.host}" }
                                    ?: "Watch in sync with friends",
                                AppColors.TextDim,
                            )
                        }
                        DesktopActionCard(
                            Icons.Filled.Groups, title, detail, color,
                            { nav.switchTab(Screen.SyncplayConnect) },
                            Modifier.testTag("card_room"),
                        )
                        nowPlaying?.let { np ->
                            DesktopActionCard(
                                Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent,
                                { nav.push(Screen.Player) },
                                Modifier.testTag("card_now_playing"),
                            )
                        }
                        DesktopActionCard(
                            Icons.Filled.Cloud,
                            serversTitle(JellyfinSource.KIND, jellyfin),
                            if (jellyfin.isNotEmpty()) serversDetail(jellyfin) else "Browse your media server",
                            if (jellyfin.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                            { nav.push(if (jellyfin.isEmpty()) Screen.JellyfinLogin else Screen.Servers) },
                            Modifier.testTag("card_jellyfin"),
                        )
                        DesktopActionCard(
                            Icons.Filled.Cloud,
                            serversTitle(PlexSource.KIND, plex),
                            if (plex.isNotEmpty()) serversDetail(plex) else "Browse your Plex server",
                            if (plex.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                            { nav.push(if (plex.isEmpty()) Screen.PlexLogin else Screen.Servers) },
                            Modifier.testTag("card_plex"),
                        )
                        DesktopActionCard(
                            Icons.Filled.SdStorage,
                            "Files on this device",
                            when {
                                folders.isEmpty() -> "Open a video or add media folders"
                                else -> "${localFiles.size} videos in ${folders.size} folder${if (folders.size == 1) "" else "s"}"
                            },
                            if (folders.isNotEmpty()) AppColors.Ready else AppColors.TextDim,
                            { nav.push(Screen.LocalFiles) },
                            Modifier.testTag("card_local"),
                        )
                        if (container.mal.client.configured) {
                            val malAccount by container.settings.map { it.mal?.userName }.collectAsStateWithLifecycle(container.settings.value.mal?.userName)
                            val user = malAccount
                            DesktopActionCard(
                                Icons.Filled.Bookmarks,
                                "MyAnimeList",
                                user?.let { "Signed in as $it · your list follows what you watch" } ?: "Keep your anime list up to date as you watch",
                                if (user != null) AppColors.Ready else AppColors.TextDim,
                                { nav.push(if (user == null) Screen.MalLogin else Screen.Mal) },
                                Modifier.testTag("card_mal"),
                            )
                        }
                    }
                    loadError?.let { err ->
                        Text("Couldn't load libraries: $err", color = AppColors.Error, modifier = Modifier.padding(top = 16.dp))
                    }
                    if (libraries.isNotEmpty()) {
                        DesktopSectionTitle("Libraries")
                        PosterFlow(libraries.map { lib ->
                            PosterModel(
                                key = lib.uniqueKey,
                                title = lib.name,
                                subtitle = (source as? CompositeMediaSource)?.sourceOf(lib)?.displayName,
                                imageUrl = source?.imageUrl(lib, 480),
                                placeholder = when (lib.collectionType) {
                                    "tvshows" -> Icons.Filled.Tv
                                    "movies" -> Icons.Filled.Movie
                                    else -> Icons.Filled.Folder
                                },
                                onClick = { nav.push(Screen.Browse(lib)) },
                            )
                        })
                    }
                    if (recent.isNotEmpty()) {
                        DesktopSectionTitle("Continue watching & recently added")
                        PosterFlow(recent.map { item -> posterModel(container, nav, item, source) })
                    }
                    serverRecents.forEach { row ->
                        if (row.items.isNotEmpty()) {
                            DesktopSectionTitle(row.title)
                            PosterFlow(row.items.map { item -> posterModel(container, nav, item, row.server) })
                        } else if (row.error != null) {
                            Text(
                                "Couldn't load ${row.server.displayName}: ${row.error}",
                                color = AppColors.Error,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun posterModel(container: AppContainer, nav: Navigator, item: MediaItem, source: MediaSource?): PosterModel =
    PosterModel(
        key = item.uniqueKey,
        title = item.seriesName ?: item.name,
        subtitle = if (item.type == MediaItemType.EPISODE) item.displayTitle else item.year?.toString(),
        imageUrl = source?.imageUrl(item, 480),
        placeholder = Icons.Filled.Movie,
        onClick = { openItem(container, nav, item) },
    )

private data class PosterModel(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val placeholder: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun DesktopSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.Text,
        modifier = Modifier.padding(top = 28.dp, bottom = 14.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterFlow(posters: List<PosterModel>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        posters.forEach { poster ->
            key(poster.key) {
                DesktopPoster(poster)
            }
        }
    }
}

@Composable
private fun DesktopPoster(poster: PosterModel) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val border = when {
        focused -> BorderStroke(2.dp, AppColors.Accent)
        hovered -> BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.65f))
        else -> BorderStroke(1.dp, Color.Transparent)
    }
    Column(
        Modifier
            .width(PosterWidth)
            .border(border, shape)
            .clip(shape)
            .background(if (hovered || focused) AppColors.Surface else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = poster.onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(poster.placeholder, contentDescription = null, tint = AppColors.TextDim, modifier = Modifier.size(36.dp))
            if (poster.imageUrl != null) {
                AsyncImage(
                    model = poster.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            poster.title,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Text,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
        if (!poster.subtitle.isNullOrBlank()) {
            Text(
                poster.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun DesktopActionCard(
    icon: ImageVector,
    title: String,
    detail: String,
    dot: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val border = when {
        focused -> BorderStroke(2.dp, AppColors.Accent)
        hovered -> BorderStroke(1.5.dp, AppColors.Accent.copy(alpha = 0.7f))
        else -> BorderStroke(1.dp, AppColors.SurfaceHigh)
    }
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 92.dp).pointerHoverIcon(PointerIcon.Hand),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface, contentColor = AppColors.OnSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, hoveredElevation = 3.dp, focusedElevation = 0.dp),
        border = border,
        interactionSource = interaction,
    ) {
        SurfaceInk {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextDim)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            }
        }
    }
}

/** As many equal columns of at least [MinCardWidth] as fit; a row is as tall as its tallest card. */
@Composable
private fun DesktopCardGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        if (measurables.isEmpty()) {
            layout(constraints.maxWidth, 0) {}
        } else {
            val gap = CardGap.roundToPx()
            val width = constraints.maxWidth
            val columns = ((width + gap) / (MinCardWidth.roundToPx() + gap)).coerceIn(1, measurables.size)
            val cellWidth = (width - gap * (columns - 1)) / columns
            val rows = measurables.chunked(columns).map { row ->
                val height = row.maxOf { it.minIntrinsicHeight(cellWidth) }
                row.map { it.measure(Constraints.fixed(cellWidth, height)) }
            }
            val rowHeights = rows.map { row -> row.maxOf { it.height } }
            val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                rows.forEachIndexed { i, row ->
                    row.forEachIndexed { j, placeable -> placeable.placeRelative(j * (cellWidth + gap), y) }
                    y += rowHeights[i] + gap
                }
            }
        }
    }
}
