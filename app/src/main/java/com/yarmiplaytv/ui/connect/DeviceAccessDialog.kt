package com.yarmiplaytv.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.shared.DeviceAccessPrompt
import com.yarmiplaytv.ui.shared.closeDeviceAccess
import com.yarmiplaytv.ui.shared.deviceAccessPrompt
import com.yarmiplaytv.ui.shared.requestDeviceAccess
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Shown over any screen while a YarmiplayServerTV host decides about this device, or after they said no. */
@Composable
fun DeviceAccessDialog(container: AppContainer) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val prompt = deviceAccessPrompt(room.yarmiplay, container.deviceDisplayName) ?: return
    val first = remember(prompt::class) { FocusRequester() }
    LaunchedEffect(prompt::class) { runCatching { delay(50); first.requestFocus() } }
    val close = { closeDeviceAccess(container) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(860.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface).padding(32.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(prompt.title, style = MaterialTheme.typography.headlineSmall)
                Text(prompt.message, color = AppColors.TextDim)
                if (prompt is DeviceAccessPrompt.Waiting) {
                    Text(
                        prompt.fingerprint,
                        style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                        color = AppColors.Accent,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.SurfaceHigh).padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    Text("This device: ${prompt.deviceName}", color = AppColors.TextDim)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (prompt) {
                        is DeviceAccessPrompt.Waiting ->
                            ActionButton("Cancel", close, Modifier.focusRequester(first), icon = Icons.Filled.Close)
                        DeviceAccessPrompt.NotApproved -> {
                            ActionButton("Request access", { requestDeviceAccess(container) }, Modifier.focusRequester(first), primary = true)
                            ActionButton("Close", close)
                        }
                        is DeviceAccessPrompt.Refused -> {
                            ActionButton("Try again", { requestDeviceAccess(container) }, Modifier.focusRequester(first), icon = Icons.Filled.Refresh, primary = true)
                            ActionButton("Close", close)
                        }
                    }
                }
            }
        }
    }
}
