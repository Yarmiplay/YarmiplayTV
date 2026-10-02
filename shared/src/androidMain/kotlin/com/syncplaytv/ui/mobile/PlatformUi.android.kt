package com.syncplaytv.ui.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.syncplaytv.AppContainer
import com.syncplaytv.player.Player
import com.syncplaytv.player.SurfacePlayer
import com.syncplaytv.shared.R

actual fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun Modifier.playerScreenInput(input: PlayerInput): Modifier = this

@Composable
actual fun Modifier.videoGestures(input: PlayerInput): Modifier = pointerInput(input.seekStep) {
    detectTapGestures(
        onTap = { input.toggleControls() },
        onDoubleTap = { offset ->
            val third = size.width / 3f
            when {
                offset.x < third -> input.seekBy(-input.seekStep)
                offset.x > third * 2 -> input.seekBy(input.seekStep)
                else -> input.togglePause()
            }
        },
    )
}

actual fun toggleRoomSidePanel(tab: RoomPanelTab): Boolean = false

@Composable
actual fun RoomSidePanel(container: AppContainer) = Unit

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier) {
    val surfacePlayer = player as SurfacePlayer
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(surfacePlayer.surfaceCallback) } },
        onRelease = { it.holder.removeCallback(surfacePlayer.surfaceCallback) },
        modifier = modifier,
    )
}

@Composable
actual fun FullscreenLandscape() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Keeps read access to the picked file across restarts when possible. */
@Composable
actual fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPicked(uri.toString())
        }
    }
    return { launcher.launch(arrayOf("video/*")) }
}

@Composable
actual fun rememberFolderPicker(container: AppContainer): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) container.local.addFolder(uri.toString())
    }
    return { launcher.launch(null) }
}

actual fun uriFileName(uri: String): String? = Uri.parse(uri).lastPathSegment?.substringAfterLast('/')

@Composable
actual fun appLogoPainter(): Painter = painterResource(R.drawable.ic_logo)
