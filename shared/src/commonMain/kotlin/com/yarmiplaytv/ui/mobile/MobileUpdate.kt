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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.update.UpdateState
import com.yarmiplaytv.ui.theme.AppColors

/** A newer version on the download page: installs it on Windows, links to the page elsewhere. */
@Composable
fun UpdateBanner(container: AppContainer, modifier: Modifier = Modifier) {
    val state by container.updates.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val updates = container.updates
    val canInstall = updates.installer != null
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
                Text("YarmiplayTV ${s.update.version} is available", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                when (s) {
                    is UpdateState.Available ->
                        if (canInstall) "Install it now, or turn on Automatic updates in Settings."
                        else "Get it from the YarmiplayTV download page."
                    is UpdateState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
                    is UpdateState.Ready -> "It installs when you close YarmiplayTV."
                    is UpdateState.Failed -> s.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextDim,
            )
            if (s is UpdateState.Downloading) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    s is UpdateState.Ready -> Button(updates::restartToInstall) { Text("Restart now") }
                    s is UpdateState.Downloading -> Unit
                    (s is UpdateState.Available || s is UpdateState.Failed) && canInstall ->
                        Button({ updates.download(thenRestart = true) }) { Text(if (s is UpdateState.Failed) "Try again" else "Install") }
                    else -> Button({ uriHandler.openUri(s.update.pageUrl) }) { Text("Download page") }
                }
                TextButton({ updates.dismiss() }) { Text(if (s is UpdateState.Downloading) "Cancel" else "Not now") }
            }
        }
    }
}
