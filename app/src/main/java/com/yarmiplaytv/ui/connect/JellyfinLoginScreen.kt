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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.Panel
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.shared.rememberJellyfinLoginModel
import com.yarmiplaytv.ui.theme.AppColors

@Composable
fun JellyfinLoginScreen(container: AppContainer, nav: Navigator) {
    val source by container.jellyfin.collectAsStateWithLifecycle()
    val model = rememberJellyfinLoginModel(container)
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val scrollState = rememberScrollState()
    LaunchedEffect(model.quickCode) {
        if (model.quickCode != null) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Jellyfin", style = MaterialTheme.typography.headlineMedium)
        source?.let { s ->
            Panel {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Signed in to ${s.displayName}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.session.userName} · ${s.session.serverUrl}", color = AppColors.TextDim)
                    }
                    ActionButton("Sign out", model::signOut, icon = Icons.Filled.Logout)
                }
            }
        }
        Text("Server address, e.g. http://192.168.1.10:8096. Inside the emulator your PC is 10.0.2.2.", color = AppColors.TextDim)
        TvTextField(model.url, { model.url = it.trim() }, "Server", Modifier.focusRequester(firstFocus), placeholder = "http://192.168.1.10:8096", keyboardType = KeyboardType.Uri)
        if (model.discovered.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                model.discovered.forEach { server ->
                    ActionButton("${server.name} (${server.address})", { model.url = server.address }, icon = Icons.Filled.Dns)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton("Quick Connect", { model.startQuickConnect(nav::back) }, icon = Icons.Filled.QrCode, primary = true, enabled = model.url.isNotBlank())
            ActionButton("Username & password", { showPassword = !showPassword; model.cancelQuickConnect() }, icon = Icons.Filled.Key)
        }
        model.status?.let { Text(it, color = AppColors.TextDim) }
        model.quickCode?.let { code ->
            Text(
                code.chunked(3).joinToString(" "),
                style = TextStyle(textDirection = TextDirection.Ltr),
                fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, letterSpacing = 8.sp,
            )
        }
        if (showPassword) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvTextField(model.username, { model.username = it }, "Username", Modifier.weight(1f))
                TvTextField(model.password, { model.password = it }, "Password", Modifier.weight(1f), password = true, onSubmit = { model.login(nav::back) })
            }
            ActionButton(
                if (model.busy) "Signing in…" else "Sign in",
                { model.login(nav::back) },
                primary = true,
                enabled = !model.busy && model.username.isNotBlank() && model.url.isNotBlank(),
            )
        }
        model.error?.let { Text(it, color = AppColors.Error) }
    }
}
