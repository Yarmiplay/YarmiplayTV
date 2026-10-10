package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.relay.SaveTarget
import kotlinx.coroutines.flow.StateFlow
import org.xml.sax.InputSource
import java.nio.file.Files
import java.nio.file.Path

actual fun Modifier.exposeTestTags(): Modifier = this

/**
 * Back handlers in composition order; the desktop window calls [dispatch] for Esc and the mouse back
 * button. Like Android's dispatcher, the most recently added enabled handler wins.
 */
object DesktopBack {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** Returns false when no handler is enabled. */
    fun dispatch(): Boolean {
        val entry = synchronized(entries) { entries.lastOrNull { it.enabled } } ?: return false
        entry.onBack()
        return true
    }

    internal fun add(entry: Entry) = synchronized(entries) { entries.add(entry) }
    internal fun remove(entry: Entry) = synchronized(entries) { entries.remove(entry) }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val entry = remember { DesktopBack.Entry(enabled) { current() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        DesktopBack.add(entry)
        onDispose { DesktopBack.remove(entry) }
    }
}

/**
 * Connects the player screen and the desktop window: the window routes keyboard shortcuts to [input] while
 * the player is showing, and provides full screen and volume to the player's mouse handling.
 */
object DesktopPlayerInput {
    /** The player screen's current input, or null when the player isn't showing. */
    var input: State<PlayerInput>? by mutableStateOf(null)
        internal set
    var toggleFullscreen: () -> Unit = {}
    /** Changes the volume by the given percentage points. */
    var changeVolume: (Double) -> Unit = {}
    /** The player's volume for the player bar's volume control; without it the control isn't shown. */
    var volume: DesktopVolume? = null
}

interface DesktopVolume {
    /** In percent, 0 to [max]. */
    val volume: StateFlow<Double>
    val muted: StateFlow<Boolean>
    val max: Double
    /** Also unmutes. */
    fun setVolume(percent: Double)
    fun setMuted(muted: Boolean)
}

private val hiddenCursor: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
}

actual val controlsHideMillis = 2000L

actual val hideControlsButton = true

actual val tvStyleSeekBar = true

/** How far the pointer has to move to bring hidden controls back, so a nudged mouse doesn't undo a hide. */
private val SHOW_CONTROLS_DISTANCE = 12.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.playerScreenInput(input: PlayerInput): Modifier {
    val latest = rememberUpdatedState(input)
    DisposableEffect(latest) {
        DesktopPlayerInput.input = latest
        onDispose { if (DesktopPlayerInput.input === latest) DesktopPlayerInput.input = null }
    }
    val showDistance = with(LocalDensity.current) { SHOW_CONTROLS_DISTANCE.toPx() }
    // The pointer's last position, and where it was when the controls went away.
    val pointer = remember { object { var last: Offset? = null; var hiddenAt: Offset? = null } }
    return this
        .pointerHoverIcon(if (input.controlsVisible) PointerIcon.Default else hiddenCursor)
        // Parents see pointer events after their children, so moving over the controls counts too.
        .onPointerEvent(PointerEventType.Move) { event ->
            val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
            if (latest.value.controlsVisible) {
                pointer.hiddenAt = null
                latest.value.showControls()
            } else {
                val from = pointer.hiddenAt ?: pointer.last ?: position
                pointer.hiddenAt = from
                if ((position - from).getDistance() > showDistance) {
                    pointer.hiddenAt = null
                    latest.value.showControls()
                }
            }
            pointer.last = position
        }
        .onPointerEvent(PointerEventType.Enter) { event ->
            event.changes.firstOrNull()?.let { pointer.last = it.position }
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) DesktopPlayerInput.changeVolume(-dy * VOLUME_STEP)
            latest.value.showControls()
        }
}

@Composable
actual fun Modifier.videoGestures(input: PlayerInput): Modifier {
    val latest = rememberUpdatedState(input)
    val focusManager = LocalFocusManager.current
    return pointerInput(Unit) {
        detectTapGestures(
            // Clicking the video leaves the chat box, so the keyboard shortcuts work again.
            onTap = { focusManager.clearFocus(); latest.value.togglePause() },
            onDoubleTap = { DesktopPlayerInput.toggleFullscreen() },
        )
    }
}

/** Volume change per scroll-wheel notch or arrow key, in percentage points. */
const val VOLUME_STEP = 5.0

/** Set by the desktop app to the libmpv render view; until then the player area stays empty. */
object DesktopVideo {
    var surface: @Composable (Player, Modifier) -> Unit = { _, modifier -> Box(modifier) }
}

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier) = DesktopVideo.surface(player, modifier)

/** The desktop window decides about full screen itself (double-click, F). */
@Composable
actual fun FullscreenLandscape() = Unit

@Composable
actual fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember { { DesktopDialogs.pickVideo()?.let { current(FileLocalLibrary.uriOf(it)) } } }
}

@Composable
actual fun rememberFolderPicker(container: AppContainer): () -> Unit =
    remember(container) { { DesktopDialogs.pickFolder()?.let { container.local.addFolder(FileLocalLibrary.uriOf(it)) } } }

@Composable
actual fun rememberSavePicker(container: AppContainer): (String, (SaveTarget) -> Unit) -> Unit =
    remember(container) {
        { name, onPicked ->
            val folder = container.settings.value.downloadDirectory
            val path = if (folder.isNotEmpty()) {
                runCatching { FileLocalLibrary.pathOf(folder) }.getOrNull()?.let { DesktopDialogs.uniqueIn(it, name) }
            } else {
                DesktopDialogs.saveFile(name)
            }
            if (path != null) onPicked(fileSaveTarget(path))
        }
    }

/** Only removes the file once this save has started writing it, so a failed save keeps a file the user picked to replace. */
internal fun fileSaveTarget(path: Path): SaveTarget {
    var opened = false
    return SaveTarget(
        label = path.toString(),
        open = {
            path.parent?.let(Files::createDirectories)
            opened = true
            Files.newOutputStream(path)
        },
        discard = { if (opened) Files.deleteIfExists(path) },
    )
}

@Composable
actual fun rememberDirectoryPicker(title: String, onPicked: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember(title) { { DesktopDialogs.pickFolder(title)?.let { current(FileLocalLibrary.uriOf(it)) } } }
}

actual fun uriFileName(uri: String): String? =
    runCatching { FileLocalLibrary.pathOf(uri).fileName?.toString() }.getOrNull() ?: uri.substringAfterLast('/').ifEmpty { null }

@Composable
actual fun appLogoPainter(): Painter {
    val density = LocalDensity.current
    val vector = remember(density) {
        DesktopVideo::class.java.getResourceAsStream("/ic_logo.xml")!!.use { loadXmlImageVector(InputSource(it), density) }
    }
    return rememberVectorPainter(vector)
}
