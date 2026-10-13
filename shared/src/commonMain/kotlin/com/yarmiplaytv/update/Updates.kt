package com.yarmiplaytv.update

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * Installs updates from the download page itself (the Windows installer, the portable zip, the macOS disk image,
 * the .deb, Android's APK); without one, the app only points to the download page.
 */
interface UpdateInstaller {
    /** Downloads [update]'s package and checks its SHA-256; [progress] gets 0 to 1. Throws on failure. */
    suspend fun download(update: Update, progress: (Float) -> Unit)

    /**
     * Runs the downloaded installer once this process has exited. With [relaunch], starts the app again
     * afterwards: the new version, or this one with a notice when the installer failed.
     */
    fun installAfterExit(relaunch: Boolean)

    /**
     * Whether installing replaces this app right away rather than after it closes (Android's installer):
     * then there's no "installs when you close" and no relaunch to wait for.
     */
    val installsNow: Boolean get() = false

    /**
     * A step the person has to take before installing can work, as a sentence (Android: allowing installs
     * from YarmiplayTV), or null when there's none. [openPendingStep] takes them there.
     */
    fun pendingStep(): String? = null
    fun openPendingStep() {}
}

/**
 * Where a store or package manager installed the app (Google Play, the Microsoft Store, Flatpak, Snap, the AUR):
 * only it may update the app, so the app never looks at the download page.
 */
interface StoreUpdates {
    /** "Google Play", "Microsoft Store", "Flathub"… */
    val name: String
    /** The app's page in the store, opened by [Updates] when [update] can't do it in the app; null when none. */
    val pageUrl: String?
    /** A command that updates the app, shown with a Copy button (Flatpak, Snap, AUR); null when none. */
    val command: String? get() = null

    /** Asks the store: true when an update is out, false when up to date, null when it can't be asked. */
    suspend fun check(): Boolean? = null

    /** Starts the store's own update in the app (Play's in-app update); false when it can't. */
    fun update(): Boolean = false
}

/** What "Check for updates" found. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    /** A newer version on the download page, now also in [Updates.state]. */
    data class Found(val update: Update) : UpdateCheck
    /** Updates come from [store]; [available] is what it said, null when it can't be asked. */
    data class InStore(val store: StoreUpdates, val available: Boolean?) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/** Looks for a newer version on the download page when the app starts and, where possible, installs it. */
class Updates(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val currentVersion: String,
    private val checker: UpdateChecker = UpdateChecker(),
    /** How often to look again while the app runs: Android keeps it running for days. */
    private val recheckMillis: Long = 6 * 60 * 60 * 1000L,
) {
    /**
     * The download page's name for this platform ("android", "windows", "macos" or "linux"), set by the host;
     * null where the app mustn't look for updates outside its store (Play builds).
     */
    var platform: String? = null
    var installer: UpdateInstaller? = null
    /** Set instead of [platform] where a store installed the app. */
    var store: StoreUpdates? = null
    /** Closes the app, for [restartToInstall]. */
    var exitApp: () -> Unit = {}

    private val _state = MutableStateFlow<UpdateState?>(null)
    val state: StateFlow<UpdateState?> = _state.asStateFlow()
    private var download: Job? = null

    private val _storeUpdate = MutableStateFlow<StoreUpdates?>(null)
    /** The [store] that has a newer version out, for the home notice; null when none (or it was put off). */
    val storeUpdate: StateFlow<StoreUpdates?> = _storeUpdate.asStateFlow()
    private var storeDismissed = false
    private var checking: Job? = null

    /**
     * Shows the notice when the page (or the [store]) has a newer version that wasn't dismissed, and looks again
     * every [recheckMillis] while the app runs. [failedInstall] is the version whose installer just failed (the
     * desktop app starts again after it): that one shows why.
     */
    fun checkOnLaunch(failedInstall: String? = null) {
        if (platform == null && store == null) return
        if (checking?.isActive == true) return
        checking = scope.launch {
            var failed = failedInstall
            while (true) {
                if (settingsStore.current().checkForUpdates) {
                    if (store != null) checkStore() else checkPage(failed)
                }
                failed = null
                delay(recheckMillis)
            }
        }
    }

    private suspend fun checkPage(failedInstall: String?) {
        val platform = platform ?: return
        // Already showing one, maybe downloading it: leave it be.
        if (_state.value != null) return
        val update = checker.check(platform, currentVersion) ?: return
        if (update.version == failedInstall) {
            showFailed(update, INSTALL_FAILED)
            return
        }
        if (update.version == settingsStore.current().dismissedUpdate) return
        Logger.i(TAG, "Version ${update.version} is available")
        show(update)
    }

    private suspend fun checkStore() {
        val store = store ?: return
        if (storeDismissed || _storeUpdate.value != null) return
        if (runCatching { store.check() }.getOrNull() == true) {
            Logger.i(TAG, "A newer version is out in ${store.name}")
            _storeUpdate.value = store
        }
    }

    /** The home notice's Update for a [store] update: the store's own in-app update, else its page via [open]. */
    fun updateFromStore(open: (String) -> Unit) {
        val store = _storeUpdate.value ?: return
        if (!store.update()) store.pageUrl?.let(open)
    }

    /** "Not now" on a [store] update: hidden until the app starts again. */
    fun dismissStoreUpdate() {
        storeDismissed = true
        _storeUpdate.value = null
    }

    /**
     * "Check for updates" in Settings: asks the download page (or the store) now, also for a version the notice was
     * dismissed for, and shows what it found in [state] so Install works from there.
     */
    suspend fun checkNow(): UpdateCheck {
        store?.let { s -> return UpdateCheck.InStore(s, runCatching { s.check() }.getOrNull()) }
        val platform = platform ?: return UpdateCheck.Failed("This version is updated by the store it came from.")
        return checker.fetch(platform, currentVersion).fold(
            onSuccess = { update ->
                if (update == null) return UpdateCheck.UpToDate
                val now = _state.value
                // Keep a download in progress or done for the same version.
                if (now == null || now.update.version != update.version || now is UpdateState.Available || now is UpdateState.Failed) {
                    _state.value = UpdateState.Available(update)
                }
                UpdateCheck.Found(update)
            },
            onFailure = { UpdateCheck.Failed("Couldn't reach the download page: ${it.message ?: "no connection"}") },
        )
    }

    /** Shows [update] as available, e.g. from tests. */
    fun show(update: Update) {
        _state.value = UpdateState.Available(update)
    }

    /** Shows that [update] couldn't be installed; [message] is the whole sentence the notice shows. */
    fun showFailed(update: Update, message: String) {
        _state.value = UpdateState.Failed(update, message)
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
                    _state.value = UpdateState.Failed(update, downloadFailed(it))
                }
        }
    }

    fun restartToInstall() {
        if (_state.value !is UpdateState.Ready) return
        val installer = installer ?: return
        if (installer.pendingStep() != null) {
            installer.openPendingStep()
            return
        }
        if (installer.installsNow) {
            // The system's installer takes over; if it's cancelled the update can be installed again.
            installer.installAfterExit(relaunch = true)
            return
        }
        installer.installAfterExit(relaunch = true)
        _state.value = null
        exitApp()
    }

    /** Called when the app closes: starts the installer of a downloaded update that wasn't installed yet. */
    fun onExit() {
        if (_state.value is UpdateState.Ready && installer?.installsNow == false) installer?.installAfterExit(relaunch = false)
    }

    /** Hides the notice; with [remember], this version isn't shown again. */
    fun dismiss(remember: Boolean = true) {
        val update = _state.value?.update ?: return
        download?.cancel()
        _state.value = null
        if (remember) scope.launch { settingsStore.saveDismissedUpdate(update.version) }
    }

    companion object {
        private const val TAG = "Updates"
        const val INSTALL_FAILED = "The installer didn't finish. Try again, or get it from the download page."

        fun downloadFailed(error: Throwable) = "Couldn't download it: ${error.message ?: "the download failed"}"
    }
}
