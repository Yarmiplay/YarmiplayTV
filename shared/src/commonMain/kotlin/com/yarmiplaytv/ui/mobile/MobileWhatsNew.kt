package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.CHANGELOG_URL
import kotlinx.coroutines.launch
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.ReleaseNote
import com.yarmiplaytv.update.ReleaseNotes
import com.yarmiplaytv.update.WhatsNew

/** Every version's patch notes, newest first. */
@Composable
fun MobileWhatsNewScreen(container: AppContainer, nav: Navigator) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("What's new", nav)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp).testTag("whats_new"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReleaseNotes.all.forEach { note -> ReleaseNoteCard(note, current = note == ReleaseNotes.of(container.appVersion)) }
                TextButton({ uriHandler.openUri(CHANGELOG_URL) }, Modifier.testTag("all_patch_notes")) { Text("All patch notes on the website") }
            }
        }
    }
}

@Composable
private fun ReleaseNoteCard(note: ReleaseNote, current: Boolean) {
    Card(
        Modifier.fillMaxWidth().testTag("release_${note.version}"),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Version ${note.version}", style = MaterialTheme.typography.titleMedium)
                if (current) {
                    Spacer(Modifier.width(8.dp))
                    Text("This version", color = AppColors.Accent, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Text(note.date, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            note.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** "What's new in 1.8.5", once after an update; opening or closing it doesn't show it again. */
@Composable
fun WhatsNewCard(container: AppContainer, nav: Navigator, modifier: Modifier = Modifier) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val version = WhatsNew.cardVersion(settings.lastSeenVersion, container.appVersion) ?: return
    val note = ReleaseNotes.of(version) ?: return
    fun seen() = container.scope.launch { container.settingsStore.saveLastSeenVersion(version) }
    // The card's own click, so hovering and pressing highlight it within its rounded corners.
    Card(
        onClick = { seen(); nav.push(Screen.WhatsNew) },
        modifier = modifier.testTag("whats_new_card"),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NewReleases, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text("What's new in $version", style = MaterialTheme.typography.titleMedium)
                Text(note.paragraphs.firstOrNull().orEmpty(), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ seen() }, Modifier.testTag("whats_new_dismiss")) { Icon(Icons.Filled.Close, "Hide", tint = AppColors.TextDim) }
        }
    }
}
