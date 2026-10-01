package com.syncplaytv

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import com.syncplaytv.data.SettingsStore
import com.syncplaytv.local.SafLocalLibrary
import com.syncplaytv.player.MpvOptions
import com.syncplaytv.player.MpvPlayer

private val Context.dataStore by preferencesDataStore("settings")

class SyncplayTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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
