package com.yarmiplaytv.update

import android.app.Application
import com.yarmiplaytv.AppContainer

/**
 * The download page's APK: looks for updates on the download page and installs them through the system's
 * installer. The Play build has its own UpdateSetup (src/release), which leaves updating to Play.
 */
object UpdateSetup {
    fun install(app: Application, container: AppContainer) {
        val updates = container.updates
        updates.platform = "android"
        updates.installer = ApkUpdater(app, onFailed = updates::showFailed, onCancelled = updates::installCancelled)
        val failed = ApkUpdater.takeFailedInstall(app)
        // Set by the instrumented tests' runner, so tests don't depend on what the live download page offers.
        if (System.getProperty("yarmiplaytv.noUpdateCheck") == null) updates.checkOnLaunch(failed)
    }
}
