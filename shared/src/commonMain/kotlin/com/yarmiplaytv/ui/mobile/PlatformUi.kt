package com.yarmiplaytv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.relay.SaveTarget

/** Lets uiautomator (`scripts/ui.ps1 -Id`) see test tags. Dialogs and sheets are separate windows and need their own. */
expect fun Modifier.exposeTestTags(): Modifier

/** System back on Android; Esc or the mouse back button on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Where the player draws its video. */
@Composable
expect fun VideoSurface(player: Player, modifier: Modifier = Modifier)

/** What the player screen does in response to input; each platform maps its own gestures and keys onto it. */
class PlayerInput(
    val seekStep: Double,
    val controlsVisible: Boolean,
    val toggleControls: () -> Unit,
    val showControls: () -> Unit,
    val togglePause: () -> Unit,
    val seekBy: (Double) -> Unit,
    /** The room playlist's next (true) or previous entry; nothing when there's none. */
    val skip: (Boolean) -> Unit = {},
)

/** How long the player controls stay up without input while a video plays and the pointer isn't on them. */
expect val controlsHideMillis: Long

/** Desktop: the player's top bar has a Hide button (and H hides), since a click on the video pauses instead. */
expect val hideControlsButton: Boolean

/** How tall the player's thin tracks (seek bar, volume) are to the pointer: taller for fingers than for the mouse. */
expect val playerTrackTouchHeight: Dp

/** Mute button and volume slider in the player's bar: mpv's volume on desktop, the device's media volume on Android. */
@Composable
expect fun PlayerVolumeControl(onInteract: () -> Unit, modifier: Modifier = Modifier)

/** On the whole player screen: desktop mouse movement, scroll wheel and keyboard shortcuts. Nothing on Android. */
@Composable
expect fun Modifier.playerScreenInput(input: PlayerInput): Modifier

/**
 * On the video area. Android: tap toggles the controls, double tap seeks back/forward or pauses by screen
 * thirds. Desktop: click pauses, double-click toggles full screen.
 */
@Composable
expect fun Modifier.videoGestures(input: PlayerInput): Modifier

enum class RoomPanelTab { ROOM, PLAYLIST, CHAT }

/**
 * Desktop shows room, playlist and chat in a side panel next to the video: opens it on [tab] (or closes it
 * when it's already showing that tab) and returns true. Android returns false and uses bottom sheets.
 */
expect fun toggleRoomSidePanel(tab: RoomPanelTab): Boolean

/** The side panel next to the video while it's open (desktop); nothing on Android. */
@Composable
expect fun RoomSidePanel(container: AppContainer)

/**
 * Landscape, edge-to-edge with hidden system bars and the screen kept on while the player is on screen. In
 * picture-in-picture ([pictureInPicture]) only the screen stays on: the window is the system's then.
 */
@Composable
expect fun FullscreenLandscape(pictureInPicture: Boolean = false)

/**
 * Picture-in-picture for the player: Android's PiP window, the desktop's mini player. [active] while the video is
 * in it; [toggle] goes in (and on desktop, back out: Android's window is expanded with the system's controls).
 */
class PictureInPicture(val supported: Boolean, val active: Boolean, val toggle: () -> Unit)

/** Whether the player is in picture-in-picture right now, so the app's dialogs stay out of the small window. */
object PlayerPictureInPicture {
    var active: Boolean by mutableStateOf(false)
}

/**
 * While the player is on screen. [autoEnter]: Android goes into PiP by itself when the app is left while a video
 * plays. Android also puts a play/pause button in the PiP window, and closing the window pauses a video watched
 * alone (in a room that would pause everyone, so there it plays on as when switching apps).
 */
@Composable
expect fun rememberPictureInPicture(player: Player, autoEnter: Boolean, inRoom: Boolean): PictureInPicture

/** Android: settings have "Picture-in-picture when leaving the app". The desktop's mini player only opens by hand. */
expect val autoPictureInPictureSetting: Boolean

/** The player's picture-in-picture button: "Picture-in-picture" on Android, "Mini player (P)" on desktop. */
expect val pictureInPictureLabel: String

/** Desktop's mini player: dragging the video moves the window, double-clicking it calls [onExit]. Nothing on Android. */
expect fun Modifier.miniPlayerGestures(onExit: () -> Unit): Modifier

/** System "open video" picker; [onPicked] gets a content:// (Android) or file:// (desktop) URI. */
@Composable
expect fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit

/** Picks a folder and adds it to the media folders. */
@Composable
expect fun rememberFolderPicker(container: AppContainer): () -> Unit

/**
 * Asks where to save a copy of a relayed file named `fileName`, or uses the download folder from settings without
 * asking. `onPicked` isn't called when the user cancels.
 */
@Composable
expect fun rememberSavePicker(container: AppContainer): (fileName: String, onPicked: (SaveTarget) -> Unit) -> Unit

/**
 * Picks a folder to save into; [onPicked] gets its URI (a writable content:// tree on Android, file:// on desktop).
 * [title] heads the desktop dialog.
 */
@Composable
expect fun rememberDirectoryPicker(title: String, onPicked: (String) -> Unit): () -> Unit

/** File name from the last segment of a file URI, for files the local library doesn't list. */
expect fun uriFileName(uri: String): String?

@Composable
expect fun appLogoPainter(): Painter
