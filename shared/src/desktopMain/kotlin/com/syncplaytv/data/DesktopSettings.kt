package com.syncplaytv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath
import java.io.File

/** Where the desktop app keeps its settings. */
object DesktopPaths {
    fun configDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
    ): File {
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") ->
                File(env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, "SyncplayTV")
            os.startsWith("mac") || os.startsWith("darwin") ->
                File(home, "Library/Application Support/SyncplayTV")
            else ->
                File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path, "syncplaytv")
        }
    }
}

/**
 * Settings persisted with DataStore in [dir] (created if missing). Only one store per file may be
 * active in a process; cancel [scope] before opening the same file again.
 */
fun desktopSettingsStore(
    dir: File = DesktopPaths.configDir(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): SettingsStore {
    dir.mkdirs()
    val file = File(dir, "settings.preferences_pb")
    return SettingsStore(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { file.toOkioPath() }))
}
