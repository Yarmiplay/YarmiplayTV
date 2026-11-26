package com.yarmiplaytv

import com.yarmiplaytv.ui.theme.appIconLabels
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.yarmiplaytv.ui.player.playbackShowsVideo
import com.yarmiplaytv.playback.PlaybackService
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.TvRoot
import com.yarmiplaytv.ui.splash.LaunchSplash
import com.yarmiplaytv.ui.splash.SplashKeyAction
import com.yarmiplaytv.ui.splash.shouldShowLaunchSplash
import com.yarmiplaytv.ui.splash.splashForm
import com.yarmiplaytv.ui.splash.splashKeyAction
import com.yarmiplaytv.ui.mobile.ACTION_PIP_EXIT
import com.yarmiplaytv.ui.mobile.ACTION_PIP_PLAY_PAUSE
import com.yarmiplaytv.ui.mobile.MobileRoot
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.PlayerPictureInPicture
import com.yarmiplaytv.ui.mobile.appPalette
import com.yarmiplaytv.ui.mobile.pipParams
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.YarmiplayTvTheme
import com.yarmiplaytv.local.SafLocalLibrary
import com.yarmiplaytv.local.StorageAccessPolicy
import com.yarmiplaytv.voice.AndroidMicPermissions
import com.yarmiplaytv.voice.TvVoiceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as YarmiplayTvApp).container

    /** Cold-start intro. The activity draws the real UI underneath the whole time. */
    private var launchSplashActive by mutableStateOf(false)

    /** The key that dismissed the intro; its up must not activate the control that then has focus. */
    private var swallowSplashKeyUp = false

    /** Attached to the mic-permission bridge in onCreate; results arrive via [onRequestPermissionsResult]. */
    private val androidMicPermissions = AndroidMicPermissions(this)

    private data class PendingExternalVideo(val uri: String, val name: String)
    private var pendingExternalVideo: PendingExternalVideo? = null

    // MainActivity extends ComponentActivity (no Fragment dependency); androidx.activity's lint check flags
    // registerForActivityResult whenever androidx.fragment isn't on the classpath.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val sidecarFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        val pending = pendingExternalVideo ?: return@registerForActivityResult
        pendingExternalVideo = null
        lifecycleScope.launch {
            if (folder != null) {
                container.local.addFolder(folder.toString())
                val sidecars = withContext(Dispatchers.IO) {
                    container.local.sidecarsInFolder(folder.toString(), pending.name)
                }
                if (sidecars.isNotEmpty()) {
                    container.playlist.rememberSidecars(pending.uri, sidecars, select = false)
                }
            }
            container.playlist.playLocal(pending.uri, inRoom = false)
        }
    }

    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val allFilesAccessSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = pendingExternalVideo ?: return@registerForActivityResult
        pendingExternalVideo = null
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                (container.local as? SafLocalLibrary)?.addMountedStorageRoots()
                val sidecars = withContext(Dispatchers.IO) {
                    container.local.sidecarsBeside(pending.uri, pending.name)
                }
                if (sidecars.isNotEmpty()) {
                    container.playlist.rememberSidecars(pending.uri, sidecars, select = false)
                }
            }
            container.playlist.playLocal(pending.uri, inRoom = false)
        }
    }

    private val pipExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            exit(context ?: this@MainActivity)
        }
    }

    /**
     * The system picture-in-picture pause button. Registered once: the root and the player both call
     * rememberPictureInPicture, and a receiver in each of those toggled pause twice, so the button did nothing.
     */
    private val pipPlayPauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val player = container.player
            player.setPaused(!player.isPaused)
        }
    }

    lateinit var deviceKind: DeviceKind
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ system splash stays a flat #0E1116 (theme). The intro is drawn after this returns.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }
        activeActivity = this
        isExiting = false
        container.onExitApp = { exit(this) }
        container.updates.exitApp = { exit(this) }
        deviceKind = DeviceUi.kind(this, intent)
        container.deviceKind = deviceKind
        container.micPermissions.delegate = androidMicPermissions
        val app = application as YarmiplayTvApp
        val underTest = System.getProperty("yarmiplaytv.noUpdateCheck") != null
        // Intents are handled below whether or not the intro plays, so open-with and stream links aren't held back.
        launchSplashActive = shouldShowLaunchSplash(
            restoredInstance = savedInstanceState != null,
            alreadyPlayed = app.launchSplashPlayed,
            underTest = underTest,
        )
        if (!underTest) app.launchSplashPlayed = true
        if (launchSplashActive && deviceKind != DeviceKind.TV) {
            val transparent = android.graphics.Color.TRANSPARENT
            enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(transparent), navigationBarStyle = SystemBarStyle.dark(transparent))
        }
        // Like saved instance state: a recreated activity carries on where it was, a new one starts at Home.
        val screens = (application as YarmiplayTvApp).screens
        if (savedInstanceState == null) {
            screens.clear()
            screens.add(Screen.Home)
        }
        if (deviceKind == DeviceKind.TV) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            setContent {
                YarmiplayTvTheme(appPalette(container), appIconLabels(container)) {
                    Box(Modifier.fillMaxSize()) {
                        TvRoot(container, screens)
                        if (launchSplashActive) LaunchSplash(splashForm(deviceKind)) { launchSplashActive = false }
                    }
                }
            }
        } else {
            setContent {
                val palette = appPalette(container)
                val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
                // The landscape player is dark whatever the theme; portrait player uses the theme background.
                val lightBars = palette.isLight && (screens.lastOrNull() != Screen.Player || isPortrait)
                LaunchedEffect(lightBars, palette) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    val style = if (lightBars) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    // What shows before the first frame and around it, e.g. while the keyboard animates.
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background.toArgb()))
                }
                MobileTheme(palette, appIconLabels(container)) {
                    Box(Modifier.fillMaxSize()) {
                        MobileRoot(container, deviceKind, screens)
                        if (launchSplashActive) LaunchSplash(splashForm(deviceKind)) { launchSplashActive = false }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)

        if (!underTest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val hasPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (hasPip) {
            ContextCompat.registerReceiver(this, pipExitReceiver, IntentFilter(ACTION_PIP_EXIT), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, pipPlayPauseReceiver, IntentFilter(ACTION_PIP_PLAY_PAUSE), ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        if (hasPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lifecycleScope.launch {
                // Only what the parameters are made of: the player's clock ticks several times a second.
                combine(container.settings, container.player.state) { settings, state ->
                    PipShape(settings.playback.pictureInPicture && playbackShowsVideo(state), state.displayAspect, state.paused)
                }.distinctUntilChanged().collect { shape ->
                    val params = pipParams(this@MainActivity, shape.aspect, shape.paused, autoEnter = shape.auto)
                    runCatching { setPictureInPictureParams(params) }
                }
            }
        }
    }

    /**
     * TV remote mic / search keys are hold-to-talk (many remotes only stream while held).
     * The focusable Mic button remains tap-to-toggle for remotes that never deliver these keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (launchSplashActive || swallowSplashKeyUp) {
            val action = if (::deviceKind.isInitialized) {
                splashKeyAction(splashForm(deviceKind), event.keyCode == KeyEvent.KEYCODE_BACK)
            } else {
                SplashKeyAction.Ignore
            }
            if (action == SplashKeyAction.Pass && !swallowSplashKeyUp) return super.dispatchKeyEvent(event)
            if (launchSplashActive && action == SplashKeyAction.Skip &&
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            ) {
                launchSplashActive = false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) swallowSplashKeyUp = true
            if (event.action == KeyEvent.ACTION_UP) swallowSplashKeyUp = false
            return true
        }
        if (deviceKind == DeviceKind.TV && TvVoiceKeys.isVoiceHoldKey(event.keyCode)) {
            val voice = container.voice.ui.value
            if (voice.showMicButton) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        container.voice.noteRemoteToggleKey(TvVoiceKeys.labelFor(event.keyCode))
                        container.voice.setTalkLatched(true)
                    }
                    KeyEvent.ACTION_UP -> container.voice.setTalkLatched(false)
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (androidMicPermissions.onRequestPermissionsResult(requestCode, grantResults)) return
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        val isTest = System.getProperty("yarmiplaytv.noUpdateCheck") != null
        if (!isExiting && !isTest) {
            val settings = container.settings.value
            val playerState = container.player.state.value
            val inRoom = container.sync.room.value.status == ConnectionStatus.CONNECTED
            val isLoaded = playerState.fileLoaded
            val voiceActive = (deviceKind == DeviceKind.PHONE || deviceKind == DeviceKind.TABLET) &&
                container.voice.ui.value.showUi &&
                container.voice.ui.value.connection == com.yarmiplaytv.voice.VoiceConnection.CONNECTED
            // Phones/tablets keep the FGS up while room voice is connected so talk continues in the background.
            if (voiceActive || (settings.playback.stayConnectedMinimized && (isLoaded || inRoom))) {
                PlaybackService.start(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipExitReceiver) }
        runCatching { unregisterReceiver(pipPlayPauseReceiver) }
        if (activeActivity == this) {
            activeActivity = null
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val hasPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (hasPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val settings = container.settings.value
            val player = container.player
            val state = player.state.value
            val isVideo = playbackShowsVideo(state)
            if (settings.playback.pictureInPicture && isVideo && !isInPictureInPictureMode) {
                val screens = (application as YarmiplayTvApp).screens
                if (screens.lastOrNull() != Screen.Player) {
                    screens.add(Screen.Player)
                }
                val params = pipParams(this, state.displayAspect, state.paused, autoEnter = true)
                runCatching { enterPictureInPictureMode(params) }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerPictureInPicture.active = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            val screens = (application as YarmiplayTvApp).screens
            if (screens.lastOrNull() != Screen.Player) {
                screens.add(Screen.Player)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * `am start -n com.yarmiplaytv/.MainActivity --es url <stream>` or a VIEW intent plays a URL directly;
     * content:// URIs (e.g. "Open with" from a file manager) play as local files.
     * Any app can send these, so a file in this app's own storage is ignored, and only stream addresses play.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (data != null && (data.scheme == "content" || data.scheme == "file")) {
            if (data.scheme == "file" && IntentMedia.isPrivateFile(data.path, privateDirs())) return
            openExternalVideo(data)
            return
        }
        val url = intent?.getStringExtra("url") ?: data?.toString()
        if (!url.isNullOrBlank() && IntentMedia.isStreamUrl(url)) container.playlist.playUrl(url)
    }

    /**
     * Plays a single video opened from another app ("Open with"). When the app cannot yet see sibling
     * subtitle files beside it, offers to grant access to its folder (SAF folder picker on Google Play,
     * all-files access on the website APK) before starting playback.
     */
    private fun openExternalVideo(uri: Uri) {
        val videoUri = uri.toString()
        if (System.getProperty("yarmiplaytv.noUpdateCheck") != null) {
            container.playlist.playLocal(videoUri, inRoom = false)
            return
        }
        lifecycleScope.launch {
            val video = withContext(Dispatchers.IO) { container.local.describe(videoUri) }
            val needsAccess = withContext(Dispatchers.IO) {
                container.local.needsSidecarFolderAccess(videoUri, video.name)
            }
            if (!needsAccess) {
                container.playlist.playLocal(videoUri, inRoom = false)
                return@launch
            }
            val pending = PendingExternalVideo(videoUri, video.name)
            pendingExternalVideo = pending
            val useAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && declaresAllFilesAccess()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Find subtitles beside videos")
                .setMessage(
                    if (useAllFiles) {
                        "Allow YarmiplayTV to access storage so it can find external subtitle files next to this video."
                    } else {
                        "Choose the folder containing this video so YarmiplayTV can find external subtitle files next to it."
                    },
                )
                .setPositiveButton(if (useAllFiles) "Allow" else "Choose folder") { _, _ ->
                    if (useAllFiles) {
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                        try {
                            allFilesAccessSettings.launch(settingsIntent)
                        } catch (_: ActivityNotFoundException) {
                            pendingExternalVideo = null
                            container.playlist.playLocal(videoUri, inRoom = false)
                        }
                    } else {
                        try {
                            sidecarFolderPicker.launch(null)
                        } catch (_: ActivityNotFoundException) {
                            pendingExternalVideo = null
                            Toast.makeText(this@MainActivity, StorageAccessPolicy.NO_DOCUMENTS_UI, Toast.LENGTH_LONG).show()
                            container.playlist.playLocal(videoUri, inRoom = false)
                        }
                    }
                }
                .setNegativeButton("Play without subtitles") { _, _ ->
                    pendingExternalVideo = null
                    container.playlist.playLocal(videoUri, inRoom = false)
                }
                .setOnCancelListener {
                    if (pendingExternalVideo == pending) {
                        pendingExternalVideo = null
                        container.playlist.playLocal(videoUri, inRoom = false)
                    }
                }
                .show()
        }
    }

    /** True only for the website APK, whose debug manifest declares all-files access. The Play bundle does not. */
    private fun declaresAllFilesAccess(): Boolean = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
    }.getOrDefault(false)

    /** This app's own storage: files, cache, no_backup, databases (and their device-protected twin). */
    private fun privateDirs(): List<String> = listOfNotNull(
        applicationInfo.dataDir,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) applicationInfo.deviceProtectedDataDir else null,
    )

    private data class PipShape(val auto: Boolean, val aspect: Double, val paused: Boolean)

    companion object {
        var activeActivity: MainActivity? = null
        var isExiting: Boolean = false

        fun exit(context: Context? = null) {
            isExiting = true
            val act = activeActivity
            act?.container?.playlist?.stop()
            act?.container?.sync?.disconnect()
            val ctx = context ?: act
            (ctx?.applicationContext as? YarmiplayTvApp)?.container?.let { c ->
                c.playlist.stop()
                c.sync.disconnect()
            }
            if (ctx != null) {
                PlaybackService.stop(ctx)
            }
            if (act != null) {
                act.finishAffinity()
                act.finishAndRemoveTask()
            }
        }
    }
}
