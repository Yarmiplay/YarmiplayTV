package com.yarmiplaytv.ui.mobile

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yarmiplaytv.Logger
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.player.Player
import kotlin.math.roundToInt

actual val autoPictureInPictureSetting = true

actual val autoPictureInPictureTitle = "Picture-in-picture when leaving the app"
actual val autoPictureInPictureSubtitle = "A playing video keeps going, in sync, in a small window over other apps"

actual val pictureInPictureLabel = "Picture-in-picture"

actual val skipKeyHint: String? = null

actual val pictureInPictureWindow = false

@Composable
actual fun rememberPictureInPicture(player: Player, autoEnter: Boolean, inRoom: Boolean): PictureInPicture {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val supported = remember(activity) { activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true }
    if (activity == null || !supported) return PictureInPicture(supported = false, active = false) {}

    var active by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    val state by player.state.collectAsState()
    val isVideo = state.fileLoaded && (state.videoWidth > 0 || state.displayAspect > 0 || !FileNames.isAudio(state.url.orEmpty()))
    val auto = autoEnter && isVideo
    val params = remember(state.displayAspect, state.paused, auto) { pipParams(activity, state.displayAspect, state.paused, auto) }
    val latestParams by rememberUpdatedState(params)
    val latestAuto by rememberUpdatedState(auto)
    val latestInRoom by rememberUpdatedState(inRoom)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(params) { runCatching { activity.setPictureInPictureParams(params) }.onFailure { Logger.w(TAG, "PiP parameters refused", it) } }
    SideEffect { PlayerPictureInPicture.active = active }

    DisposableEffect(activity, player, lifecycle) {
        // Closing the PiP window stops the activity; a video watched alone pauses then.
        fun closed() {
            if (!latestInRoom) player.setPaused(true)
        }
        val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
            Logger.d(TAG, "Picture-in-picture: ${info.isInPictureInPictureMode}")
            active = info.isInPictureInPictureMode
            // Some Android versions stop the activity first, others report leaving PiP first.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) closed()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (active) closed()
                // Back at full size: Android can skip telling a window that left PiP while it was still animating in.
                Lifecycle.Event.ON_RESUME -> active = activity.isInPictureInPictureMode
                else -> Unit
            }
        }
        // Android 12 enters by itself (setAutoEnterEnabled); older versions get the leave hint.
        val leaveHint = Runnable { if (latestAuto && !activity.isInPictureInPictureMode) runCatching { activity.enterPictureInPictureMode(latestParams) } }
        val playPause = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = player.setPaused(!player.isPaused)
        }
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        lifecycle.addObserver(lifecycleObserver)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) activity.addOnUserLeaveHintListener(leaveHint)
        ContextCompat.registerReceiver(activity, playPause, IntentFilter(ACTION_PIP_PLAY_PAUSE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(modeListener)
            lifecycle.removeObserver(lifecycleObserver)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) activity.removeOnUserLeaveHintListener(leaveHint)
            runCatching { activity.unregisterReceiver(playPause) }
            PlayerPictureInPicture.active = false
            // Off the player screen, keep auto-enter enabled if media is loaded and setting is on.
            val keepAuto = latestAuto && player.isFileLoaded && (state.videoWidth > 0 || state.displayAspect > 0 || !FileNames.isAudio(state.url.orEmpty()))
            runCatching { activity.setPictureInPictureParams(pipParams(activity, state.displayAspect, state.paused, autoEnter = keepAuto)) }
        }
    }

    return PictureInPicture(supported = true, active = active) {
        runCatching { activity.enterPictureInPictureMode(latestParams) }.onFailure { Logger.w(TAG, "Couldn't enter picture-in-picture", it) }
    }
}

/** The window's shape (the video's, within Android's limits), whether it opens by itself, and a play/pause button. */
fun pipParams(context: Context, aspect: Double, paused: Boolean, autoEnter: Boolean): PictureInPictureParams {
    val toggle = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_PIP_PLAY_PAUSE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val label = if (paused) "Play" else "Pause"
    val icon = Icon.createWithResource(context, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
    return PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect(aspect))
        .setActions(listOf(RemoteAction(icon, label, label, toggle)))
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(autoEnter)
                // Video looks better cross-faded than stretched while the window resizes.
                setSeamlessResizeEnabled(false)
            }
        }
        .build()
}

/** [aspect] as a ratio Android accepts for a PiP window (between 1:2.39 and 2.39:1); 16:9 until the video's is known. */
fun pipAspect(aspect: Double): Rational {
    val shown = if (aspect > 0) aspect.coerceIn(MIN_ASPECT, MAX_ASPECT) else 16.0 / 9.0
    return Rational((shown * ASPECT_SCALE).roundToInt(), ASPECT_SCALE)
}

private const val TAG = "PictureInPicture"
const val ACTION_PIP_PLAY_PAUSE = "com.yarmiplaytv.action.PIP_PLAY_PAUSE"
private const val MAX_ASPECT = 2.39
/** A little over 1:2.39, so rounding to [ASPECT_SCALE] never drops below the limit. */
private const val MIN_ASPECT = 0.42
private const val ASPECT_SCALE = 1000
