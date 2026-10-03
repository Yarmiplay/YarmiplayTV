package com.yarmiplaytv.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.shared.plexServerLabel
import com.yarmiplaytv.ui.shared.rememberPlexLoginModel
import com.yarmiplaytv.ui.theme.AppColors

@Composable
fun PlexLoginScreen(container: AppContainer, nav: Navigator) {
    val model = rememberPlexLoginModel(container)
    val firstFocus = remember { FocusRequester() }
    val serverFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        model.useSavedAccount(nav::back)
    }
    LaunchedEffect(model.servers) { if (model.servers.any { !model.isAdded(it) }) runCatching { serverFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Plex", style = MaterialTheme.typography.headlineMedium)
        Text("Link this TV to your Plex account, then pick a server.", color = AppColors.TextDim)
        ActionButton(
            when {
                model.linkCode != null -> "Get a new code"
                model.savedAccount != null -> "Use a different Plex account"
                else -> "Get a link code"
            },
            { model.startLink(nav::back) },
            Modifier.focusRequester(firstFocus),
            icon = Icons.Filled.Link,
            primary = model.savedAccount == null,
            enabled = !model.busy,
        )
        model.status?.let { Text(it, color = AppColors.TextDim) }
        model.linkCode?.let { code ->
            Text(
                code,
                style = TextStyle(textDirection = TextDirection.Ltr),
                fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, letterSpacing = 8.sp,
            )
        }
        if (model.servers.isNotEmpty()) {
            val firstNew = model.servers.indexOfFirst { !model.isAdded(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                model.servers.forEachIndexed { i, server ->
                    val added = model.isAdded(server)
                    ActionButton(
                        plexServerLabel(server, added),
                        { model.pick(server, nav::back) },
                        if (i == firstNew) Modifier.focusRequester(serverFocus) else Modifier,
                        icon = Icons.Filled.Dns,
                        enabled = !model.busy && !added,
                    )
                }
            }
        }
        model.error?.let { Text(it, color = AppColors.Error) }
    }
}
