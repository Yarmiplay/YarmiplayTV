package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Home", Icons.Filled.Home),
    Tab(Screen.Search(), "Search", Icons.Filled.Search),
    Tab(Screen.SyncplayConnect, "Room", Icons.Filled.Groups),
    Tab(Screen.Settings, "Settings", Icons.Filled.Settings),
)

/** Touch UI for phones and tablets: bottom navigation on phones, a navigation rail on tablets. */
@Composable
fun MobileRoot(container: AppContainer, kind: DeviceKind) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val saveable = rememberSaveableStateHolder()
    val nav = remember { Navigator(stack) { saveable.removeState(it.key) } }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        container.playlist.openPlayerRequests.collect { request ->
            if (request <= container.playlist.handledPlayerRequests) return@collect
            container.playlist.handledPlayerRequests = request
            if (nav.current != Screen.Player && nav.current !is Screen.Search) nav.push(Screen.Player)
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
            showing = launch { snackbar.showSnackbar(msg.from?.let { "$it: ${msg.text}" } ?: msg.text) }
        }
    }
    PlatformBackHandler(enabled = nav.canGoBack && nav.current != Screen.Player) { nav.back() }

    val screen = nav.current
    val selectedTab = stack.lastOrNull { s -> tabs.any { it.screen::class == s::class } }?.let { s -> tabs.first { it.screen::class == s::class } }
        ?: tabs.first()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().exposeTestTags()) {
            if (screen == Screen.Player) {
                // Unlike the other screens, the player isn't inside a Scaffold that sets this.
                CompositionLocalProvider(LocalContentColor provides AppColors.Text) { MobilePlayerScreen(container, nav) }
            } else {
                MobileScaffold(snackbar, selectedTab, useRail = kind.isLarge, onTab = { nav.switchTab(it.screen) }) {
                    saveable.SaveableStateProvider(screen.key) {
                        when (screen) {
                            Screen.Home -> MobileHomeScreen(container, nav, kind)
                            Screen.SyncplayConnect -> MobileRoomScreen(container, nav)
                            Screen.JellyfinLogin -> MobileJellyfinScreen(container, nav)
                            Screen.PlexLogin -> MobilePlexScreen(container, nav)
                            Screen.Servers -> MobileServersScreen(container, nav)
                            Screen.Settings -> MobileSettingsScreen(container, nav)
                            Screen.LocalFiles -> MobileLocalFilesScreen(container, nav)
                            Screen.Licenses -> MobileLicensesScreen(nav)
                            is Screen.Browse -> MobileBrowseScreen(container, nav, screen.item, kind)
                            is Screen.Search -> MobileSearchScreen(container, nav, screen.initialQuery, screen.pickFor, kind)
                            Screen.Player -> Unit
                        }
                    }
                }
            }
            nav.actionItem?.let { item ->
                MobileItemActionsSheet(container, item, onDismiss = { nav.actionItem = null })
            }
            MobileDeviceAccessDialog(container)
        }
    }
}

@Composable
private fun MobileScaffold(snackbar: SnackbarHostState, selected: Tab, useRail: Boolean, onTab: (Tab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!useRail) {
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
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (useRail) {
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
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) { content() }
        }
    }
}

