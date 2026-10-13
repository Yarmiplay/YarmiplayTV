package com.yarmiplaytv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.yarmiplaytv.ui.TvRoot
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.YarmiplayTvTheme

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
