package com.syncplaytv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.syncplaytv.AppContainer
import com.syncplaytv.player.Player

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
)

/** On the whole player screen: desktop mouse movement, scroll wheel and keyboard shortcuts. Nothing on Android. */
@Composable
expect fun Modifier.playerScreenInput(input: PlayerInput): Modifier

/**
 * On the video area. Android: tap toggles the controls, double tap seeks back/forward or pauses by screen
 * thirds. Desktop: click pauses, double-click toggles full screen.
 */
@Composable
expect fun Modifier.videoGestures(input: PlayerInput): Modifier

/** Landscape, edge-to-edge with hidden system bars and the screen kept on while the player is on screen. */
@Composable
expect fun FullscreenLandscape()

/** System "open video" picker; [onPicked] gets a content:// (Android) or file:// (desktop) URI. */
@Composable
expect fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit

/** Picks a folder and adds it to the media folders. */
@Composable
expect fun rememberFolderPicker(container: AppContainer): () -> Unit

/** File name from the last segment of a file URI, for files the local library doesn't list. */
expect fun uriFileName(uri: String): String?

@Composable
expect fun appLogoPainter(): Painter
