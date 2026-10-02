package com.syncplaytv.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.syncplaytv.AppContainer
import com.syncplaytv.DeviceKind
import com.syncplaytv.DevicePlatform
import com.syncplaytv.data.DesktopPaths
import com.syncplaytv.defaultSyncplayName
import com.syncplaytv.data.desktopSettingsStore
import com.syncplaytv.local.FileLocalLibrary
import com.syncplaytv.local.LocalMatcher
import com.syncplaytv.player.desktop.DesktopMpvOptions
import com.syncplaytv.player.desktop.DesktopMpvPlayer
import com.syncplaytv.player.desktop.MpvUnavailableException
import com.syncplaytv.player.desktop.VideoSurface
import com.syncplaytv.ui.mobile.DesktopBack
import com.syncplaytv.ui.mobile.DesktopPlayerInput
import com.syncplaytv.ui.mobile.DesktopVideo
import com.syncplaytv.ui.mobile.MobileRoot
import com.syncplaytv.ui.mobile.MobileTheme
import com.syncplaytv.ui.mobile.appLogoPainter
import java.awt.Dimension
import java.io.File
import java.net.URI
import java.nio.file.Paths
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

internal fun runApp(args: List<String>) {
    val configDir = DesktopPaths.configDir()
    val boundsFile = File(configDir, "window.properties")
    var container: AppContainer? = null
    var failure: Throwable? = null
    // The container's scope runs on the Swing thread (Dispatchers.Main), so it's created there too.
    SwingUtilities.invokeAndWait {
        container = runCatching { createContainer(configDir) }.onFailure { failure = it }.getOrNull()
    }
    val app = container ?: run {
        val error = failure
        val message = (error as? MpvUnavailableException)?.hint ?: "SyncplayTV couldn't start: ${error?.message}"
        error?.printStackTrace()
        JOptionPane.showMessageDialog(null, message, "SyncplayTV", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val player = app.player as DesktopMpvPlayer
    DesktopVideo.surface = { _, modifier -> VideoSurface(player.renderer, modifier) }
    DesktopPlayerInput.changeVolume = { delta ->
        player.changeVolume(delta)
        player.showText("Volume ${player.volume.value.toInt()}%", 1000)
    }
    val launch = LaunchOptions.parse(args)
    SwingUtilities.invokeLater {
        launch.profile(app.settings.value.syncplay, defaultSyncplayName(DeviceKind.DESKTOP))?.let { app.sync.connect(it.toConfig()) }
        openFiles(app, launch.files)
    }

    application(exitProcessOnExit = false) {
        MainWindow(app, boundsFile)
    }
    player.close()
    exitProcess(0)
}

private fun createContainer(configDir: File): AppContainer {
    val store = desktopSettingsStore(configDir)
    return AppContainer(
        settingsStore = store,
        deviceName = DevicePlatform.deviceModel.ifEmpty { "Desktop" },
        appVersion = APP_VERSION,
        createPlayer = { initial ->
            DesktopMpvPlayer(
                DesktopMpvOptions(
                    hwdec = if (initial.playback.hardwareDecoding) "auto" else "no",
                    audioLanguages = initial.playback.audioLanguages,
                    subtitleLanguages = initial.playback.subtitleLanguages,
                ),
            )
        },
        createLocalLibrary = { settings, scope, folders -> FileLocalLibrary(settings, scope, folders) },
    ).also { it.deviceKind = DeviceKind.DESKTOP }
}

/**
 * Plays the first video like "Open with" on Android (here, reported to the room). Further videos are
 * added to the room's shared playlist when in a room.
 */
internal fun openFiles(container: AppContainer, files: List<File>) {
    val videos = files.filter { it.isFile && LocalMatcher.isVideo(it.name, null) }
    val first = videos.firstOrNull() ?: return
    container.playlist.playLocal(FileLocalLibrary.uriOf(first.toPath()), inRoom = false)
    if (container.sync.isActive) videos.drop(1).forEach { container.playlist.addLocalToRoomPlaylist(FileLocalLibrary.uriOf(it.toPath())) }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ApplicationScope.MainWindow(container: AppContainer, boundsFile: File) {
    val bounds = remember { WindowBounds.load(boundsFile) }
    val state = rememberWindowState(placement = bounds.placement, position = bounds.position, size = bounds.size)
    val fullscreen = remember { Fullscreen(state) }
    val shortcuts = remember {
        object : ShortcutTarget {
            override val isFullscreen get() = fullscreen.isFullscreen
            override fun setFullscreen(on: Boolean) = fullscreen.set(on)
            override fun back() = DesktopBack.dispatch()
            override val player get() = DesktopPlayerInput.input?.value
            override fun changeVolume(delta: Double) = DesktopPlayerInput.changeVolume(delta)
        }
    }
    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
                openFiles(container, files)
                return files.isNotEmpty()
            }
        }
    }
    Window(
        onCloseRequest = {
            fullscreen.set(false)
            WindowBounds.save(boundsFile, state)
            exitApplication()
        },
        state = state,
        title = "SyncplayTV",
        icon = appLogoPainter(),
        onKeyEvent = { event ->
            event.type == KeyEventType.KeyDown &&
                handleShortcut(event.key, event.isCtrlPressed || event.isAltPressed || event.isMetaPressed, shortcuts)
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt())
            fullscreen.attach(window)
            DesktopPlayerInput.toggleFullscreen = fullscreen::toggle
        }
        LaunchedEffect(Unit) {
            // Leaving the player leaves full screen.
            snapshotFlow { DesktopPlayerInput.input != null }.collect { showing -> if (!showing) fullscreen.set(false) }
        }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Press) { if (it.button == PointerButton.Back) DesktopBack.dispatch() }
                .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop),
        ) {
            MobileTheme { MobileRoot(container, DeviceKind.DESKTOP) }
        }
    }
}