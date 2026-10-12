package com.yarmiplaytv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** [url] as the dark modules of a QR code, row by row; null when it doesn't fit in one. */
internal fun qrModules(url: String): List<BooleanArray>? = runCatching {
    val matrix = Encoder.encode(url, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}.getOrNull()

/** [url] without its scheme and trailing slash, for reading off the screen: "tv.yarmiplay.com/manual". */
internal fun shortUrl(url: String): String = url.substringAfter("://").removeSuffix("/")

/** A link opened on the TV: a QR code to scan with a phone, and the address to type. Back closes it. */
@Composable
fun QrLinkDialog(title: String, url: String, onDismiss: () -> Unit) {
    val modules = remember(url) { qrModules(url) }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { delay(50); close.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface).padding(32.dp).testTag("qr_dialog")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text("Scan with your phone, or type the address.", color = AppColors.TextDim, textAlign = TextAlign.Center)
                if (modules != null) QrCode(modules, Modifier.size(280.dp).testTag("qr_code"))
                Text(shortUrl(url), style = MaterialTheme.typography.titleLarge, color = AppColors.Accent, textAlign = TextAlign.Center)
                ActionButton("Close", onDismiss, Modifier.focusRequester(close), icon = Icons.Filled.Close)
            }
        }
    }
}

/** Black modules on white, with the four-module quiet zone scanners need. */
@Composable
private fun QrCode(modules: List<BooleanArray>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val quiet = 4
        val count = modules.size + quiet * 2
        val cell = size.minDimension / count
        val origin = (size.minDimension - cell * count) / 2 + cell * quiet
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                // A hair larger than the cell, so neighbouring modules don't show seams.
                if (dark) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
