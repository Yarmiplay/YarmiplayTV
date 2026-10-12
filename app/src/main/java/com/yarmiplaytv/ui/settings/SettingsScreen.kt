package com.yarmiplaytv.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.yarmiplaytv.ui.shared.MAL_ATTRIBUTION
import com.yarmiplaytv.ui.shared.UpdateCheckModel
import com.yarmiplaytv.ui.shared.aboutLinks
import com.yarmiplaytv.ui.shared.nextPreferred
import com.yarmiplaytv.ui.shared.preferredOf
import com.yarmiplaytv.ui.shared.readyButtonLabel
import com.yarmiplaytv.ui.shared.rememberSavedText
import com.yarmiplaytv.ui.shared.serverLabel
import com.yarmiplaytv.ui.shared.shortUrl
import com.yarmiplaytv.ui.shared.updateSourceLine
import com.yarmiplaytv.ui.shared.updateStateLine
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.update.UpdateCheck
import com.yarmiplaytv.update.UpdateState
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Syncing")
        ValueRow(
            "When I unpause",
            when (s.unpauseMode) {
                UnpauseMode.IF_OTHERS_READY -> "Play if everyone is ready"
                UnpauseMode.IF_ALREADY_READY -> "Set ready first"
                UnpauseMode.ALWAYS -> "Always play"
            },
            { saveSync(s.copy(unpauseMode = UnpauseMode.entries[(s.unpauseMode.ordinal + 1) % UnpauseMode.entries.size])) },
            Modifier.focusRequester(firstFocus),
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
        if (container.supportsDeviceAccess) {
            var deviceName by rememberSavedText(settings.deviceName) { name -> container.scope.launch { container.settingsStore.saveDeviceName(name) } }
            TvTextField(
                deviceName,
                { deviceName = it },
                "Device name (shown to YarmiplayServerTV hosts when asking for access)",
                placeholder = DevicePlatform.deviceName,
            )
        }

        SectionTitle("Media servers")
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

        if (container.mal.client.configured) {
            SectionTitle("MyAnimeList")
            val account = settings.mal
            if (account == null) {
                ValueRow("MyAnimeList account", "Sign in", { nav.push(Screen.MalLogin) }, subtitle = "Update your list as you watch")
            } else {
                ValueRow("MyAnimeList", account.userName, { nav.push(Screen.Mal) }, subtitle = "Your list, list updates, matched shows and signing out")
            }
            Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
        }

        SectionTitle("Playback")
        ToggleRow("Hardware decoding", pb.hardwareDecoding, { savePlayback(pb.copy(hardwareDecoding = !pb.hardwareDecoding)) }, subtitle = "MediaCodec with software fallback. Takes effect after restarting the app")
        // Many TVs have no picture-in-picture.
        val context = LocalContext.current
        if (remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }) {
            ToggleRow(
                "Picture-in-picture when leaving the app",
                pb.pictureInPicture,
                { savePlayback(pb.copy(pictureInPicture = !pb.pictureInPicture)) },
                subtitle = "A playing video keeps going, in sync, in a small window over other apps",
            )
        }
        ValueRow("Seek step", "${pb.seekStepSeconds} s", {
            val steps = listOf(5, 10, 15, 30)
            savePlayback(pb.copy(seekStepSeconds = steps[(steps.indexOf(pb.seekStepSeconds) + 1) % steps.size]))
        }, subtitle = "D-pad left/right while the controls are hidden")
        ControlsHideSetting(pb, ::savePlayback)
        var audio by rememberSavedText(pb.audioLanguages) { text ->
            container.scope.launch { container.settingsStore.savePlayback(container.settings.value.playback.copy(audioLanguages = text)) }
        }
        TvTextField(audio, { audio = it }, "Preferred audio languages (restart to apply)", placeholder = "jpn,ja,eng")
        var subtitles by rememberSavedText(pb.subtitleLanguages) { text ->
            container.scope.launch { container.settingsStore.savePlayback(container.settings.value.playback.copy(subtitleLanguages = text)) }
        }
        TvTextField(subtitles, { subtitles = it }, "Preferred subtitle languages (restart to apply)", placeholder = "eng,en")

        var qr by remember { mutableStateOf<AboutLink?>(null) }
        UpdatesSection(container) { qr = it }

        SectionTitle("About")
        Text("YarmiplayTV ${BuildConfig.VERSION_NAME} · Syncplay protocol 1.7 · mpv (libmpv)", color = AppColors.TextDim)
        ValueRow("What's new", "Open", { nav.push(Screen.WhatsNew) }, subtitle = "This version's changes")
        aboutLinks.forEach { link ->
            ValueRow(link.title, shortUrl(link.url), { qr = link }, Modifier.testTag(link.tag), subtitle = link.subtitle)
        }
        ValueRow("Open-source licenses", "View", { nav.push(Screen.Licenses) }, subtitle = "mpv, FFmpeg and the other parts this app is built on")
        qr?.let { link -> QrLinkDialog(link.title, link.url) { qr = null } }
    }
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
    SectionTitle("Updates")
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
                    null -> "Updates come from ${result.store.name}."
                },
            )
            else -> Unit
        }
    }
    val store = updates.store
    if (store != null) {
        val available = (check.result as? UpdateCheck.InStore)?.available
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (available == true) {
                ActionButton("Update", { if (!store.update()) store.pageUrl?.let { showLink(AboutLink(store.name, "", it, "")) } }, primary = true)
            }
            store.pageUrl?.takeIf { it.startsWith("http") }?.let { url ->
                ActionButton("${store.name} link", { showLink(AboutLink(store.name, "", url, "")) })
            }
        }
    }
    val s = state
    if (s != null) {
        key(lifecycleState) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YarmiplayTV ${s.update.version} is available", style = MaterialTheme.typography.titleMedium, color = AppColors.Accent)
                Text(updateStateLine(updates, s), color = AppColors.TextDim, modifier = Modifier.testTag("update_state"))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val canInstall = updates.installer != null
                    when {
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
