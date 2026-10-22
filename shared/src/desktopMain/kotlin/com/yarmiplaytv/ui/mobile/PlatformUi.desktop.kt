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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
    var volume: PlayerVolume? = null
    /** Where a mute key announces the new state. The window sets this to the player's on-screen display. */
    var announce: (String) -> Unit = {}
    /** Pointer in the video, 0 to 1, for zooming toward the cursor. */
    var pointerFraction: Pair<Double, Double>? = null
    /** Ctrl+wheel. The window sets this to zoom toward [pointerFraction]. */
    var zoomAt: (Double) -> Unit = {}
}

/**
 * The desktop's picture-in-picture: a small always-on-top window of its own with the video ([open]), while the main
 * window stays usable. The desktop app shows the window and sets [toggle].
 */
object DesktopPip {
    private var _open: Boolean by mutableStateOf(false)
    var open: Boolean
        get() = _open
        set(value) {
            _open = value
            PlayerPictureInPicture.active = value
        }
    var toggle: () -> Unit = { open = !open }
}

/** Invisible while the player controls are hidden. Falls back when the display can't make a custom cursor (headless CI). */
private val hiddenCursor: PointerIcon by lazy {
    runCatching {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
    }.getOrDefault(PointerIcon.Default)
}

actual val controlsHideMillis = 2000L

actual val hideControlsButton = true

actual val playerTrackTouchHeight = 24.dp

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
    var box by remember { mutableStateOf(IntSize.Zero) }
    return this
        .onSizeChanged { box = it }
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
            if (box.width > 0 && box.height > 0) {
                DesktopPlayerInput.pointerFraction = (position.x / box.width).toDouble() to (position.y / box.height).toDouble()
            }
        }
        .onPointerEvent(PointerEventType.Enter) { event ->
            event.changes.firstOrNull()?.let { pointer.last = it.position }
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull()
            val dy = change?.scrollDelta?.y ?: 0f
            val dx = change?.scrollDelta?.x ?: 0f
            if (event.keyboardModifiers.isCtrlPressed) {
                if (dy != 0f) DesktopPlayerInput.zoomAt(if (dy < 0) 0.1 else -0.1)
            } else if (dy != 0f) {
                DesktopPlayerInput.changeVolume(-dy * VOLUME_STEP)
                latest.value.showControls()
            }
            if (dx != 0f) latest.value.seekBy(if (dx > 0) 10.0 else -10.0)
        }
}

@Composable
actual fun Modifier.videoGestures(input: PlayerInput): Modifier {
    val latest = rememberUpdatedState(input)
    val focusManager = LocalFocusManager.current
    return pointerInput(Unit) {
        detectTapGestures(
            // Clicking the video leaves the chat box, so the keyboard shortcuts work again. mpv ignores this button.
            onTap = { focusManager.clearFocus() },
            onDoubleTap = { DesktopPlayerInput.toggleFullscreen() },
        )
    }
}

/** Volume change per scroll-wheel notch, in percentage points. mpv's wheel step is 2. */
const val VOLUME_STEP = 2.0

/** Set by the desktop app to the libmpv render view; until then the player area stays empty. */
object DesktopVideo {
    var surface: @Composable (Player, Modifier) -> Unit = { _, modifier -> Box(modifier) }
}

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier) = DesktopVideo.surface(player, modifier)

/** The desktop window decides about full screen itself (double-click, F). */
@Composable
actual fun FullscreenLandscape(pictureInPicture: Boolean, isPortrait: Boolean) = Unit

@Composable
actual fun rememberFullscreenToggle(isPortrait: Boolean): (() -> Unit)? = null

actual val autoPictureInPictureSetting = true
actual val autoPictureInPictureTitle = "Picture-in-picture when minimized"
actual val autoPictureInPictureSubtitle = "A playing video keeps going in a small window on top when you minimize YarmiplayTV"
actual val pictureInPictureLabel = "Picture-in-picture (F12)"

actual val skipKeyHint: String? = "Y"
actual val pictureInPictureWindow = true

/** Picture-in-picture is [DesktopPip]'s window: the button or P opens and closes it, and so does minimizing (a setting). */
@Composable
actual fun rememberPictureInPicture(player: Player, autoEnter: Boolean, inRoom: Boolean): PictureInPicture =
    PictureInPicture(supported = true, active = DesktopPip.open) { DesktopPip.toggle() }

@Composable
actual fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember { { DesktopDialogs.pickVideo()?.let { current(FileLocalLibrary.uriOf(it)) } } }
}

@Composable
actual fun rememberSubtitlePicker(onPicked: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember { { DesktopDialogs.pickSubtitle()?.let { current(FileLocalLibrary.uriOf(it)) } } }
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

actual val malSignInInApp = false

@Composable
actual fun MalSignInPage(url: String, onNavigate: (String) -> Boolean, modifier: Modifier, remotePointer: Boolean) = Unit
