package com.yarmiplaytv.desktop

import com.yarmiplaytv.ui.theme.appIconLabels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.DesktopPaths
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.defaultSyncplayName
import com.yarmiplaytv.device.FileDeviceKeyStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.local.FrameGrabber
import com.yarmiplaytv.media.SharedHttp
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.player.desktop.DesktopMpvOptions
import com.yarmiplaytv.player.desktop.DesktopMpvPlayer
import com.yarmiplaytv.player.desktop.MpvFrameGrabber
import com.yarmiplaytv.player.desktop.MpvUnavailableException
import com.yarmiplaytv.player.desktop.VideoSurface
import com.yarmiplaytv.screenshot.FolderScreenshotStore
import com.yarmiplaytv.ui.mobile.ClassicRoomWindow
import com.yarmiplaytv.ui.mobile.ClassicWindows
import com.yarmiplaytv.ui.mobile.DesktopBack
import com.yarmiplaytv.ui.mobile.DesktopDialogs
import com.yarmiplaytv.ui.mobile.DesktopPip
import com.yarmiplaytv.ui.mobile.DesktopPlayerInput
import com.yarmiplaytv.ui.mobile.DesktopRoomPanel
import com.yarmiplaytv.ui.mobile.DesktopVideo
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.PlayerVolume
import com.yarmiplaytv.ui.mobile.appLogoPainter
import com.yarmiplaytv.update.UpdateChecker
import com.yarmiplaytv.update.UpdateFailedException
import com.yarmiplaytv.update.Updates
import com.yarmiplaytv.update.explainUpdateFailure
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal fun runApp(args: List<String>) {
    val configDir = DesktopPaths.portableDir() ?: DesktopPaths.migrateLegacyConfig()
    val boundsFile = File(configDir, "window.properties")
    val store = desktopSettingsStore(configDir)
    // macOS reads the window appearance once, when AWT starts: before the first window, from the saved theme.
    System.setProperty("apple.awt.application.appearance", macAppearance(runBlocking { store.current() }.theme))
    val updateSource = updateSource(args = args)
    val installer = (updateSource as? UpdateSource.DownloadPage)?.installer
    val failedInstall = loadPendingUpdateFailure(System.getenv(FAILED_INSTALL)?.ifEmpty { null })
    // Before the container, so libmpv isn't loaded yet and the installer can replace it.
    val launchUpdate = runBlocking {
        updateBeforeLaunch(store.current(), installer, failedInstall, LaunchUpdateWindow()) {
            val platform = (updateSource as UpdateSource.DownloadPage).platform
            UpdateChecker(http = SharedHttp.base.newBuilder().callTimeout(5, TimeUnit.SECONDS).build())
                .fetch(platform, APP_VERSION)
                .getOrThrow()
        }
    }
    var helperFailure: Throwable? = null
    if (launchUpdate is LaunchUpdate.Install) {
        val started = runCatching { installer?.installAfterExit(relaunch = true) }
        if (started.isSuccess) exitProcess(0)
        helperFailure = started.exceptionOrNull()
        // The app is still here, so the notice can say why. Also leave the note if we never reach that notice.
        val error = helperFailure
        if (error != null) {
            runCatching { writeUpdateFailure(failureFromHelperStart(launchUpdate.update.version, error)) }
        }
    }
    var container: AppContainer? = null
    var failure: Throwable? = null
    // The container's scope runs on the Swing thread (Dispatchers.Main), so it's created there too.
    SwingUtilities.invokeAndWait {
        container = runCatching { createContainer(configDir, store) }.onFailure { failure = it }.getOrNull()
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
    DesktopPlayerInput.volume = object : PlayerVolume {
        override val volume = player.volume
        override val muted = player.muted
        override val max = DesktopMpvPlayer.MAX_VOLUME
        override fun setVolume(percent: Double) = player.setVolume(percent)
        override fun setMuted(muted: Boolean) = player.setMuted(muted)
    }
    val launch = LaunchOptions.parse(args)
    // macOS hands files opened from Finder to the app as events rather than arguments.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
        Desktop.getDesktop().setOpenFileHandler { event -> SwingUtilities.invokeLater { openFiles(app, event.files) } }
    }
    SwingUtilities.invokeLater {
        launch.profile(app.settings.value.syncplay, defaultSyncplayName(DeviceKind.DESKTOP))?.let { app.sync.connect(it.toConfig()) }
        openFiles(app, launch.files)
        when (updateSource) {
            is UpdateSource.DownloadPage -> {
                app.updates.platform = updateSource.platform
                app.updates.installer = installer
            }
            is UpdateSource.Store -> app.updates.store = updateSource.store
        }
        val postponed = launchUpdate as? LaunchUpdate.Postponed
        val checkFailed = launchUpdate as? LaunchUpdate.CheckFailed
        val helperError = helperFailure
        app.updates.onFailureNoted = { clearPendingUpdateFailure() }
        when {
            helperError != null && launchUpdate is LaunchUpdate.Install -> {
                val failed = helperError as? UpdateFailedException
                val message = failed?.message
                    ?: explainUpdateFailure("launch", null, helperError.message)
                app.updates.showFailed(launchUpdate.update, message)
            }
            postponed == null && checkFailed == null -> app.updates.checkOnLaunch(failedInstall)
            postponed?.error != null -> app.updates.showFailed(postponed.update, Updates.downloadFailed(postponed.error))
            postponed != null -> app.updates.show(postponed.update)
            checkFailed != null -> app.updates.showProblem(
                checkFailed.error.message?.takeIf { it.isNotBlank() } ?: explainUpdateFailure("check", null, null),
            )
        }
    }

    application(exitProcessOnExit = false) {
        MainWindow(app, boundsFile)
    }
    val exitError = runCatching { app.updates.onExit() }.exceptionOrNull()
    if (exitError != null) {
        val update = app.updates.state.value?.update
        if (update != null) runCatching { writeUpdateFailure(failureFromHelperStart(update.version, exitError)) }
    }
    app.relay?.close()
    player.close()
    exitProcess(0)
}

