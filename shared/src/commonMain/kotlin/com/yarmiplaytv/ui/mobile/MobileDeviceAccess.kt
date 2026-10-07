package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.shared.DeviceAccessPrompt
import com.yarmiplaytv.ui.shared.closeDeviceAccess
import com.yarmiplaytv.ui.shared.deviceAccessPrompt
import com.yarmiplaytv.ui.shared.requestDeviceAccess
import com.yarmiplaytv.ui.theme.AppColors

/** Shown over any screen while a YarmiplayServerTV host decides about this device, or after they said no. */
@Composable
fun MobileDeviceAccessDialog(container: AppContainer) {
    val room by container.sync.room.collectAsStateWithLifecycle()
    val prompt = deviceAccessPrompt(room.yarmiplay, container.deviceDisplayName) ?: return
    val close = { closeDeviceAccess(container) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(prompt.message)
                if (prompt is DeviceAccessPrompt.Waiting) {
                    Text(
                        prompt.fingerprint,
                        style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, textDirection = TextDirection.Ltr),
                        color = AppColors.Accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("device_fingerprint"),
                    )
                    Text("This device: ${prompt.deviceName}", color = AppColors.TextDim)
                }
            }
        },
        confirmButton = {
            when (prompt) {
                is DeviceAccessPrompt.Waiting -> TextButton(close, Modifier.testTag("device_cancel")) { Text("Cancel") }
                DeviceAccessPrompt.NotApproved ->
                    Button({ requestDeviceAccess(container) }, Modifier.testTag("device_request")) { Text("Request access") }
                is DeviceAccessPrompt.Refused ->
                    Button({ requestDeviceAccess(container) }, Modifier.testTag("device_retry")) { Text("Try again") }
            }
        },
        dismissButton = if (prompt is DeviceAccessPrompt.Waiting) null else ({ TextButton(close) { Text("Close") } }),
        modifier = Modifier.testTag("device_access"),
    )
}
