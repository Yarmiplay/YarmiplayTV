package com.yarmiplaytv.ui.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.SurfacePlayer
import com.yarmiplaytv.relay.SaveTarget
import com.yarmiplaytv.shared.R
import java.io.IOException

actual fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

actual val controlsHideMillis = 5000L

actual val hideControlsButton = false

actual val tvStyleSeekBar = false

@Composable
actual fun PlayerVolumeControl(onInteract: () -> Unit, modifier: Modifier) = Unit

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

@Composable
actual fun rememberSavePicker(container: AppContainer): (String, (SaveTarget) -> Unit) -> Unit {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val pending = remember { arrayOfNulls<(SaveTarget) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SAVE_MIME)) { uri ->
        val onPicked = pending[0]
        pending[0] = null
        if (uri != null && onPicked != null) onPicked(documentSaveTarget(resolver, uri))
    }
    return remember(container, launcher) {
        { name, onPicked ->
            val folder = container.settings.value.downloadDirectory
            if (folder.isNotEmpty()) {
                val created = runCatching {
                    val tree = Uri.parse(folder)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    DocumentsContract.createDocument(resolver, parent, SAVE_MIME, name)
                }.getOrNull()
                if (created != null) {
                    onPicked(documentSaveTarget(resolver, created))
                } else {
                    Toast.makeText(context, "Can't save to ${container.local.folderOf(folder).name}; pick the folder again in Settings", Toast.LENGTH_LONG).show()
                }
            } else {
                pending[0] = onPicked
                try {
                    launcher.launch(name)
                } catch (e: ActivityNotFoundException) {
                    pending[0] = null
                    Toast.makeText(context, "This device has no file picker to save with", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** A document the picker or the download folder just created for the copy; a failed save deletes it again. */
private fun documentSaveTarget(resolver: ContentResolver, uri: Uri) = SaveTarget(
    label = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uriFileName(uri.toString()) ?: uri.toString(),
    open = { resolver.openOutputStream(uri, "wt") ?: throw IOException("Can't open $uri") },
    discard = { DocumentsContract.deleteDocument(resolver, uri) },
)

/** Any name the user keeps works: the providers keep the extension for this type. */
private const val SAVE_MIME = "application/octet-stream"

@Composable
actual fun rememberDirectoryPicker(title: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            current(uri.toString())
        }
    }
    return {
        try {
            launcher.launch(null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "This device has no folder picker", Toast.LENGTH_LONG).show()
        }
    }
}

actual fun uriFileName(uri: String): String? = Uri.parse(uri).lastPathSegment?.substringAfterLast('/')

@Composable
actual fun appLogoPainter(): Painter = painterResource(R.drawable.ic_logo)
