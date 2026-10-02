package com.yarmiplaytv.data

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
    ): File = dirNamed("YarmiplayTV", osName, env, home)

    /** The folder the app used while it was called SyncplayTV. */
    fun legacyConfigDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
    ): File = dirNamed("SyncplayTV", osName, env, home)

    /** Moves [legacy] to [target] if only the legacy folder exists, so settings survive the rename. */
    fun migrateLegacyConfig(target: File = configDir(), legacy: File = legacyConfigDir()): File {
        if (!target.exists() && legacy.isDirectory) legacy.renameTo(target)
        return target
    }

    private fun dirNamed(name: String, osName: String, env: (String) -> String?, home: String): File {
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") ->
                File(env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, name)
            os.startsWith("mac") || os.startsWith("darwin") ->
                File(home, "Library/Application Support/$name")
            else ->
                File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path, name.lowercase())
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
