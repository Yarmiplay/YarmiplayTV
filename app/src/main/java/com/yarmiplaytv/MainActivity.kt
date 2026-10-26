package com.yarmiplaytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.playback.PlaybackService
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.TvRoot
import com.yarmiplaytv.ui.mobile.ACTION_PIP_EXIT
import com.yarmiplaytv.ui.mobile.ACTION_PIP_PLAY_PAUSE
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.PlayerPictureInPicture
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.pipParams
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.YarmiplayTvTheme
import com.yarmiplaytv.voice.AndroidMicPermissions
import com.yarmiplaytv.voice.TvVoiceKeys
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as YarmiplayTvApp).container

    /** Attached to the mic-permission bridge in onCreate; results arrive via [onRequestPermissionsResult]. */
    private val androidMicPermissions = AndroidMicPermissions(this)

    private val pipExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            exit(context ?: this@MainActivity)
        }
    }

    /**
     * The system picture-in-picture pause button. Registered once: the root and the player both call
     * rememberPictureInPicture, and a receiver in each of those toggled pause twice, so the button did nothing.
     */
    private val pipPlayPauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val player = container.player
            player.setPaused(!player.isPaused)
        }
    }

    lateinit var deviceKind: DeviceKind
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = this
        isExiting = false
        container.onExitApp = { exit(this) }
        container.updates.exitApp = { exit(this) }
        deviceKind = DeviceUi.kind(this, intent)
        container.deviceKind = deviceKind
        container.micPermissions.delegate = androidMicPermissions
        // Like saved instance state: a recreated activity carries on where it was, a new one starts at Home.
        val screens = (application as YarmiplayTvApp).screens
        if (savedInstanceState == null) {
            screens.clear()
            screens.add(Screen.Home)
        }
        if (deviceKind == DeviceKind.TV) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            setContent { YarmiplayTvTheme(appPalette(container)) { TvRoot(container, screens) } }
        } else {
            setContent {
                val palette = appPalette(container)
                val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
                // The landscape player is dark whatever the theme; portrait player uses the theme background.
                val lightBars = palette.isLight && (screens.lastOrNull() != Screen.Player || isPortrait)
                LaunchedEffect(lightBars, palette) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    val style = if (lightBars) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    // What shows before the first frame and around it, e.g. while the keyboard animates.
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background.toArgb()))
                }
                MobileTheme(palette) { MobileRoot(container, deviceKind, screens) }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)

        val isTest = System.getProperty("yarmiplaytv.noUpdateCheck") != null
        if (!isTest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val hasPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (hasPip) {
            ContextCompat.registerReceiver(this, pipExitReceiver, IntentFilter(ACTION_PIP_EXIT), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, pipPlayPauseReceiver, IntentFilter(ACTION_PIP_PLAY_PAUSE), ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        if (hasPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lifecycleScope.launch {
                combine(container.settings, container.player.state) { settings, playerState ->
                    settings.playback.pictureInPicture to playerState
                }.collect { (pipEnabled, state) ->
                    val isVideo = state.fileLoaded && (state.videoWidth > 0 || state.displayAspect > 0 || !FileNames.isAudio(state.url.orEmpty()))
                    val auto = pipEnabled && isVideo
                    val params = pipParams(this@MainActivity, state.displayAspect, state.paused, autoEnter = auto)
                    runCatching { setPictureInPictureParams(params) }
                }
            }
        }
    }

    /**
     * TV remote mic / search keys are hold-to-talk (many remotes only stream while held).
     * The focusable Mic button remains tap-to-toggle for remotes that never deliver these keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (deviceKind == DeviceKind.TV && TvVoiceKeys.isVoiceHoldKey(event.keyCode)) {
            val voice = container.voice.ui.value
            if (voice.showMicButton) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        container.voice.noteRemoteToggleKey(TvVoiceKeys.labelFor(event.keyCode))
                        container.voice.setTalkLatched(true)
                    }
                    KeyEvent.ACTION_UP -> container.voice.setTalkLatched(false)
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (androidMicPermissions.onRequestPermissionsResult(requestCode, grantResults)) return
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        val isTest = System.getProperty("yarmiplaytv.noUpdateCheck") != null
        if (!isExiting && !isTest) {
            val settings = container.settings.value
            val playerState = container.player.state.value
            val inRoom = container.sync.room.value.status == ConnectionStatus.CONNECTED
            val isLoaded = playerState.fileLoaded
            val voiceActive = (deviceKind == DeviceKind.PHONE || deviceKind == DeviceKind.TABLET) &&
                container.voice.ui.value.showUi &&
                container.voice.ui.value.connection == com.yarmiplaytv.voice.VoiceConnection.CONNECTED
            // Phones/tablets keep the FGS up while room voice is connected so talk continues in the background.
            if (voiceActive || (settings.playback.stayConnectedMinimized && (isLoaded || inRoom))) {
                PlaybackService.start(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipExitReceiver) }
        runCatching { unregisterReceiver(pipPlayPauseReceiver) }
        if (activeActivity == this) {
            activeActivity = null
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val hasPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (hasPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val settings = container.settings.value
            val player = container.player
            val state = player.state.value
            val isVideo = state.fileLoaded && (state.videoWidth > 0 || state.displayAspect > 0 || !FileNames.isAudio(state.url.orEmpty()))
            if (settings.playback.pictureInPicture && isVideo && !isInPictureInPictureMode) {
                val screens = (application as YarmiplayTvApp).screens
                if (screens.lastOrNull() != Screen.Player) {
                    screens.add(Screen.Player)
                }
                val params = pipParams(this, state.displayAspect, state.paused, autoEnter = true)
                runCatching { enterPictureInPictureMode(params) }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerPictureInPicture.active = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            val screens = (application as YarmiplayTvApp).screens
            if (screens.lastOrNull() != Screen.Player) {
                screens.add(Screen.Player)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * `am start -n com.yarmiplaytv/.MainActivity --es url <stream>` or a VIEW intent plays a URL directly;
     * content:// URIs (e.g. "Open with" from a file manager) play as local files.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (data != null && (data.scheme == "content" || data.scheme == "file")) {
            container.playlist.playLocal(data.toString(), inRoom = false)
            return
        }
        val url = intent?.getStringExtra("url") ?: data?.toString()
        if (!url.isNullOrBlank()) container.playlist.playUrl(url)
    }

    companion object {
        var activeActivity: MainActivity? = null
        var isExiting: Boolean = false

        fun exit(context: Context? = null) {
            isExiting = true
            val act = activeActivity
            act?.container?.playlist?.stop()
            act?.container?.sync?.disconnect()
            val ctx = context ?: act
            (ctx?.applicationContext as? YarmiplayTvApp)?.container?.let { c ->
                c.playlist.stop()
                c.sync.disconnect()
            }
            if (ctx != null) {
                PlaybackService.stop(ctx)
            }
            if (act != null) {
                act.finishAffinity()
                act.finishAndRemoveTask()
            }
        }
    }
}
