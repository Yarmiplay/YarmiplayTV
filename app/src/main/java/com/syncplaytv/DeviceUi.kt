package com.syncplaytv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceUi {
    /** Debug/testing override: `am start ... --es ui mobile|tv`. */
    const val EXTRA_UI = "ui"

    fun kind(context: Context, intent: Intent? = null): DeviceKind {
        when (intent?.getStringExtra(EXTRA_UI)) {
            "tv" -> return DeviceKind.TV
            "mobile" -> return mobileKind(context)
        }
        val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        val tv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return if (tv) DeviceKind.TV else mobileKind(context)
    }

    private fun mobileKind(context: Context): DeviceKind =
        if (context.resources.configuration.smallestScreenWidthDp >= 600) DeviceKind.TABLET else DeviceKind.PHONE

    fun defaultUserName(kind: DeviceKind): String = defaultSyncplayName(kind)
}