private fun createContainer(configDir: File, store: SettingsStore): AppContainer {
    val pictures = lazy { File(PicturesFolder.resolve(), "YarmiplayTV") }
    return AppContainer(
        settingsStore = store,
        deviceName = DevicePlatform.deviceModel.ifEmpty { "Desktop" },
        appVersion = APP_VERSION,
        createPlayer = { initial ->
            val lowEnd = initial.playback.lowEndPerformance
            DesktopMpvPlayer(
                DesktopMpvOptions(
                    // auto-copy: predictable with GL→CPU readback on Windows mid-range GPUs (e.g. GTX 1060).
                    hwdec = if (initial.playback.hardwareDecoding) "auto-copy" else "no",
                    audioLanguages = initial.playback.audioLanguages,
                    subtitleLanguages = initial.playback.subtitleLanguages,
                    lowEnd = lowEnd,
                    maxRenderEdge = if (lowEnd) 1280 else 1920,
                ),
            )
        },
        createLocalLibrary = { settings, scope, folders -> FileLocalLibrary(settings, scope, folders) },
        deviceKeys = FileDeviceKeyStore(File(configDir, "device-keys")),
        cacheDir = if (DesktopPaths.portableDir() != null) File(configDir, "cache") else DesktopPaths.cacheDir(),
        screenshotStore = FolderScreenshotStore(File(System.getProperty("java.io.tmpdir"), "yarmiplaytv-screenshots"), pictures::value),
        frameGrabber = FrameGrabber { uri, width, target ->
            MpvFrameGrabber.grab(FileLocalLibrary.pathOf(uri).toString(), width, target, wait = true)
        },
        seekFrames = MpvSeekFrames,
        malClientId = MAL_CLIENT_ID,
        audioDecoder = MpvAudioDecoder,
        voiceEngine = DesktopVoiceEngine(),
    ).also { it.deviceKind = DeviceKind.DESKTOP }
}

/**
 * Plays the first video like "Open with" on Android (here, reported to the room). Further videos are
 * added to the room's shared playlist when in a room. Folders stand for the videos in them and their subfolders.
 * With the playlist panel open ([addToPlaylist]), every video goes to the playlist instead.
 */
/** Videos stay videos. A loose subtitle file is not one of them, and a folder is left for [openFiles] to walk. */
internal fun splitDropped(files: List<File>): Pair<List<File>, List<File>> {
    val subs = mutableListOf<File>()
    val rest = mutableListOf<File>()
    for (file in files) {
        if (file.isFile && file.extension.lowercase() in com.yarmiplaytv.media.SUBTITLE_EXTENSIONS) subs += file else rest += file
    }
    return rest to subs
}

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

/** The app's own keys that share mpv's table, and mpv's actions; false when the key did nothing. */
private fun performShortcut(container: AppContainer, action: PlaybackAction, togglePip: () -> Unit): Boolean = when (action) {
    PlaybackAction.SkipSegment -> {
        if (container.skips.prompt.value == null) false else {
            container.skips.skip()
            true
        }
    }
    PlaybackAction.FocusChat -> {
        if (container.settings.value.classicMode) ClassicWindows.focusChat() else DesktopRoomPanel.focusChat()
        true
    }
    PlaybackAction.TogglePip -> {
        togglePip()
        true
    }
    PlaybackAction.ToggleMic -> {
        if (!container.voice.ui.value.showMicButton) {
            false
        } else {
            container.voice.toggleMic()
            true
        }
    }
    else -> container.playback.perform(action)
}

