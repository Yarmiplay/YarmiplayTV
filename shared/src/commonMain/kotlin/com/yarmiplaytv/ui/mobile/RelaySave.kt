package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.theme.AppColors

/** The player's save button for a file played through the room's relay. While [saving], clicking stops it. */
class RelaySaveAction(val saving: Boolean, val fraction: Float, val onClick: () -> Unit) {
    val label: String get() = if (saving) "Stop saving" else "Save a copy"
}

/** Null unless the file that's playing comes from the room's relay. */
@Composable
fun rememberRelaySave(container: AppContainer): RelaySaveAction? {
    val relay = container.relay ?: return null
    val pick = rememberSavePicker(container)
    val status by relay.status.collectAsStateWithLifecycle()
    val current = status ?: return null
    return RelaySaveAction(current.saving, current.fraction) {
        if (current.saving) relay.cancelSave() else pick(current.fileName) { relay.save(current.fileName, it) }
    }
}

@Composable
internal fun RelaySaveButton(action: RelaySaveAction, onInteract: () -> Unit) {
    IconButton({ action.onClick(); onInteract() }, Modifier.testTag("relay_save")) {
        if (action.saving) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { action.fraction },
                    modifier = Modifier.size(28.dp),
                    color = AppColors.Accent,
                    strokeWidth = 2.5.dp,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                Icon(Icons.Filled.Close, contentDescription = action.label, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            Icon(Icons.Filled.Download, contentDescription = action.label, tint = Color.White)
        }
    }
}
