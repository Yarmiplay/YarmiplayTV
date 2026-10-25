package com.yarmiplaytv.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * RECORD_AUDIO runtime permission, requested only when the user taps Mic (never at launch).
 * Wired into [MicPermissionBridge] from [com.yarmiplaytv.MainActivity].
 */
class AndroidMicPermissions(
    private val activity: ComponentActivity,
) : MicPermissions {
    @Volatile private var asked = false
    @Volatile private var pending: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cb = pending
        pending = null
        cb?.invoke(granted)
    }

    override fun granted(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun request(onResult: (granted: Boolean) -> Unit) {
        if (granted()) {
            onResult(true)
            return
        }
        asked = true
        pending = onResult
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun permanentlyDenied(): Boolean {
        if (granted() || !asked) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity as Activity,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    override fun openAppSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
