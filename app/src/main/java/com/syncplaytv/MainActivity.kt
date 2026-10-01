package com.syncplaytv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syncplaytv.ui.TvRoot
import com.syncplaytv.ui.mobile.MobileRoot
import com.syncplaytv.ui.mobile.MobileTheme
import com.syncplaytv.ui.theme.SyncplayTvTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SyncplayTvApp).container

    lateinit var deviceKind: DeviceKind
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceKind = DeviceUi.kind(this, intent)
        container.deviceKind = deviceKind
        if (deviceKind == DeviceKind.TV) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            setContent { SyncplayTvTheme { TvRoot(container) } }
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
            setContent { MobileTheme { MobileRoot(container, deviceKind) } }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * `am start -n com.syncplaytv/.MainActivity --es url <stream>` or a VIEW intent plays a URL directly;
     * content:// URIs (e.g. "Open with" from a file manager) play as local files.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (data != null && (data.scheme == "content" || data.scheme == "file")) {
            container.playlist.playLocal(data, inRoom = false)
            return
        }
        val url = intent?.getStringExtra("url") ?: data?.toString()
        if (!url.isNullOrBlank()) container.playlist.playUrl(url)
    }
}
