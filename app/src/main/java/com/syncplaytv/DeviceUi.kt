package com.syncplaytv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

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

    /** Default Syncplay user name, e.g. "TV-sdkgoogleatv" or "Phone-Pixel8". */
    fun defaultUserName(kind: DeviceKind): String {
        val prefix = when (kind) {
            DeviceKind.TV -> "TV"
            DeviceKind.PHONE -> "Phone"
            DeviceKind.TABLET -> "Tablet"
        }
        val model = Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9]"), "").take(16 - prefix.length - 1)
        return if (model.isEmpty()) prefix else "$prefix-$model"
    }
}
