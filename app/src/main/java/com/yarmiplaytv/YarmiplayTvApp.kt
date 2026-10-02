package com.yarmiplaytv

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.local.SafLocalLibrary
import com.yarmiplaytv.player.MpvOptions
import com.yarmiplaytv.player.MpvPlayer

private val Context.dataStore by preferencesDataStore("settings")

class YarmiplayTvApp : Application() {
    lateinit var container: AppContainer
        private set

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
        )
    }
}
