package com.yarmiplaytv.update

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    val update: Update

    data class Available(override val update: Update) : UpdateState
    data class Downloading(override val update: Update, val progress: Float) : UpdateState
    /** Downloaded; installs when the app closes, or right away with [Updates.restartToInstall]. */
    data class Ready(override val update: Update) : UpdateState
    data class Failed(override val update: Update, val message: String) : UpdateState
}

/** Installs updates itself (the Windows installer); without one, the app only points to the download page. */
interface UpdateInstaller {
    /** Downloads [update]'s package and checks its SHA-256; [progress] gets 0 to 1. Throws on failure. */
    suspend fun download(update: Update, progress: (Float) -> Unit)

    /** Runs the downloaded installer once this process has exited, then starts the new version if [relaunch]. */
    fun installAfterExit(relaunch: Boolean)
}

/** Looks for a newer version on the download page when the app starts and, where possible, installs it. */
class Updates(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val currentVersion: String,
    private val checker: UpdateChecker = UpdateChecker(),
) {
    /**
     * The download page's name for this platform ("android", "windows", "macos" or "linux"), set by the host;
     * null where the app mustn't look for updates outside its store (Play builds).
     */
    var platform: String? = null
    var installer: UpdateInstaller? = null
    /** Closes the app, for [restartToInstall]. */
    var exitApp: () -> Unit = {}

    private val _state = MutableStateFlow<UpdateState?>(null)
    val state: StateFlow<UpdateState?> = _state.asStateFlow()
    private var download: Job? = null

    fun checkOnLaunch() {
        val platform = platform ?: return
        scope.launch {
            val settings = settingsStore.current()
            if (!settings.checkForUpdates) return@launch
            val update = checker.check(platform, currentVersion) ?: return@launch
            val autoInstall = settings.installUpdatesOnLaunch && installer != null
            if (update.version == settings.dismissedUpdate && !autoInstall) return@launch
            Logger.i(TAG, "Version ${update.version} is available")
            show(update)
            if (autoInstall) download(thenRestart = false)
        }
    }

    /** Shows [update] as available, e.g. from tests. */
    fun show(update: Update) {
        _state.value = UpdateState.Available(update)
    }

    /** Downloads the update, then restarts into the installer if [thenRestart] or installs when the app closes. */
    fun download(thenRestart: Boolean) {
        val installer = installer ?: return
        val update = _state.value?.update ?: return
        if (download?.isActive == true) return
        download = scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            runCatching { installer.download(update) { p -> _state.value = UpdateState.Downloading(update, p) } }
                .onSuccess {
                    _state.value = UpdateState.Ready(update)
                    if (thenRestart) restartToInstall()
                }
                .onFailure {
                    Logger.w(TAG, "Couldn't download version ${update.version}: ${it.message}")
                    _state.value = UpdateState.Failed(update, it.message ?: "Download failed")
                }
        }
    }

    fun restartToInstall() {
        if (_state.value !is UpdateState.Ready) return
        installer?.installAfterExit(relaunch = true)
        _state.value = null
        exitApp()
    }

    /** Called when the app closes: starts the installer of a downloaded update that wasn't installed yet. */
    fun onExit() {
        if (_state.value is UpdateState.Ready) installer?.installAfterExit(relaunch = false)
    }

    /** Hides the notice; with [remember], this version isn't shown again. */
    fun dismiss(remember: Boolean = true) {
        val update = _state.value?.update ?: return
        download?.cancel()
        _state.value = null
        if (remember) scope.launch { settingsStore.saveDismissedUpdate(update.version) }
    }

    private companion object {
        const val TAG = "Updates"
    }
}
