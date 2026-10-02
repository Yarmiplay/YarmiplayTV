package com.yarmiplaytv.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.yarmiplaytv.media.MediaItem

sealed interface Screen {
    val key: String

    data object Home : Screen { override val key = "home" }
    data object SyncplayConnect : Screen { override val key = "syncplay" }
    data object JellyfinLogin : Screen { override val key = "jellyfin" }
    data object Player : Screen { override val key = "player" }
    data object Settings : Screen { override val key = "settings" }
    data object LocalFiles : Screen { override val key = "local" }
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

    /** Switches to a top-level destination (bottom bar / rail), dropping everything above Home. */
    fun switchTab(screen: Screen) {
        popTo(Screen.Home)
        if (screen != Screen.Home) push(screen)
    }
}
