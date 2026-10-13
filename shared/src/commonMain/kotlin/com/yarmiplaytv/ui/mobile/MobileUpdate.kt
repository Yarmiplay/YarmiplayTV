package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.CHANGELOG_URL
import com.yarmiplaytv.ui.shared.UpdateCheckModel
import com.yarmiplaytv.ui.shared.readyButtonLabel
import com.yarmiplaytv.ui.shared.updateSourceLine
import com.yarmiplaytv.ui.shared.updateStateLine
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.StoreUpdates
import com.yarmiplaytv.update.UpdateCheck
import com.yarmiplaytv.update.UpdateState
import com.yarmiplaytv.update.Updates
import kotlinx.coroutines.launch

/** A newer version on the download page: installs it where this copy can, links to the page elsewhere. */
@Composable
fun UpdateBanner(container: AppContainer, modifier: Modifier = Modifier) {
    val state by container.updates.state.collectAsStateWithLifecycle()
    val inStore by container.updates.storeUpdate.collectAsStateWithLifecycle()
    inStore?.let { store ->
        StoreUpdateBanner(container, store, modifier)
        return
    }
    val s = state ?: return
    Card(
        modifier.testTag("update_banner"),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Text("YarmiplayTV ${s.update.version} is available", style = MaterialTheme.typography.titleMedium)
            }
            UpdateProgress(container, s, dismiss = true)
        }
    }
}

/** A newer version in the store that installed the app (Google Play): Update there, or not now. */
@Composable
private fun StoreUpdateBanner(container: AppContainer, store: StoreUpdates, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier.testTag("update_banner"),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Text("A new version of YarmiplayTV is available in ${store.name}", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ container.updates.updateFromStore(uriHandler::openUri) }, Modifier.testTag("store_update")) { Text("Update") }
                TextButton(container.updates::dismissStoreUpdate) { Text("Not now") }
            }
        }
    }
}

/** What's happening with [s] and the buttons for it: shared by the home notice and Settings. */
@Composable
private fun UpdateProgress(container: AppContainer, s: UpdateState, dismiss: Boolean) {
    val updates = container.updates
    val canInstall = updates.installer != null
    // Coming back from Android's "install unknown apps" setting changes what the notice says.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    key(lifecycleState) { UpdateProgressContent(updates, s, dismiss, canInstall) }
}

@Composable
private fun UpdateProgressContent(updates: Updates, s: UpdateState, dismiss: Boolean, canInstall: Boolean) {
    val uriHandler = LocalUriHandler.current
    Text(updateStateLine(updates, s), style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, modifier = Modifier.testTag("update_state"))
    if (s is UpdateState.Downloading) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            s is UpdateState.Ready -> Button(updates::restartToInstall, Modifier.testTag("update_install")) { Text(readyButtonLabel(updates)) }
            s is UpdateState.Downloading -> Unit
            (s is UpdateState.Available || s is UpdateState.Failed) && canInstall ->
                Button({ updates.download(thenRestart = true) }, Modifier.testTag("update_install")) { Text(if (s is UpdateState.Failed) "Try again" else "Install") }
            else -> Button({ uriHandler.openUri(s.update.pageUrl) }) { Text("Download page") }
        }
        if (dismiss || s is UpdateState.Downloading) {
            TextButton({ updates.dismiss(remember = s !is UpdateState.Downloading) }) { Text(if (s is UpdateState.Downloading) "Cancel" else "Not now") }
        }
        TextButton({ uriHandler.openUri(CHANGELOG_URL) }) { Text("Patch notes") }
    }
}

/**
 * Settings > Updates: this version, where its updates come from, "Check for updates" and installing what it finds,
 * and the automatic-update switches.
 */
@Composable
internal fun UpdatesSettings(container: AppContainer, nav: Navigator) {
    val updates = container.updates
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val check = remember(updates) { UpdateCheckModel(updates, scope) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("YarmiplayTV ${container.appVersion}", style = MaterialTheme.typography.titleMedium)
        Text(updateSourceLine(updates), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(check::check, Modifier.testTag("check_updates"), enabled = !check.checking) { Text("Check for updates") }
            if (check.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        val s = state
        when (val result = check.result) {
            null -> Unit
            UpdateCheck.UpToDate -> CheckLine("You're up to date.")
            is UpdateCheck.Failed -> CheckLine(result.message, error = true)
            is UpdateCheck.InStore -> StoreUpdate(result.store, result.available)
            is UpdateCheck.Found -> if (s == null) CheckLine("YarmiplayTV ${result.update.version} is available.")
        }
        if (s != null) {
            Text("YarmiplayTV ${s.update.version} is available", style = MaterialTheme.typography.bodyLarge, color = AppColors.Accent)
            UpdateProgress(container, s, dismiss = false)
        } else if (check.result == null && updates.store != null) {
            StoreUpdate(updates.store!!, available = null)
        }
        TextButton({ nav.push(Screen.WhatsNew) }, Modifier.testTag("whats_new")) { Text("What's new in this version") }
    }
    if (updates.platform != null) {
        fun save(check: Boolean = settings.checkForUpdates, install: Boolean = settings.installUpdatesOnLaunch) =
            container.scope.launch { container.settingsStore.saveUpdatePrefs(check, install) }
        SettingsToggle(
            "Check for updates when the app starts",
            "Look for a new version on the download page (GitHub)",
            settings.checkForUpdates,
            Modifier.testTag("toggle_check_updates"),
        ) { save(check = it) }
        if (updates.installer?.installsNow == false) {
            SettingsToggle(
                "Automatic updates",
                "Download and install a new version before the app opens, then start that version",
                settings.checkForUpdates && settings.installUpdatesOnLaunch,
                Modifier.testTag("toggle_install_updates"),
            ) { save(check = settings.checkForUpdates || it, install = it) }
        }
    }
}

@Composable
private fun CheckLine(text: String, error: Boolean = false) {
    Text(text, color = if (error) AppColors.Error else AppColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("update_check_result"))
}

/** A store's updates: what it said, its update button or page, and the command that updates the app. */
@Composable
private fun StoreUpdate(store: StoreUpdates, available: Boolean?) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    when (available) {
        true -> CheckLine("A new version is available in ${store.name}.")
        false -> CheckLine("You're up to date.")
        null -> CheckLine("Updates come from ${store.name}" + if (store.command != null) ": run this to update." else ".")
    }
    store.command?.let { command ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("update_command"))
            IconButton({ clipboard.setText(AnnotatedString(command)) }) { Icon(Icons.Filled.ContentCopy, "Copy the command", tint = AppColors.TextDim) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (available == true) {
            Button({ if (!store.update()) store.pageUrl?.let(uriHandler::openUri) }, Modifier.testTag("store_update")) { Text("Update") }
        }
        store.pageUrl?.let { url -> TextButton({ uriHandler.openUri(url) }) { Text("Open ${store.name}") } }
    }
}
