package com.syncplaytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncplaytv.AppContainer
import com.syncplaytv.media.MediaItem
import com.syncplaytv.ui.browse.BrowseScreen
import com.syncplaytv.ui.browse.ItemActionsDialog
import com.syncplaytv.ui.browse.SearchScreen
import com.syncplaytv.ui.components.ToastHost
import com.syncplaytv.ui.connect.JellyfinLoginScreen
import com.syncplaytv.ui.connect.SyncplayConnectScreen
import com.syncplaytv.ui.home.HomeScreen
import com.syncplaytv.ui.player.PlayerScreen
import com.syncplaytv.ui.settings.SettingsScreen
import com.syncplaytv.ui.theme.AppColors
import kotlinx.coroutines.flow.drop

sealed interface Screen {
    val key: String

    data object Home : Screen { override val key = "home" }
    data object SyncplayConnect : Screen { override val key = "syncplay" }
    data object JellyfinLogin : Screen { override val key = "jellyfin" }
    data object Player : Screen { override val key = "player" }
    data object Settings : Screen { override val key = "settings" }
    data class Browse(val item: MediaItem) : Screen { override val key = "browse/${item.id}" }

    /** [pickFor] is set when the user is choosing a substitute for a playlist entry that wasn't found. */
    data class Search(val initialQuery: String = "", val pickFor: String? = null) : Screen {
        override val key = "search/$pickFor"
    }
}

class Navigator(private val stack: SnapshotStateList<Screen>, private val onRemoved: (Screen) -> Unit = {}) {
    val current: Screen get() = stack.last()

    /** Playable item whose action sheet (play in room / play here / add to playlist) is open. */
    var actionItem by mutableStateOf<MediaItem?>(null)
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        if (current == screen) return
        if (screen == Screen.Player && stack.remove(Screen.Player)) onRemoved(Screen.Player)
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) onRemoved(stack.removeAt(stack.lastIndex))
    }

    fun popTo(screen: Screen) {
        while (stack.size > 1 && current != screen) onRemoved(stack.removeAt(stack.lastIndex))
    }
}

@Composable
fun AppRoot(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val saveable = rememberSaveableStateHolder()
    val nav = remember { Navigator(stack) { saveable.removeState(it.key) } }

    // The room switched files (or the user started playback): bring up the player.
    LaunchedEffect(Unit) {
        container.playlist.openPlayerRequests.drop(1).collect {
            if (nav.current != Screen.Player && nav.current !is Screen.Search) nav.push(Screen.Player)
        }
    }

    BackHandler(enabled = nav.canGoBack && nav.current != Screen.Player) { nav.back() }

    CompositionLocalProvider(LocalContentColor provides AppColors.Text) {
    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        val screen = nav.current
        saveable.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Home -> HomeScreen(container, nav)
                Screen.SyncplayConnect -> SyncplayConnectScreen(container, nav)
                Screen.JellyfinLogin -> JellyfinLoginScreen(container, nav)
                Screen.Player -> PlayerScreen(container, nav)
                Screen.Settings -> SettingsScreen(container, nav)
                is Screen.Browse -> BrowseScreen(container, nav, screen.item)
                is Screen.Search -> SearchScreen(container, nav, screen.initialQuery, screen.pickFor)
            }
        }
        if (screen != Screen.Player) {
            ToastHost(container.sync.toasts, Modifier.align(Alignment.BottomEnd).padding(32.dp))
        }
        nav.actionItem?.let { item ->
            ItemActionsDialog(container, item, onDismiss = { nav.actionItem = null })
        }
    }
    }
}
