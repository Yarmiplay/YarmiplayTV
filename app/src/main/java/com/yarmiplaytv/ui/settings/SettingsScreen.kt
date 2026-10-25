package com.yarmiplaytv.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.BuildConfig
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.UnpauseMode
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.QrLinkDialog
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.ToggleRow
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.components.ValueRow
import com.yarmiplaytv.ui.mobile.rememberDirectoryPicker
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.AboutLink
import com.yarmiplaytv.ui.shared.CHANGELOG_URL
import com.yarmiplaytv.ui.shared.CHAT_ON_VIDEO_NOTE
import com.yarmiplaytv.ui.shared.DETECT_ANY_NETWORK_SUBTITLE
import com.yarmiplaytv.ui.shared.DETECT_ANY_NETWORK_TITLE
import com.yarmiplaytv.ui.shared.DETECT_SKIPS_SUBTITLE
import com.yarmiplaytv.ui.shared.DETECT_SKIPS_TITLE
import com.yarmiplaytv.ui.shared.MAL_ATTRIBUTION
import com.yarmiplaytv.ui.shared.SKIP_ALONE_SUBTITLE
import com.yarmiplaytv.ui.shared.SKIP_ALONE_TITLE
import com.yarmiplaytv.ui.shared.SKIP_BUTTON_SUBTITLE
import com.yarmiplaytv.ui.shared.SKIP_BUTTON_TITLE
import com.yarmiplaytv.ui.shared.UpdateCheckModel
import com.yarmiplaytv.ui.shared.aboutLinks
import com.yarmiplaytv.ui.shared.nextPreferred
import com.yarmiplaytv.ui.shared.preferredOf
import com.yarmiplaytv.ui.shared.readyButtonLabel
import com.yarmiplaytv.ui.shared.rememberSavedText
import com.yarmiplaytv.ui.shared.serverLabel
import com.yarmiplaytv.ui.shared.shortUrl
import com.yarmiplaytv.ui.shared.updateSourceLine
import com.yarmiplaytv.ui.mobile.UpdateProgressBar
import com.yarmiplaytv.ui.shared.updateStateLine
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.AppTheme
import com.yarmiplaytv.ui.theme.THEME_NOTE
import com.yarmiplaytv.update.UpdateCheck
import com.yarmiplaytv.update.UpdateState
import com.yarmiplaytv.update.openStoreApp
import com.yarmiplaytv.voice.VoiceAudioDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    fun saveSync(sync: SyncSettings = settings.sync, autoReady: Boolean = settings.autoReadyOnLoad) =
        container.scope.launch { container.settingsStore.saveSync(sync, autoReady) }
    fun savePlayback(p: PlaybackPrefs) = container.scope.launch { container.settingsStore.savePlayback(p) }
    val servers by container.servers.collectAsStateWithLifecycle()
    fun saveServerPrefs(preferred: String = settings.preferredServer, report: Boolean = settings.reportPlayback) =
        container.scope.launch { container.settingsStore.saveServerPrefs(preferred, report) }

    val s = settings.sync
    val pb = settings.playback
    val categories = remember(container) { TvSettingsCategory.entries.filter { it != TvSettingsCategory.MAL || container.mal.client.configured } }
    var selected by rememberSaveable { mutableStateOf(TvSettingsCategory.MEDIA) }
    var qr by remember { mutableStateOf<AboutLink?>(null) }
    // The categories on the left, the chosen one's settings beside them, as on wide phone and desktop windows.
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
            categories.forEachIndexed { i, c ->
                ActionButton(
                    c.title,
                    { selected = c },
                    // Moving through the list shows each category's settings; Right goes into them.
                    Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) selected = c }
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .testTag("settings_category_${c.name.lowercase()}"),
                    icon = c.icon,
                    primary = c == selected,
                )
            }
        }
        key(selected) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("settings"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(selected.title, style = MaterialTheme.typography.headlineSmall)
                when (selected) {
                    TvSettingsCategory.SYNCING -> {
                ValueRow(
                    "When I unpause",
                    when (s.unpauseMode) {
                        UnpauseMode.IF_OTHERS_READY -> "Play if everyone is ready"
                        UnpauseMode.IF_ALREADY_READY -> "Set ready first"
                        UnpauseMode.ALWAYS -> "Always play"
                    },
                    { saveSync(s.copy(unpauseMode = UnpauseMode.entries[(s.unpauseMode.ordinal + 1) % UnpauseMode.entries.size])) },
                    subtitle = "Same options as the desktop Syncplay client",
                )
                ToggleRow("Rewind when I'm ahead", s.rewindOnDesync, { saveSync(s.copy(rewindOnDesync = !s.rewindOnDesync)) }, subtitle = "Seek back when more than ${s.rewindThreshold.toInt()} s ahead of the room")
                ToggleRow("Slow down to catch up", s.slowOnDesync, { saveSync(s.copy(slowOnDesync = !s.slowOnDesync)) }, subtitle = "Play at 0.95× when slightly ahead")
                ToggleRow("Ready when joining a room", s.readyAtStart, { saveSync(s.copy(readyAtStart = !s.readyAtStart)) })
                ToggleRow("Ready after loading a playlist item", settings.autoReadyOnLoad, { saveSync(autoReady = !settings.autoReadyOnLoad) }, subtitle = "Mark yourself ready once the room's file is loaded")
                ToggleRow(
                    "Remember room playlists",
                    settings.autosavePlaylists,
                    { container.scope.launch { container.settingsStore.saveAutosavePlaylists(!settings.autosavePlaylists) } },
                    subtitle = "Put a room's playlist back when you rejoin and it's empty",
                )
                ToggleRow(
                    "Show room chat",
                    settings.showRoomChat,
                    { container.scope.launch { container.settingsStore.saveShowRoomChat(!settings.showRoomChat) } },
                    subtitle = "Off hides everyone's chat messages in rooms, whatever name they use",
                )
                if (settings.showRoomChat) {
                    ToggleRow(
                        "Chat on the video",
                        settings.chatOnVideo,
                        { container.scope.launch { container.settingsStore.saveChatOnVideo(!settings.chatOnVideo) } },
                        subtitle = CHAT_ON_VIDEO_NOTE,
                    )
                }
                ToggleRow(
                    "Voice chat",
                    settings.voiceChat,
                    { container.scope.launch { container.settingsStore.saveVoiceChat(!settings.voiceChat) } },
                    subtitle = "Talk in rooms on a YarmiplayServerTV server that has voice on; off hides the controls and disconnects",
                )
                if (settings.voiceChat) {
                    ToggleRow(
                        "Auto-join voice",
                        settings.voiceAutoJoin,
                        { container.scope.launch { container.settingsStore.saveVoiceAutoJoin(!settings.voiceAutoJoin) } },
                        subtitle = "Join voice automatically when you enter a room that has it; off shows Join voice until you tap it",
                        modifier = Modifier.testTag("toggle_voice_auto_join"),
                    )
                    TvVoiceDeviceRows(container)
                }
                if (container.supportsDeviceAccess) {
                    var deviceName by rememberSavedText(settings.deviceName) { name -> container.scope.launch { container.settingsStore.saveDeviceName(name) } }
                    TvTextField(
                        deviceName,
                        { deviceName = it },
                        "Device name (shown to YarmiplayServerTV hosts when asking for access)",
                        placeholder = DevicePlatform.deviceName,
                    )
                }
                    }
                    TvSettingsCategory.MEDIA -> {
                ValueRow(
                    "Media servers",
                    servers.joinToString { it.displayName }.ifEmpty { "None" },
                    { nav.push(Screen.Servers) },
                    subtitle = "Add Jellyfin and Plex servers, or sign out of one",
                )
                preferredOf(servers, settings.preferredServer)?.takeIf { servers.size > 1 }?.let { preferred ->
                    ValueRow(
                        "Preferred server",
                        serverLabel(preferred),
                        { saveServerPrefs(preferred = nextPreferred(servers, preferred.key)) },
                        subtitle = "Streams a room's file when several servers have it",
                    )
                }
                ToggleRow(
                    "Report progress to your media servers",
                    settings.reportPlayback,
                    { saveServerPrefs(report = !settings.reportPlayback) },
                    subtitle = "Send watched state and how far you got to the servers that have the file",
                )
                ToggleRow(
                    "Add media servers shared by Syncplay hosts",
                    settings.addSharedServers,
                    { container.scope.launch { container.settingsStore.saveAddSharedServers(!settings.addSharedServers) } },
                    subtitle = "When a YarmiplayServerTV host shares their Jellyfin, sign in to it automatically",
                )
                ToggleRow(
                    "Share my files with the room",
                    settings.shareFiles,
                    { container.scope.launch { container.settingsStore.saveShareFiles(!settings.shareFiles) } },
                    subtitle = "On YarmiplayServerTV servers, viewers without the playlist's file can stream it from your media folders",
                )
                val pickDownloadFolder = rememberDirectoryPicker("Save relayed files to") { uri ->
                    container.scope.launch { container.settingsStore.saveDownloadDirectory(uri) }
                }
                ValueRow(
                    "Save relayed files to",
                    if (settings.downloadDirectory.isEmpty()) "Ask each time" else container.local.folderOf(settings.downloadDirectory).name,
                    {
                        if (settings.downloadDirectory.isEmpty()) pickDownloadFolder()
                        else container.scope.launch { container.settingsStore.saveDownloadDirectory("") }
                    },
                    subtitle = if (settings.downloadDirectory.isEmpty()) "Pick a folder for the player's save button" else "Select to ask each time again",
                )
                container.screenshots?.let { screenshots ->
                    val pickScreenshotFolder = rememberDirectoryPicker("Save screenshots to") { uri ->
                        container.scope.launch { container.settingsStore.saveScreenshotDirectory(uri) }
                    }
                    val folder = settings.screenshotDirectory
                    ValueRow(
                        "Save screenshots to",
                        if (folder.isEmpty()) screenshots.defaultFolder else container.local.folderOf(folder).name,
                        {
                            if (folder.isEmpty()) pickScreenshotFolder()
                            else container.scope.launch { container.settingsStore.saveScreenshotDirectory("") }
                        },
                        subtitle = if (folder.isEmpty()) "Pick another folder for the player's screenshot button" else "Select to use ${screenshots.defaultFolder} again",
                    )
                }
                    }
                    TvSettingsCategory.MAL -> {
                val account = settings.mal
                if (account == null) {
                    ValueRow("MyAnimeList account", "Sign in", { nav.push(Screen.MalLogin) }, subtitle = "Update your list as you watch")
                } else {
                    ValueRow("MyAnimeList", account.userName, { nav.push(Screen.Mal) }, subtitle = "Your list, list updates, matched shows and signing out")
                }
                Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
                    }
                    TvSettingsCategory.PLAYBACK -> {
                ToggleRow("Hardware decoding", pb.hardwareDecoding, { savePlayback(pb.copy(hardwareDecoding = !pb.hardwareDecoding)) }, subtitle = "MediaCodec with software fallback. Takes effect after restarting the app")
                ToggleRow(
                    "Picture-in-picture when leaving the app",
                    pb.pictureInPicture,
                    { savePlayback(pb.copy(pictureInPicture = !pb.pictureInPicture)) },
                    subtitle = "A playing video keeps going, in sync, in a small window over other apps",
                )
                ToggleRow(
                    "Stay connected when in background",
                    pb.stayConnectedMinimized,
                    { savePlayback(pb.copy(stayConnectedMinimized = !pb.stayConnectedMinimized)) },
                    subtitle = "Keep room sync and playback active in background with playback controls",
                )
                ToggleRow(SKIP_ALONE_TITLE, pb.autoSkipAlone, { savePlayback(pb.copy(autoSkipAlone = !pb.autoSkipAlone)) }, subtitle = SKIP_ALONE_SUBTITLE)
                ToggleRow(SKIP_BUTTON_TITLE, pb.showSkipButton, { savePlayback(pb.copy(showSkipButton = !pb.showSkipButton)) }, subtitle = SKIP_BUTTON_SUBTITLE)
                ToggleRow(DETECT_SKIPS_TITLE, pb.detectSkips, { savePlayback(pb.copy(detectSkips = !pb.detectSkips)) }, subtitle = DETECT_SKIPS_SUBTITLE)
                if (pb.detectSkips) {
                    ToggleRow(
                        DETECT_ANY_NETWORK_TITLE,
                        pb.detectOnAnyNetwork,
                        { savePlayback(pb.copy(detectOnAnyNetwork = !pb.detectOnAnyNetwork)) },
                        subtitle = DETECT_ANY_NETWORK_SUBTITLE,
                    )
                }
                ValueRow("Seek step", "${pb.seekStepSeconds} s", {
                    val steps = listOf(5, 10, 15, 30)
                    savePlayback(pb.copy(seekStepSeconds = steps[(steps.indexOf(pb.seekStepSeconds) + 1) % steps.size]))
                }, subtitle = "D-pad left/right while the controls are hidden")
                ControlsHideSetting(pb, ::savePlayback)
                PlayerScaleSetting(pb, ::savePlayback)
                var audio by rememberSavedText(pb.audioLanguages) { text ->
                    container.scope.launch { container.settingsStore.savePlayback(container.settings.value.playback.copy(audioLanguages = text)) }
                }
                TvTextField(audio, { audio = it }, "Preferred audio languages (restart to apply)", placeholder = "jpn,ja,eng")
                var subtitles by rememberSavedText(pb.subtitleLanguages) { text ->
                    container.scope.launch { container.settingsStore.savePlayback(container.settings.value.playback.copy(subtitleLanguages = text)) }
                }
                TvTextField(subtitles, { subtitles = it }, "Preferred subtitle languages (restart to apply)", placeholder = "eng,en")
                    }
                    TvSettingsCategory.APPEARANCE -> {
                        val chosen = AppTheme.of(settings.theme)
                        AppTheme.entries.forEach { theme ->
                            ValueRow(
                                theme.label, if (theme == chosen) "Chosen" else "",
                                { container.scope.launch { container.settingsStore.saveTheme(theme.key) } },
                                Modifier.testTag("theme_${theme.key}"),
                                subtitle = theme.description,
                            )
                        }
                        Text(THEME_NOTE, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        PlayerScaleSetting(pb, ::savePlayback)
                    }
                    TvSettingsCategory.UPDATES -> UpdatesSection(container) { qr = it }
                    TvSettingsCategory.ABOUT -> {
                Text("YarmiplayTV ${BuildConfig.VERSION_NAME} · Syncplay protocol 1.7 · mpv (libmpv)", color = AppColors.TextDim)
                ValueRow("What's new", "Open", { nav.push(Screen.WhatsNew) }, subtitle = "This version's changes")
                aboutLinks.forEach { link ->
                    ValueRow(link.title, shortUrl(link.url), { qr = link }, Modifier.testTag(link.tag), subtitle = link.subtitle)
                }
                ValueRow("Open-source licenses", "View", { nav.push(Screen.Licenses) }, subtitle = "mpv, FFmpeg and the other parts this app is built on")
                    }
                }
            }
        }
    }
    qr?.let { link -> QrLinkDialog(link.title, link.url) { qr = null } }
}

