package com.yarmiplaytv.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Panel
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.serverDetail
import com.yarmiplaytv.ui.shared.signOutOfServer
import com.yarmiplaytv.ui.theme.AppColors

/** Every signed-in Jellyfin and Plex server, with sign out for each and buttons to add more. */
@Composable
fun ServersScreen(container: AppContainer, nav: Navigator) {
    val servers by container.servers.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    // Signing out removes the focused button, so focus moves back to the top of the list.
    LaunchedEffect(servers.size) { runCatching { firstFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Media servers", style = MaterialTheme.typography.headlineMedium)
        if (servers.isEmpty()) Text("No media servers yet", color = AppColors.TextDim)
        servers.forEachIndexed { i, s ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(serverDetail(s), color = AppColors.TextDim)
                    }
                    ActionButton(
                        "Sign out",
                        { signOutOfServer(container, s.key) },
                        if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        icon = Icons.Filled.Logout,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(
                "Add Jellyfin server",
                { nav.push(Screen.JellyfinLogin) },
                if (servers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                icon = Icons.Filled.Add,
                primary = servers.isEmpty(),
            )
            ActionButton("Add Plex server", { nav.push(Screen.PlexLogin) }, icon = Icons.Filled.Add)
        }
    }
}
