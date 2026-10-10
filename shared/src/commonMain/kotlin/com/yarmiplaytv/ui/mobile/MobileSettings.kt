package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.UnpauseMode
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.nextPreferred
import com.yarmiplaytv.ui.shared.preferredOf
import com.yarmiplaytv.ui.shared.serverLabel
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun MobileSettingsScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    val servers by container.servers.collectAsStateWithLifecycle()
    fun saveServerPrefs(preferred: String = settings.preferredServer, report: Boolean = settings.reportPlayback) =
        container.scope.launch { container.settingsStore.saveServerPrefs(preferred, report) }

    fun saveSync(sync: SyncSettings = settings.sync, autoReady: Boolean = settings.autoReadyOnLoad) =
        container.scope.launch { container.settingsStore.saveSync(sync, autoReady) }
    fun savePlayback(p: PlaybackPrefs) = container.scope.launch { container.settingsStore.savePlayback(p) }

    val s = settings.sync
    val pb = settings.playback
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Settings", nav, showBack = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp).testTag("settings")) {
            SectionHeader("Media")
            ValueSetting(
                "Media servers",
                servers.joinToString { it.displayName }.ifEmpty { "Add a Jellyfin or Plex server" },
                Modifier.testTag("settings_servers"),
            ) { nav.push(Screen.Servers) }
            ValueSetting("Media folders on this device", if (folders.isEmpty()) "None" else folders.joinToString { it.name }) { nav.push(Screen.LocalFiles) }
            preferredOf(servers, settings.preferredServer)?.takeIf { servers.size > 1 }?.let { preferred ->
                ValueSetting(
                    "Preferred server",
                    "${serverLabel(preferred)} · streams a room's file when several servers have it",
                ) { saveServerPrefs(preferred = nextPreferred(servers, preferred.key)) }
            }
            ToggleSetting(
                "Report progress to your media servers",
                "Send watched state and how far you got to the servers that have the file",
                settings.reportPlayback,
                Modifier.testTag("toggle_report_playback"),
            ) { saveServerPrefs(report = it) }
            ToggleSetting(
                "Add media servers shared by Syncplay hosts",
                "When a YarmiplayServerTV host shares their Jellyfin, sign in to it automatically",
                settings.addSharedServers,
                Modifier.testTag("toggle_add_shared_servers"),
            ) { container.scope.launch { container.settingsStore.saveAddSharedServers(it) } }
            ToggleSetting(
                "Share my files with the room",
                "On YarmiplayServerTV servers, viewers without the playlist's file can stream it from your media folders",
                settings.shareFiles,
                Modifier.testTag("toggle_share_files"),
            ) { container.scope.launch { container.settingsStore.saveShareFiles(it) } }
            FolderSetting(container, "Save relayed files to", settings.downloadDirectory, "Ask each time", "Ask each time", "download_folder") { uri ->
                container.scope.launch { container.settingsStore.saveDownloadDirectory(uri) }
            }
            container.screenshots?.let { screenshots ->
                FolderSetting(
                    container, "Save screenshots to", settings.screenshotDirectory,
                    unset = screenshots.defaultFolder, clear = "Use ${screenshots.defaultFolder}", tag = "screenshot_folder",
                ) { uri ->
                    container.scope.launch { container.settingsStore.saveScreenshotDirectory(uri) }
                }
            }

            SectionHeader("Syncing")
            ValueSetting(
                "When I unpause",
                when (s.unpauseMode) {
                    UnpauseMode.IF_OTHERS_READY -> "Play if everyone is ready"
                    UnpauseMode.IF_ALREADY_READY -> "Set ready first"
                    UnpauseMode.ALWAYS -> "Always play"
                },
            ) { saveSync(s.copy(unpauseMode = UnpauseMode.entries[(s.unpauseMode.ordinal + 1) % UnpauseMode.entries.size])) }
            ToggleSetting("Rewind when I'm ahead", "Seek back when more than ${s.rewindThreshold.toInt()} s ahead of the room", s.rewindOnDesync) {
                saveSync(s.copy(rewindOnDesync = it))
            }
            ToggleSetting("Slow down to catch up", "Play at 0.95× when slightly ahead", s.slowOnDesync) { saveSync(s.copy(slowOnDesync = it)) }
            ToggleSetting("Ready when joining a room", null, s.readyAtStart, Modifier.testTag("toggle_ready_at_start")) { saveSync(s.copy(readyAtStart = it)) }
            ToggleSetting("Ready after loading a playlist item", "Mark yourself ready once the room's file is loaded", settings.autoReadyOnLoad) {
                saveSync(autoReady = it)
            }
            ToggleSetting(
                "Remember room playlists",
                "Put a room's playlist back when you rejoin and it's empty",
                settings.autosavePlaylists,
                Modifier.testTag("toggle_autosave_playlists"),
            ) { container.scope.launch { container.settingsStore.saveAutosavePlaylists(it) } }
            ToggleSetting(
                "Show room chat",
                "Off hides everyone's chat messages in rooms, whatever name they use",
                settings.showRoomChat,
                Modifier.testTag("toggle_show_chat"),
            ) { container.scope.launch { container.settingsStore.saveShowRoomChat(it) } }
            if (container.supportsDeviceAccess) {
                OutlinedTextField(
                    settings.deviceName, { name -> container.scope.launch { container.settingsStore.saveDeviceName(name) } },
                    label = { Text("Device name (shown to YarmiplayServerTV hosts)") }, placeholder = { Text(DevicePlatform.deviceName) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("device_name"),
                )
            }

            SectionHeader("Playback")
            ToggleSetting("Hardware decoding", "MediaCodec with software fallback. Takes effect after restarting the app", pb.hardwareDecoding) {
                savePlayback(pb.copy(hardwareDecoding = it))
            }
            ValueSetting("Seek step", "${pb.seekStepSeconds} s · double-tap the left or right side of the video") {
                val steps = listOf(5, 10, 15, 30)
                savePlayback(pb.copy(seekStepSeconds = steps[(steps.indexOf(pb.seekStepSeconds) + 1) % steps.size]))
            }
            OutlinedTextField(
                pb.audioLanguages, { savePlayback(pb.copy(audioLanguages = it)) },
                label = { Text("Preferred audio languages (restart to apply)") }, placeholder = { Text("jpn,ja,eng") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                pb.subtitleLanguages, { savePlayback(pb.copy(subtitleLanguages = it)) },
                label = { Text("Preferred subtitle languages (restart to apply)") }, placeholder = { Text("eng,en") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            SectionHeader("About")
            Text(
                "YarmiplayTV ${container.appVersion} · Syncplay protocol 1.7 · mpv (libmpv)",
                color = AppColors.TextDim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (container.updates.platform != null) {
                fun saveUpdates(check: Boolean = settings.checkForUpdates, install: Boolean = settings.installUpdatesOnLaunch) =
                    container.scope.launch { container.settingsStore.saveUpdatePrefs(check, install) }
                ToggleSetting(
                    "Check for updates",
                    "Look for a new version on the download page (GitHub) when the app starts",
                    settings.checkForUpdates,
                    Modifier.testTag("toggle_check_updates"),
                ) { saveUpdates(check = it) }
                if (container.updates.installer != null) {
                    ToggleSetting(
                        "Automatic updates",
                        "Download and install a new version before the app opens, then start that version",
                        settings.checkForUpdates && settings.installUpdatesOnLaunch,
                        Modifier.testTag("toggle_install_updates"),
                    ) { saveUpdates(check = settings.checkForUpdates || it, install = it) }
                }
            }
            ValueSetting("Open-source licenses", "mpv, FFmpeg and the other parts this app is built on") { nav.push(Screen.Licenses) }
        }
    }
}

@Composable
private fun ValueSetting(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = AppColors.Accent, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A folder the app saves into, picked with the system's folder picker; [folder] empty means [unset] (asking each
 * time, or a default folder), which the [clear] button goes back to. [tag] names the row and the button in tests.
 */
@Composable
private fun FolderSetting(
    container: AppContainer,
    title: String,
    folder: String,
    unset: String,
    clear: String,
    tag: String,
    save: (String) -> Unit,
) {
    val pick = rememberDirectoryPicker(title, save)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = pick).padding(start = 16.dp, end = 4.dp).testTag("settings_$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (folder.isEmpty()) unset else container.local.folderOf(folder).name,
                color = AppColors.Accent,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (folder.isNotEmpty()) {
            IconButton({ save("") }, Modifier.testTag("clear_$tag")) {
                Icon(Icons.Filled.Close, clear, tint = AppColors.TextDim)
            }
        }
    }
}

@Composable
private fun ToggleSetting(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}
