package com.yarmiplaytv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.BuildConfig
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.UnpauseMode
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.ToggleRow
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.components.ValueRow
import com.yarmiplaytv.ui.theme.AppColors
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
        ToggleRow("Ready after loading a playlist item", settings.autoReadyOnLoad, { saveSync(autoReady = !settings.autoReadyOnLoad) }, subtitle = "Mark yourself ready once the room's file is loaded from Jellyfin")
        ToggleRow(
            "Remember room playlists",
            settings.autosavePlaylists,
            { container.scope.launch { container.settingsStore.saveAutosavePlaylists(!settings.autosavePlaylists) } },
            subtitle = "Put a room's playlist back when you rejoin and it's empty",
        )

        SectionTitle("Playback")
        ToggleRow("Hardware decoding", pb.hardwareDecoding, { savePlayback(pb.copy(hardwareDecoding = !pb.hardwareDecoding)) }, subtitle = "MediaCodec with software fallback. Takes effect after restarting the app")
        ValueRow("Seek step", "${pb.seekStepSeconds} s", {
            val steps = listOf(5, 10, 15, 30)
            savePlayback(pb.copy(seekStepSeconds = steps[(steps.indexOf(pb.seekStepSeconds) + 1) % steps.size]))
        }, subtitle = "D-pad left/right while the controls are hidden")
        TvTextField(pb.audioLanguages, { savePlayback(pb.copy(audioLanguages = it)) }, "Preferred audio languages (restart to apply)", placeholder = "jpn,ja,eng")
        TvTextField(pb.subtitleLanguages, { savePlayback(pb.copy(subtitleLanguages = it)) }, "Preferred subtitle languages (restart to apply)", placeholder = "eng,en")

        SectionTitle("About")
        Text("YarmiplayTV ${BuildConfig.VERSION_NAME} · Syncplay protocol 1.7 · mpv (libmpv)", color = AppColors.TextDim)
    }
}
