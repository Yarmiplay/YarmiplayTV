package com.yarmiplaytv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

actual object DevicePlatform {
    private var appContext: Context? = null

    /** Called once from the Application before anything asks for multicast. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual val deviceModel: String get() = Build.MODEL.orEmpty()

    actual val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")

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
