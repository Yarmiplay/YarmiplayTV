package com.yarmiplaytv.ui.mobile

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yarmiplaytv.ui.theme.AppColors

actual val malSignInInApp = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun MalSignInPage(url: String, onNavigate: (String) -> Boolean, modifier: Modifier) {
    val navigate by rememberUpdatedState(onNavigate)
    DisposableEffect(Unit) {
        onDispose {
            // Nothing of the sign-in stays behind in the web view.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // MyAnimeList's page needs JavaScript; the app adds none of its own.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url.toString())
                }
                loadUrl(url)
                requestFocus()
            }
        },
        onRelease = { it.clearCache(true); it.destroy() },
        modifier = modifier.background(AppColors.Background),
    )
}