/** The groups of settings on TV, in the same order as on phones and desktop. */
private enum class TvSettingsCategory(val title: String, val icon: ImageVector) {
    MEDIA("Media", Icons.Filled.VideoLibrary),
    MAL("MyAnimeList", Icons.Filled.Tv),
    SYNCING("Syncing", Icons.Filled.Sync),
    PLAYBACK("Playback", Icons.Filled.PlayCircle),
    APPEARANCE("Appearance", Icons.Filled.Palette),
    UPDATES("Updates", Icons.Filled.SystemUpdate),
    ABOUT("About", Icons.Filled.Info),
}

/** This version, "Check for updates" and installing what it finds; links are shown as QR codes with [showLink]. */
@Composable
private fun UpdatesSection(container: AppContainer, showLink: (AboutLink) -> Unit) {
    val updates = container.updates
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val check = remember(updates) { UpdateCheckModel(updates, scope) }
    // Coming back from Android's "install unknown apps" setting changes what's shown.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    Text("YarmiplayTV ${BuildConfig.VERSION_NAME} · ${updateSourceLine(updates)}", color = AppColors.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(if (check.checking) "Checking…" else "Check for updates", check::check, Modifier.testTag("check_updates"), icon = Icons.Filled.SystemUpdate, enabled = !check.checking)
        when (val result = check.result) {
            UpdateCheck.UpToDate -> Text("You're up to date.")
            is UpdateCheck.Failed -> Text(result.message, color = AppColors.Error)
            is UpdateCheck.InStore -> Text(
                when (result.available) {
                    true -> "A new version is available in ${result.store.name}."
                    false -> "You're up to date."
                    null -> "Couldn't check for updates in ${result.store.name}."
                },
            )
            else -> Unit
        }
    }
    val store = updates.store
    if (store != null) {
        val available = (check.result as? UpdateCheck.InStore)?.available
        val context = LocalContext.current
        // The store's app on the TV shows its page; a QR code for a phone only where it can't.
        fun openStore(url: String) {
            if (!openStoreApp(context, url)) showLink(AboutLink(store.name, "", url, ""))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (available == true) {
                ActionButton("Update", { if (!store.update()) store.pageUrl?.let(::openStore) }, primary = true)
            }
            store.pageUrl?.takeIf { it.startsWith("http") || it.startsWith("market") }?.let { url ->
                ActionButton(if (available == true) "Open ${store.name}" else "Check in ${store.name}", { openStore(url) }, Modifier.testTag("open_store"))
            }
        }
    }
    val s = state
    if (s != null) {
        key(lifecycleState) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YarmiplayTV ${s.update.version} is available", style = MaterialTheme.typography.titleMedium, color = AppColors.Accent)
                Text(updateStateLine(updates, s), color = AppColors.TextDim, modifier = Modifier.testTag("update_state"))
                UpdateProgressBar(s)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val canInstall = updates.installer != null
                    when {
                        s is UpdateState.Installing -> Unit
                        s is UpdateState.Ready -> ActionButton(readyButtonLabel(updates), updates::restartToInstall, Modifier.testTag("update_install"), primary = true)
                        s is UpdateState.Downloading -> ActionButton("Cancel", { updates.dismiss(remember = false) })
                        canInstall -> ActionButton(if (s is UpdateState.Failed) "Try again" else "Install", { updates.download(thenRestart = true) }, Modifier.testTag("update_install"), primary = true)
                        else -> ActionButton("Download link", { showLink(AboutLink("Download page", "", s.update.pageUrl, "")) })
                    }
                    ActionButton("All patch notes", { showLink(aboutLinks.first { it.url == CHANGELOG_URL }) })
                }
            }
        }
    }
    if (updates.platform != null) {
        ToggleRow(
            "Check for updates when the app starts",
            settings.checkForUpdates,
            { container.scope.launch { container.settingsStore.saveUpdatePrefs(!settings.checkForUpdates, settings.installUpdatesOnLaunch) } },
            subtitle = "Look for a new version on the download page (GitHub)",
        )
    }
}

