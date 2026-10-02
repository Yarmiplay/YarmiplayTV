package com.yarmiplaytv.desktop

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
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.DesktopPaths
import com.yarmiplaytv.defaultSyncplayName
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.player.desktop.DesktopMpvOptions
import com.yarmiplaytv.player.desktop.DesktopMpvPlayer
import com.yarmiplaytv.player.desktop.MpvUnavailableException
import com.yarmiplaytv.player.desktop.VideoSurface
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.DesktopDialogs
import com.yarmiplaytv.ui.mobile.DesktopRoomPanel
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.DesktopVideo
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appLogoPainter
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.net.URI
import java.nio.file.Paths
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

internal fun runApp(args: List<String>) {
    val configDir = DesktopPaths.migrateLegacyConfig()
    val boundsFile = File(configDir, "window.properties")
    var container: AppContainer? = null
    var failure: Throwable? = null
    // The container's scope runs on the Swing thread (Dispatchers.Main), so it's created there too.
    SwingUtilities.invokeAndWait {
        container = runCatching { createContainer(configDir) }.onFailure { failure = it }.getOrNull()
    }
    val app = container ?: run {
        val error = failure
        val message = (error as? MpvUnavailableException)?.hint ?: "YarmiplayTV couldn't start: ${error?.message}"
        error?.printStackTrace()
        JOptionPane.showMessageDialog(null, message, "YarmiplayTV", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val player = app.player as DesktopMpvPlayer
    DesktopVideo.surface = { _, modifier -> VideoSurface(player.renderer, modifier) }
    DesktopPlayerInput.changeVolume = { delta ->
        player.changeVolume(delta)
        player.showText("Volume ${player.volume.value.toInt()}%", 1000)
    }
    val launch = LaunchOptions.parse(args)
    // macOS hands files opened from Finder to the app as events rather than arguments.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
        Desktop.getDesktop().setOpenFileHandler { event -> SwingUtilities.invokeLater { openFiles(app, event.files) } }
    }
    SwingUtilities.invokeLater {
        launch.profile(app.settings.value.syncplay, defaultSyncplayName(DeviceKind.DESKTOP))?.let { app.sync.connect(it.toConfig()) }
        openFiles(app, launch.files)
        app.updates.platform = updatePlatform()
        app.updates.installer = WindowsUpdater.createOrNull()
        app.updates.checkOnLaunch()
    }

    application(exitProcessOnExit = false) {
        MainWindow(app, boundsFile)
    }
    app.updates.onExit()
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
 * added to the room's shared playlist when in a room. Folders stand for the videos directly in them.
 * With the playlist panel open ([addToPlaylist]), every video goes to the playlist instead.
 */
internal fun openFiles(container: AppContainer, files: List<File>, addToPlaylist: Boolean = false) {
    val videos = files.flatMap { DesktopDialogs.videosIn(it.toPath()) }.map(FileLocalLibrary::uriOf)
    if (addToPlaylist) {
        container.playlist.addLocalFilesToRoomPlaylist(videos)
        return
    }
    val first = videos.firstOrNull() ?: return
    container.playlist.playLocal(first, inRoom = false)
    if (container.sync.isActive && videos.size > 1) container.playlist.addLocalFilesToRoomPlaylist(videos.drop(1))
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
            override fun focusChat() = DesktopRoomPanel.focusChat()
        }
    }
    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
                openFiles(container, files, addToPlaylist = DesktopRoomPanel.showsPlaylist && container.sync.isActive)
                return files.isNotEmpty()
            }
        }
    }
    val close = {
        fullscreen.set(false)
        WindowBounds.save(boundsFile, state)
        exitApplication()
    }
    container.updates.exitApp = close
    Window(
        onCloseRequest = close,
        state = state,
        title = "YarmiplayTV",
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