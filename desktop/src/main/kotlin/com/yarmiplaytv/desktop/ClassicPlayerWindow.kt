package com.yarmiplaytv.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.ui.mobile.ClassicPlayerContent
import com.yarmiplaytv.ui.mobile.ClassicWindows
import com.yarmiplaytv.ui.mobile.DesktopPip
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appLogoPainter
import com.yarmiplaytv.ui.mobile.appPalette
import java.awt.Dimension
import java.io.File

/**
 * The Syncplay-style player window: the app's player on its own, beside the room window (like mpv beside Syncplay).
 * It has its own full screen ([onFullscreen] hands it to the room window's F key) and remembers where it was in
 * [boundsFile]. Closing it pauses and hides it; the room window's menus bring it back, and so does playing anything.
 * Minimizing it while a video plays opens picture-in-picture through [setPip] when that setting is on.
 * [onGone] runs when it goes, so the video's mouse actions go back to the main window.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ClassicPlayerWindow(
    container: AppContainer,
    boundsFile: File,
    shortcuts: (Fullscreen) -> ShortcutTarget,
    onFullscreen: (Fullscreen?) -> Unit,
    setPip: (Boolean) -> Unit,
    onGone: () -> Unit,
) {
    val bounds = remember { if (boundsFile.exists()) WindowBounds.load(boundsFile) else WindowBounds(size = DpSize(1024.dp, 600.dp)) }
    val state = rememberWindowState(placement = bounds.placement, position = bounds.position, size = bounds.size)
    val fullscreen = remember { Fullscreen(state) }
    val target = remember { shortcuts(fullscreen) }
    val drop = remember { fileDrop(container) { false } }
    val nowPlaying by container.playlist.nowPlaying.collectAsState()
    fun saveBounds() {
        // Full screen's size isn't the window's own.
        if (!fullscreen.isFullscreen) WindowBounds.save(boundsFile, state)
    }
    Window(
        onCloseRequest = {
            fullscreen.set(false)
            saveBounds()
            if (container.player.state.value.fileLoaded) container.player.setPaused(true)
            ClassicWindows.playerOpen = false
        },
        state = state,
        title = (nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "No file") + " - YarmiplayTV",
        icon = appLogoPainter(),
        onKeyEvent = { event ->
            event.type == KeyEventType.KeyDown &&
                handleShortcut(
                    event.key,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    meta = event.isMetaPressed,
                    target = target,
                )
        },
    ) {
        DisposableEffect(Unit) {
            window.minimumSize = Dimension(480, 300)
            runCatching { window.iconImages = LaunchUpdateWindow.windowIcons() }
            fullscreen.attach(window)
            onFullscreen(fullscreen)
            DesktopPlayerInput.toggleFullscreen = { fullscreen.toggle() }
            DesktopPlayerInput.moveWindow = { dx, dy ->
                if (!fullscreen.isFullscreen && state.placement == WindowPlacement.Floating) {
                    window.setLocation(window.x + dx, window.y + dy)
                }
            }
            ClassicWindows.raisePlayer = {
                state.isMinimized = false
                window.toFront()
            }
            onDispose {
                saveBounds()
                ClassicWindows.raisePlayer = {}
                onFullscreen(null)
                onGone()
            }
        }
        val palette = appPalette(container)
        LaunchedEffect(palette.isLight) { useTitleBarTheme(window, dark = !palette.isLight) }
        LaunchedEffect(Unit) { showPlaybackOnTaskbar(window, container.player) }
        LaunchedEffect(Unit) {
            // Minimizing while a video plays goes on in picture-in-picture (a setting), like the regular window.
            var fromMinimize = false
            snapshotFlow { state.isMinimized }.collect { minimized ->
                val playing = container.player.state.value.let { it.fileLoaded && !it.paused }
                if (minimized && !DesktopPip.open && playing && container.settings.value.playback.pictureInPicture) {
                    fullscreen.set(false)
                    setPip(true)
                    fromMinimize = true
                } else if (!minimized && fromMinimize) {
                    fromMinimize = false
                    setPip(false)
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Press) {
                    when (it.button) {
                        PointerButton.Back -> DesktopPlayerInput.input?.value?.skip(false)
                        PointerButton.Forward -> DesktopPlayerInput.input?.value?.skip(true)
                    }
                }
                .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop),
        ) {
            MobileTheme(palette) { ClassicPlayerContent(container) }
        }
    }
}
