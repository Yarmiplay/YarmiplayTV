package com.yarmiplaytv.ui.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
actual fun MalSignInPage(url: String, onNavigate: (String) -> Boolean, modifier: Modifier, remotePointer: Boolean) {
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
            val web = WebView(context).apply {
                // MyAnimeList's page needs JavaScript; the app adds none of its own.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Focusing the web view would otherwise focus the page's first field, scrolling it to the top.
                settings.setNeedInitialFocus(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url.toString())
                }
                loadUrl(url)
            }
            val view: View = if (remotePointer) RemotePointer(context, web) else web
            view.apply { requestFocus() }
        },
        onRelease = { view ->
            val web = (view as? RemotePointer)?.web ?: view as WebView
            web.clearCache(true)
            web.destroy()
        },
        modifier = modifier.background(AppColors.Background),
    )
}

/**
 * A pointer over [web] for a remote, since a web page can't be reached with arrows alone: the arrows move it
 * (faster while held) and scroll the page at its edges, and OK taps where it is, which also opens the keyboard
 * in a text field. A computer keyboard's Enter still goes to the page.
 */
@SuppressLint("ViewConstructor")
private class RemotePointer(context: Context, val web: WebView) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var x = -1f
    private var y = -1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    init {
        setWillNotDraw(false)
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (x < 0) {
            x = w / 2f
            y = h / 3f
        } else {
            x = x.coerceIn(0f, w - 1f)
            y = y.coerceIn(0f, h - 1f)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(0, -1, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(0, 1, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(-1, 0, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(1, 0, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_CENTER -> if (down && event.repeatCount == 0) tap()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.device?.keyboardType == KeyCharacterMap.ALPHA) return super.dispatchKeyEvent(event)
                if (down && event.repeatCount == 0) tap()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun move(dx: Int, dy: Int, repeat: Int) {
        val step = (STEP_DP + minOf(repeat, MAX_SPEEDUP) * SPEEDUP_DP) * density
        val edge = EDGE_DP * density
        val nx = x + dx * step
        val ny = y + dy * step
        // At an edge, the page moves under the pointer instead.
        if (dy != 0 && (ny < edge || ny > height - edge) && web.canScrollVertically(dy)) web.scrollBy(0, (dy * step).toInt())
        if (dx != 0 && (nx < edge || nx > width - edge) && web.canScrollHorizontally(dx)) web.scrollBy((dx * step).toInt(), 0)
        x = nx.coerceIn(0f, width - 1f)
        y = ny.coerceIn(0f, height - 1f)
        invalidate()
    }

    private fun tap() {
        if (!web.hasFocus()) web.requestFocus()
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val touch = MotionEvent.obtain(time, time, action, x, y, 0)
            touch.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(touch)
            touch.recycle()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (x < 0) return
        val r = RADIUS_DP * density
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, ring)
    }

    private companion object {
        const val STEP_DP = 12f
        const val SPEEDUP_DP = 3f
        const val MAX_SPEEDUP = 12
        const val EDGE_DP = 24f
        const val RADIUS_DP = 9f
    }
}
