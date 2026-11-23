package com.yarmiplaytv.ui.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.jellyfin.JellyfinSource
import com.yarmiplaytv.media.plex.PlexSource
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors

/** Which home card it is; also its test tag ("card_room", …). */
enum class HomeCardKind { ROOM, NOW_PLAYING, JELLYFIN, PLEX, LOCAL, PLAYER, MAL, EXIT;
    val tag: String get() = "card_${name.lowercase()}"
}

/**
 * One of the home screen's cards, the same on TV, phones, tablets and desktop; each home draws them its own way.
 * [dot] says how it stands: green when connected or signed in, amber while connecting, the accent for what's playing,
 * and none for something not set up yet (the [detail] says what it would do).
 */
data class HomeCard(
    val kind: HomeCardKind,
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val dot: Color?,
    val onClick: () -> Unit,
    /** Drawn in the warning colour (the TV's Exit card). */
    val warning: Boolean = false,
)

/** What the home cards say for this device: [tv] has a Player and an Exit card instead of Files, and shorter details. */
@Composable
fun homeCards(container: AppContainer, nav: Navigator, tv: Boolean): List<HomeCard> {
    val room by container.sync.roomView.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val jellyfin by container.jellyfinServers.collectAsStateWithLifecycle()
    val plex by container.plexServers.collectAsStateWithLifecycle()
    val nowPlaying by container.playlist.nowPlaying.collectAsStateWithLifecycle()
    val localFiles by container.local.files.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    return buildList {
        val roomTarget = { if (tv) nav.push(if (room.status == ConnectionStatus.CONNECTED) Screen.Room else Screen.SyncplayConnect) else nav.switchTab(Screen.SyncplayConnect) }
        add(
            when (room.status) {
                ConnectionStatus.CONNECTED -> HomeCard(
                    HomeCardKind.ROOM, Icons.Filled.Groups,
                    "Room: ${ManagedRooms.label(room.room)}",
                    "${room.users.size} watching · ${room.users.count { it.isReady == true }} ready",
                    AppColors.Ready, roomTarget,
                )
                ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> HomeCard(
                    HomeCardKind.ROOM, Icons.Filled.Groups, "Connecting…", container.sync.client?.config?.host ?: "", AppColors.NotReady, roomTarget,
                )
                ConnectionStatus.DISCONNECTED -> HomeCard(
                    HomeCardKind.ROOM, Icons.Filled.Groups, "Join a Syncplay room",
                    settings.savedRooms.firstOrNull()?.let { if (tv) "Rejoin ${savedRoomLabel(it)}" else "Rejoin ${savedRoomLabel(it)} on ${it.host}" }
                        ?: "Watch in sync with friends",
                    null, roomTarget,
                )
            },
        )
        nowPlaying?.let { np ->
            add(HomeCard(HomeCardKind.NOW_PLAYING, Icons.Filled.PlayCircle, "Now playing", np.title, AppColors.Accent, { nav.push(Screen.Player) }))
        }
        add(
            HomeCard(
                HomeCardKind.JELLYFIN, Icons.Filled.Dns,
                serversTitle(JellyfinSource.KIND, jellyfin),
                if (jellyfin.isNotEmpty()) serversDetail(jellyfin) else "Browse your Jellyfin server",
                if (jellyfin.isNotEmpty()) AppColors.Ready else null,
                { nav.push(if (jellyfin.isEmpty()) Screen.JellyfinLogin else Screen.Servers) },
            ),
        )
        add(
            HomeCard(
                HomeCardKind.PLEX, Icons.Filled.VideoLibrary,
                serversTitle(PlexSource.KIND, plex),
                if (plex.isNotEmpty()) serversDetail(plex) else "Browse your Plex server",
                if (plex.isNotEmpty()) AppColors.Ready else null,
                { nav.push(if (plex.isEmpty()) Screen.PlexLogin else Screen.Servers) },
            ),
        )
        // TV reaches its files from Browse; a card here would move the tiles its remote steps through.
        if (!tv) add(
            HomeCard(
                HomeCardKind.LOCAL, Icons.Filled.SdStorage, "Files on this device",
                if (folders.isEmpty()) "Open a video or add media folders"
                else "${localFiles.size} videos in ${folders.size} folder${if (folders.size == 1) "" else "s"}",
                if (folders.isNotEmpty()) AppColors.Ready else null,
                { nav.push(Screen.LocalFiles) },
            ),
        )
        if (tv && nowPlaying == null) {
            add(HomeCard(HomeCardKind.PLAYER, Icons.Filled.PlayCircle, "Player", "Open player", null, { nav.push(Screen.Player) }))
        }
        if (container.mal.client.configured) {
            val user = settings.mal?.userName
            add(
                HomeCard(
                    HomeCardKind.MAL, Icons.Filled.Bookmarks, "MyAnimeList",
                    when {
                        user != null && tv -> "Signed in as $user"
                        user != null -> "Signed in as $user · your list follows what you watch"
                        tv -> "Keep your anime list up to date"
                        else -> "Keep your anime list up to date as you watch"
                    },
                    if (user != null) AppColors.Ready else null,
                    { nav.push(if (user == null) Screen.MalLogin else Screen.Mal) },
                ),
            )
        }
        if (tv) {
            add(HomeCard(HomeCardKind.EXIT, Icons.AutoMirrored.Filled.Logout, "Exit YarmiplayTV", "Close the app completely", null, { container.exitApp() }, warning = true))
        }
    }
}
