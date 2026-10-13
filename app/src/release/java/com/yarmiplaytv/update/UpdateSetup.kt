package com.yarmiplaytv.update

import android.app.Application
import com.yarmiplaytv.AppContainer

/**
 * The Play build: only Google Play updates it, through Play's in-app update. It never reads the download page,
 * downloads an APK or asks to install apps (Play's Device and Network Abuse policy).
 */
object UpdateSetup {
    fun install(app: Application, container: AppContainer) {
        container.updates.store = PlayUpdater(app)
        // Only asks Play whether there's a newer version, for the home screen's notice.
        container.updates.checkOnLaunch()
    }
}
