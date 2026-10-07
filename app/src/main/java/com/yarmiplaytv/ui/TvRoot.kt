package com.yarmiplaytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.browse.BrowseScreen
import com.yarmiplaytv.ui.browse.ItemActionsDialog
import com.yarmiplaytv.ui.browse.SearchScreen
import com.yarmiplaytv.ui.components.ToastHost
import com.yarmiplaytv.ui.connect.DeviceAccessDialog
import com.yarmiplaytv.ui.connect.JellyfinLoginScreen
import com.yarmiplaytv.ui.connect.PlexLoginScreen
import com.yarmiplaytv.ui.connect.ServersScreen
import com.yarmiplaytv.ui.connect.SyncplayConnectScreen
import com.yarmiplaytv.ui.home.HomeScreen
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.player.PlayerScreen
import com.yarmiplaytv.ui.settings.LicensesScreen
import com.yarmiplaytv.ui.settings.SettingsScreen
import com.yarmiplaytv.ui.theme.AppColors
/** The D-pad driven UI used on Google TV / Android TV. */
@Composable
fun TvRoot(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val saveable = rememberSaveableStateHolder()
    val nav = remember { Navigator(stack) { saveable.removeState(it.key) } }

    // The room switched files (or the user started playback): bring up the player.
    LaunchedEffect(Unit) {
        container.playlist.openPlayerRequests.collect { request ->
            if (request <= container.playlist.handledPlayerRequests) return@collect
            container.playlist.handledPlayerRequests = request
            if (nav.current != Screen.Player && nav.current !is Screen.Search) nav.push(Screen.Player)
        }
    }

    BackHandler(enabled = nav.canGoBack && nav.current != Screen.Player) { nav.back() }

    // The UI is English-only, so keep it left-to-right even when the TV's system language is RTL.
    CompositionLocalProvider(
        LocalContentColor provides AppColors.Text,
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        Box(Modifier.fillMaxSize().background(AppColors.Background)) {
            val screen = nav.current
            saveable.SaveableStateProvider(screen.key) {
                when (screen) {
                    Screen.Home -> HomeScreen(container, nav)
                    Screen.SyncplayConnect -> SyncplayConnectScreen(container, nav)
                    Screen.JellyfinLogin -> JellyfinLoginScreen(container, nav)
                    Screen.PlexLogin -> PlexLoginScreen(container, nav)
                    Screen.Servers -> ServersScreen(container, nav)
                    Screen.Player -> PlayerScreen(container, nav)
                    Screen.Settings, Screen.LocalFiles -> SettingsScreen(container, nav)
                    Screen.Licenses -> LicensesScreen()
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
            DeviceAccessDialog(container)
        }
    }
}
