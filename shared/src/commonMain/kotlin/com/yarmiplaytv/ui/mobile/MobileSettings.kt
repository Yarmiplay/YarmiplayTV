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
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.UnpauseMode
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun MobileSettingsScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    val source by container.mediaSource.collectAsStateWithLifecycle()

    fun saveSync(sync: SyncSettings = settings.sync, autoReady: Boolean = settings.autoReadyOnLoad) =
        container.scope.launch { container.settingsStore.saveSync(sync, autoReady) }
    fun savePlayback(p: PlaybackPrefs) = container.scope.launch { container.settingsStore.savePlayback(p) }

    val s = settings.sync
    val pb = settings.playback
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Settings", nav, showBack = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp).testTag("settings")) {
            SectionHeader("Media")
            ValueSetting("Jellyfin", source?.displayName ?: "Not connected") { nav.push(Screen.JellyfinLogin) }
            ValueSetting("Media folders on this device", if (folders.isEmpty()) "None" else folders.joinToString { it.name }) { nav.push(Screen.LocalFiles) }

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
                        "Install updates on launch",
                        "Download new versions when the app starts and install them when you close it",
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
private fun ValueSetting(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = AppColors.Accent, style = MaterialTheme.typography.bodyMedium)
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