/** Mic/speaker routes; each click advances to the next available device (including System default). */
@Composable
private fun TvVoiceDeviceRows(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    var inputs by remember { mutableStateOf(container.voice.listInputDevices()) }
    var outputs by remember { mutableStateOf(container.voice.listOutputDevices()) }
    LaunchedEffect(Unit) {
        while (true) {
            inputs = container.voice.listInputDevices()
            outputs = container.voice.listOutputDevices()
            delay(2_000)
        }
    }
    TvVoiceDeviceRow(
        title = "Microphone",
        subtitle = "Input for voice chat; changes apply without leaving the room",
        devices = inputs,
        selectedId = settings.voiceInputDeviceId,
        testTag = "voice_input_device",
        onSelect = { container.voice.setInputDeviceId(it) },
    )
    TvVoiceDeviceRow(
        title = "Speaker",
        subtitle = "Output for voice chat; changes apply without leaving the room",
        devices = outputs,
        selectedId = settings.voiceOutputDeviceId,
        testTag = "voice_output_device",
        onSelect = { container.voice.setOutputDeviceId(it) },
    )
}

@Composable
private fun TvVoiceDeviceRow(
    title: String,
    subtitle: String,
    devices: List<VoiceAudioDevice>,
    selectedId: String,
    testTag: String,
    onSelect: (String) -> Unit,
) {
    val selected = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
    ValueRow(
        title,
        selected?.label ?: "System default",
        {
            if (devices.isEmpty()) return@ValueRow
            val idx = devices.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
            onSelect(devices[(idx + 1) % devices.size].id)
        },
        Modifier.testTag(testTag),
        subtitle = subtitle,
    )
}