/**
 * Files dropped on a window: videos play (or go to the room's playlist when [toPlaylist] says so), folders stand for
 * the videos in them, and subtitle files go with the video dropped with them or the one playing.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun fileDrop(container: AppContainer, toPlaylist: () -> Boolean): DragAndDropTarget = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
            .mapNotNull { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
        val (videos, subs) = splitDropped(files)
        val playingWith = videos.flatMap { DesktopDialogs.videosIn(it.toPath()) }.firstOrNull()
        val sidecars = subs.map { file ->
            com.yarmiplaytv.media.ExternalSubtitle(
                FileLocalLibrary.uriOf(file.toPath()),
                file.nameWithoutExtension,
                com.yarmiplaytv.media.sidecarLanguage(file.name),
            )
        }
        if (playingWith != null) container.playlist.rememberSidecars(FileLocalLibrary.uriOf(playingWith), sidecars)
        openFiles(container, videos, addToPlaylist = toPlaylist())
        if (playingWith == null) sidecars.forEach { container.playlist.addExternalSubtitle(it.url, it.title, it.language) }
        return files.isNotEmpty()
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ApplicationScope.MainWindow(container: AppContainer, boundsFile: File) {
    val bounds = remember { WindowBounds.load(boundsFile) }
    val state = rememberWindowState(placement = bounds.placement, position = bounds.position, size = bounds.size)
    val fullscreen = remember { Fullscreen(state) }
    var pipBounds by remember { mutableStateOf(bounds.mini) }
    var mainWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    /** Picture-in-picture opened by minimizing: restoring the window closes it again. */
    var pipFromMinimize by remember { mutableStateOf(false) }
    fun setPip(on: Boolean) {
        if (on) fullscreen.set(false) else pipFromMinimize = false
        DesktopPip.open = on
    }
    fun applyFullscreen(on: Boolean) {
        if (on) setPip(false)
        fullscreen.set(on)
    }
    val settings by container.settings.collectAsState()
    val classic = settings.classicMode
    /** The Syncplay-style player window's full screen, while that window is open. */
    var playerFullscreen by remember { mutableStateOf<Fullscreen?>(null) }
    val shortcuts = remember {
        object : ShortcutTarget {
            // In the Syncplay-style windows, this window is the room's: F goes to the player window.
            override val isFullscreen get() = if (container.settings.value.classicMode) false else fullscreen.isFullscreen
            override fun setFullscreen(on: Boolean) {
                if (container.settings.value.classicMode) playerFullscreen?.toggle() else applyFullscreen(on)
            }
            override fun back() = if (container.settings.value.classicMode) ClassicWindows.back() else DesktopBack.dispatch()
            override val player get() = DesktopPlayerInput.input?.value
            override fun perform(action: PlaybackAction): Boolean = performShortcut(container, action, togglePip = { setPip(!DesktopPip.open) })
        }
    }
    val playerShortcuts = { playerWindow: Fullscreen ->
        object : ShortcutTarget {
            override val isFullscreen get() = playerWindow.isFullscreen
            override fun setFullscreen(on: Boolean) {
                if (on) setPip(false)
                playerWindow.set(on)
            }
            // The player has nowhere to go back to; Esc only leaves full screen (and closes its own menus).
            override fun back() = false
            override val player get() = DesktopPlayerInput.input?.value
            override fun perform(action: PlaybackAction): Boolean = performShortcut(container, action, togglePip = {
                if (!DesktopPip.open) playerWindow.set(false)
                setPip(!DesktopPip.open)
            })
        }
    }
    // Dropped on the Syncplay-style room window, videos go to the room's playlist, like dropping them on Syncplay's.
    val drop = remember {
        fileDrop(container) {
            container.sync.isActive && (container.settings.value.classicMode || DesktopRoomPanel.showsPlaylist)
        }
    }
    val close = {
        DesktopPip.open = false
        fullscreen.set(false)
        WindowBounds.save(boundsFile, state, pipBounds)
        exitApplication()
    }
    container.onExitApp = close
    container.updates.exitApp = close
    /** Like Syncplay, closing the room window quits; with that setting off it only hides while the player stays. */
    val closeRequest = {
        if (!container.settings.value.classicMode || ClassicWindows.closeRoom(container.settings.value.classicCloseTogether)) {
            close()
        } else {
            fullscreen.set(false)
            WindowBounds.save(boundsFile, state, pipBounds)
        }
    }
    fun useMainWindowForVideo(window: java.awt.Window) {
        DesktopPlayerInput.toggleFullscreen = { applyFullscreen(!fullscreen.isFullscreen) }
        DesktopPlayerInput.moveWindow = { dx, dy ->
            if (!fullscreen.isFullscreen && state.placement == WindowPlacement.Floating) {
                window.setLocation(window.x + dx, window.y + dy)
            }
        }
    }
    Window(
        onCloseRequest = closeRequest,
        state = state,
        visible = !(classic && ClassicWindows.roomHidden),
        title = "YarmiplayTV",
        icon = appLogoPainter(),
        onKeyEvent = { event ->
            event.type == KeyEventType.KeyDown &&
                handleShortcut(
                    event.key,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    meta = event.isMetaPressed,
                    target = shortcuts,
                )
        },
    ) {
        LaunchedEffect(Unit) {
            DesktopPlayerInput.announce = { container.player.showText(it, 1000) }
            DesktopPlayerInput.zoomAt = { delta ->
                val (x, y) = DesktopPlayerInput.pointerFraction ?: (0.5 to 0.5)
                container.playback.zoomAt(delta, x, y)
            }
            window.minimumSize = Dimension(WindowBounds.MIN_WIDTH.toInt(), WindowBounds.MIN_HEIGHT.toInt())
            fullscreen.attach(window)
            mainWindow = window
            useMainWindowForVideo(window)
            DesktopPip.toggle = { setPip(!DesktopPip.open) }
            ClassicWindows.raiseRoom = {
                ClassicWindows.roomHidden = false
                state.isMinimized = false
                window.toFront()
            }
            runCatching { window.iconImages = LaunchUpdateWindow.windowIcons() }
        }
        LaunchedEffect(Unit) {
            // Shown again after being hidden: in front, not behind the player window.
            snapshotFlow { ClassicWindows.roomHidden }.drop(1).collect { hidden -> if (!hidden) window.toFront() }
        }
        LaunchedEffect(Unit) {
            // Leaving the player leaves full screen. Picture-in-picture stays: browsing meanwhile is what it's for.
            snapshotFlow { DesktopPlayerInput.input != null }.collect { showing -> if (!showing) fullscreen.set(false) }
        }
        val palette = appPalette(container)
        LaunchedEffect(palette.isLight) { useTitleBarTheme(window, dark = !palette.isLight) }
        LaunchedEffect(Unit) { showPlaybackOnTaskbar(window, container.player) }
        LaunchedEffect(Unit) {
            // Minimizing while a video plays goes on in picture-in-picture (a setting); restoring comes back.
            var playerBeforeMinimize = false
            snapshotFlow { state.isMinimized }.collect { minimized ->
                val playing = container.player.state.value.let { it.fileLoaded && !it.paused }
                // The Syncplay-style room window has no video to keep going.
                if (container.settings.value.classicMode) return@collect
                if (minimized && !DesktopPip.open && playing && container.settings.value.playback.pictureInPicture) {
                    playerBeforeMinimize = DesktopPlayerInput.input != null
                    setPip(true)
                    pipFromMinimize = true
                } else if (!minimized && pipFromMinimize) {
                    setPip(false)
                    if (playerBeforeMinimize) container.playlist.showPlayer()
                    playerBeforeMinimize = false
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Press) {
                    when (it.button) {
                        PointerButton.Back -> {
                            val playing = DesktopPlayerInput.input?.value
                            if (playing != null) playing.skip(false) else DesktopBack.dispatch()
                        }
                        PointerButton.Forward -> DesktopPlayerInput.input?.value?.skip(true)
                    }
                }
                .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop),
        ) {
            MobileTheme(palette, appIconLabels(container)) {
                if (classic) {
                    ClassicRoomWindow(container, onRegularView = {
                        container.scope.launch {
                            container.settingsStore.saveClassicMode(false)
                            // The regular view shows what's playing in this window again.
                            if (container.player.state.value.fileLoaded) container.playlist.showPlayer()
                        }
                    })
                } else {
                    MobileRoot(container, DeviceKind.DESKTOP)
                }
            }
        }
    }
    LaunchedEffect(classic) {
        // The room window has no player: whatever was in full screen leaves it.
        if (classic) fullscreen.set(false)
    }
    LaunchedEffect(Unit) {
        // The room window stays hidden only beside the player window; the regular view always shows it.
        snapshotFlow { ClassicWindows.roomHidden && !(container.settings.value.classicMode && ClassicWindows.playerOpen) }
            .collect { if (it) ClassicWindows.roomHidden = false }
    }
    if (classic && ClassicWindows.playerOpen) {
        ClassicPlayerWindow(
            container,
            File(boundsFile.parentFile, "player-window.properties"),
            shortcuts = playerShortcuts,
            onFullscreen = { playerFullscreen = it },
            setPip = { on -> setPip(on) },
            onGone = { mainWindow?.let(::useMainWindowForVideo) },
        )
    }
    if (DesktopPip.open) {
        PipWindow(
            container,
            remembered = pipBounds,
            near = mainWindow,
            onBounds = { pipBounds = it },
            onBack = {
                setPip(false)
                state.isMinimized = false
                mainWindow?.toFront()
                container.playlist.showPlayer()
            },
            onClose = { setPip(false) },
        )
    }
}