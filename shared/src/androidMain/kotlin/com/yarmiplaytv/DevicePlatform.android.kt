package com.yarmiplaytv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

actual object DevicePlatform {
    private var appContext: Context? = null

    /** Called once from the Application before anything asks for multicast. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual val isTv: Boolean
        get() {
            val ctx = appContext ?: return false
            val uiMode = ctx.getSystemService(UiModeManager::class.java)?.currentModeType
            return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }

    actual val deviceModel: String get() = Build.MODEL.orEmpty()

    actual val deviceName: String
        get() = appContext?.let { runCatching { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()

    actual val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")

    actual val onLocalNetwork: Boolean
        get() {
            val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull() ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

    actual val onUnmeteredNetwork: Boolean
        get() {
            val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            return connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered
        }

    actual suspend fun <T> withMulticastLock(tag: String, block: suspend () -> T): T {
        val wifi = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock(tag)?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }
}
