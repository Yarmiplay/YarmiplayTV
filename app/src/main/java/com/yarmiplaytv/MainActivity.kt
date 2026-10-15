package com.yarmiplaytv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.playback.PlaybackService
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.TvRoot
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.PlayerPictureInPicture
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.pipParams
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.YarmiplayTvTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as YarmiplayTvApp).container

    lateinit var deviceKind: DeviceKind
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceKind = DeviceUi.kind(this, intent)
        container.deviceKind = deviceKind
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
                // The player is dark whatever the theme, and so are the bars over it.
                val lightBars = palette.isLight && screens.lastOrNull() != Screen.Player
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        lifecycleScope.launch {
            combine(container.settings, container.player.state, container.sync.room) { settings, playerState, room ->
                settings.playback.stayConnectedMinimized &&
                    (playerState.fileLoaded || room.status == ConnectionStatus.CONNECTED)
            }.distinctUntilChanged().collect { active ->
                if (active) {
                    PlaybackService.start(this@MainActivity)
                } else {
                    PlaybackService.stop(this@MainActivity)
                }
            }
        }

        if (deviceKind != DeviceKind.TV && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (deviceKind != DeviceKind.TV && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settings = container.settings.value
            val player = container.player
            val state = player.state.value
            val isVideo = state.fileLoaded && (state.videoWidth > 0 || state.displayAspect > 0 || !FileNames.isAudio(state.url.orEmpty()))
            if (settings.playback.pictureInPicture && isVideo) {
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
}
