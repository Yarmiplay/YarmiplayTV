package com.yarmiplaytv

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.datastore.preferences.preferencesDataStore
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.device.AndroidDeviceKeyStore
import com.yarmiplaytv.local.AndroidFrameGrabber
import com.yarmiplaytv.local.AndroidSeekFrames
import com.yarmiplaytv.local.SafLocalLibrary
import com.yarmiplaytv.player.MpvOptions
import com.yarmiplaytv.player.MpvPlayer
import com.yarmiplaytv.screenshot.AndroidScreenshotStore
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.update.UpdateSetup

private val Context.dataStore by preferencesDataStore("settings")

class YarmiplayTvApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * The UI's screens, kept outside the activity: when the system recreates it (e.g. a theme or overlay change no
     * app can handle itself) the player stays on screen instead of falling back to Home. A fresh start resets it.
     */
    val screens: SnapshotStateList<Screen> = mutableStateListOf(Screen.Home)

    override fun onCreate() {
        super.onCreate()
        DevicePlatform.init(this)
        container = AppContainer(
            settingsStore = SettingsStore(dataStore),
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "Android TV" },
            appVersion = BuildConfig.VERSION_NAME,
            createPlayer = { initial ->
                MpvPlayer(
                    this,
                    MpvOptions(
                        hwdec = if (initial.playback.hardwareDecoding) "mediacodec,mediacodec-copy" else "no",
                        audioLanguages = initial.playback.audioLanguages,
                        subtitleLanguages = initial.playback.subtitleLanguages,
                    ),
                )
            },
            createLocalLibrary = { store, scope, folders -> SafLocalLibrary(this, store, scope, folders) },
            deviceKeys = AndroidDeviceKeyStore(),
            cacheDir = cacheDir,
            screenshotStore = AndroidScreenshotStore(this),
            frameGrabber = AndroidFrameGrabber(this),
            seekFrames = AndroidSeekFrames(this),
            malClientId = BuildConfig.MAL_CLIENT_ID,
        )
        // The download page's APK (debug) installs its own updates; the Play build (release) leaves them to Play.
        UpdateSetup.install(this, container)
    }
}