/** 0 is "Never"; after it the row offers "Custom", which shows a field for any number of seconds. */
private val controlsHidePresets = listOf(2, 3, 5, 10, 0)
private const val CONTROLS_HIDE_MAX = 60

@Composable
private fun ControlsHideSetting(pb: PlaybackPrefs, save: (PlaybackPrefs) -> Unit) {
    val seconds = pb.controlsHideSeconds
    var customPicked by remember { mutableStateOf(false) }
    val custom = customPicked || seconds !in controlsHidePresets
    ValueRow(
        "Hide player controls after",
        when {
            custom && seconds > 0 -> "$seconds s (custom)"
            custom -> "Custom"
            seconds == 0 -> "Never"
            else -> "$seconds s"
        },
        {
            val next = controlsHidePresets.indexOf(seconds) + 1
            when {
                custom -> { customPicked = false; save(pb.copy(controlsHideSeconds = controlsHidePresets.first())) }
                next == controlsHidePresets.size -> customPicked = true
                else -> save(pb.copy(controlsHideSeconds = controlsHidePresets[next]))
            }
        },
        subtitle = "While a video plays; paused videos keep the controls up",
    )
    if (custom) {
        var text by remember { mutableStateOf(if (seconds > 0) seconds.toString() else "") }
        val valid = text.toIntOrNull()?.takeIf { it in 1..CONTROLS_HIDE_MAX }
        TvTextField(
            text,
            {
                customPicked = true
                text = it.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.takeIf { s -> s in 1..CONTROLS_HIDE_MAX }?.let { s -> save(pb.copy(controlsHideSeconds = s)) }
            },
            if (valid != null || text.isEmpty()) "Custom time (seconds)" else "Custom time (seconds): enter 1 to $CONTROLS_HIDE_MAX",
            placeholder = "7",
            keyboardType = KeyboardType.Number,
        )
    }
}

private val tvPlayerScalePresets = listOf(0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 1.0f)

@Composable
private fun PlayerScaleSetting(pb: PlaybackPrefs, save: (PlaybackPrefs) -> Unit) {
    val scale = pb.tvPlayerScale
    val label = when {
        scale == 0.85f -> "85% (Default)"
        scale == 1.0f -> "100% (Full size)"
        else -> "${(scale * 100).toInt()}%"
    }
    ValueRow(
        "In-player menu scale",
        label,
        {
            val idx = tvPlayerScalePresets.indexOfFirst { kotlin.math.abs(it - scale) < 0.01f }
            val nextIdx = if (idx == -1) 0 else (idx + 1) % tvPlayerScalePresets.size
            save(pb.copy(tvPlayerScale = tvPlayerScalePresets[nextIdx]))
        },
        Modifier.testTag("player_scale"),
        subtitle = "Size of menus, controls, and overlays inside the player",
    )
}

