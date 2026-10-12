package com.yarmiplaytv.update

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.yarmiplaytv.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Updates through Google Play: asks Play whether a newer version is out and runs Play's in-app update, which
 * downloads and installs it. A copy that Play didn't install (e.g. sideloaded) can't use it, so Settings opens the
 * app's Play Store page instead.
 */
internal class PlayUpdater(
    private val app: Application,
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(app),
) : StoreUpdates {
    override val name = "Google Play"
    override val pageUrl = "https://play.google.com/store/apps/details?id=${app.packageName}"

    private var resumed: WeakReference<Activity>? = null
    private var info: AppUpdateInfo? = null
    private var started = false

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                // An immediate update left before it finished (e.g. Home pressed): Play asks to carry on with it.
                if (started) {
                    manager.appUpdateInfo.addOnSuccessListener { i ->
                        if (i.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) start(i, activity)
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Whether Play installed this copy: only then can Play's in-app update replace it. */
    private val fromPlay: Boolean
        get() = runCatching {
            val installer = if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
            installer == PLAY_STORE
        }.getOrDefault(false)

    override suspend fun check(): Boolean? {
        if (!fromPlay) return null
        val found = suspendCancellableCoroutine<AppUpdateInfo?> { cont ->
            manager.appUpdateInfo
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    Logger.w(TAG, "Couldn't ask Google Play for updates: ${it.message}")
                    cont.resume(null)
                }
        } ?: return null
        info = found
        return when (found.updateAvailability()) {
            UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> true
            UpdateAvailability.UPDATE_NOT_AVAILABLE -> false
            else -> null
        }
    }

    override fun update(): Boolean {
        val info = info ?: return false
        val activity = resumed?.get() ?: return false
        val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        val inProgress = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        if (!available && !inProgress) return false
        return start(info, activity)
    }

    private fun start(info: AppUpdateInfo, activity: Activity): Boolean = runCatching {
        // Play keeps the AppUpdateInfo single-use; a used one throws, and Settings then opens the Play Store page.
        manager.startUpdateFlow(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
        started = true
        this.info = null
    }.onFailure { Logger.w(TAG, "Couldn't start Google Play's update: ${it.message}") }.isSuccess

    private companion object {
        const val TAG = "PlayUpdater"
        const val PLAY_STORE = "com.android.vending"
    }
}
