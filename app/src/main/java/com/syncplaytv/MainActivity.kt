package com.syncplaytv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.syncplaytv.ui.AppRoot
import com.syncplaytv.ui.theme.SyncplayTvTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SyncplayTvApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyncplayTvTheme { AppRoot(container) }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `am start -n com.syncplaytv/.MainActivity --es url <stream>` or a VIEW intent plays a URL directly. */
    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("url") ?: intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.toString()
        if (!url.isNullOrBlank()) container.playlist.playUrl(url)
    }
}
