package com.yarmiplaytv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.IconAction
import com.yarmiplaytv.ui.components.QrLinkDialog
import com.yarmiplaytv.ui.components.TvTile
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.CHANGELOG_URL
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.ReleaseNotes
import com.yarmiplaytv.update.WhatsNew
import kotlinx.coroutines.launch

/** Every version's patch notes, newest first. Each version is focusable (and does nothing) so the D-pad scrolls. */
@Composable
fun WhatsNewScreen(container: AppContainer) {
    val firstFocus = remember { FocusRequester() }
    var showLink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val current = ReleaseNotes.of(container.appVersion)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp).testTag("whats_new"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("What's new", style = MaterialTheme.typography.headlineMedium)
        ReleaseNotes.all.forEachIndexed { i, note ->
            TvTile(
                onClick = {},
                modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier).fillMaxWidth(),
                focusedScale = 1.01f,
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Version ${note.version}", style = MaterialTheme.typography.titleMedium)
                        if (note == current) {
                            Spacer(Modifier.width(10.dp))
                            Text("This version", color = AppColors.Accent, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(note.date, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    note.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        ActionButton("All patch notes on the website", { showLink = true }, icon = Icons.Filled.OpenInNew)
    }
    if (showLink) QrLinkDialog("All patch notes", CHANGELOG_URL) { showLink = false }
}

/** "What's new in 1.8.5" on the home screen, once after an update; opening or hiding it doesn't show it again. */
@Composable
fun TvWhatsNewCard(container: AppContainer, nav: Navigator, modifier: Modifier = Modifier) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val version = WhatsNew.cardVersion(settings.lastSeenVersion, container.appVersion) ?: return
    val note = ReleaseNotes.of(version) ?: return
    fun seen() = container.scope.launch { container.settingsStore.saveLastSeenVersion(version) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TvTile(onClick = { seen(); nav.push(Screen.WhatsNew) }, modifier = Modifier.weight(1f).testTag("whats_new_card"), focusedScale = 1.02f) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NewReleases, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("What's new in $version", style = MaterialTheme.typography.titleMedium)
                    Text(note.paragraphs.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        IconAction(Icons.Filled.Close, "Hide", { seen() })
    }
}
