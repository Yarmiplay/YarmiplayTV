package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.data.PlaybackPrefs
import com.yarmiplaytv.syncplay.SyncSettings
import com.yarmiplaytv.syncplay.UnpauseMode
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
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
import com.yarmiplaytv.ui.shared.aboutLinks
import com.yarmiplaytv.ui.shared.nextPreferred
import com.yarmiplaytv.ui.shared.preferredOf
import com.yarmiplaytv.ui.shared.rememberSavedText
import com.yarmiplaytv.ui.shared.serverLabel
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.AppTheme
import com.yarmiplaytv.ui.theme.Palette
import com.yarmiplaytv.ui.theme.THEME_NOTE
import kotlinx.coroutines.launch

/**
 * The groups of settings: on phones a list of these, each opening its own page; on wide windows the list beside the
 * chosen one's settings. [summary] says what's in it, on the phone's list.
 */
private enum class SettingsCategory(val title: String, val icon: ImageVector, val summary: String) {
    MEDIA("Media", Icons.Filled.VideoLibrary, "Media servers, media folders, sharing your files, where files are saved"),
    MAL("MyAnimeList", Icons.Filled.Tv, "Your account and list updates"),
    SYNCING("Syncing", Icons.Filled.Sync, "Unpausing, catching up, readiness, playlists, chat, your device's name"),
    PLAYBACK("Playback", Icons.Filled.PlayCircle, "Decoding, picture-in-picture, openings and endings, seeking, languages"),
    APPEARANCE("Appearance", Icons.Filled.Palette, "Theme: dark, noire, monochrome, light, or as your device is set"),
    UPDATES("Updates", Icons.Filled.SystemUpdate, "Checking for and installing new versions"),
    ABOUT("About", Icons.Filled.Info, "Version, what's new, the manual, privacy, licenses"),
}

/** Windows at least this wide show the categories beside the settings. */
private val TWO_PANES_WIDTH = 840.dp

@Composable
fun MobileSettingsScreen(container: AppContainer, nav: Navigator) {
    val categories = remember(container) {
        SettingsCategory.entries.filter { it != SettingsCategory.MAL || container.mal.client.configured }
    }
    var selected by rememberSaveable { mutableStateOf(SettingsCategory.MEDIA.name) }
    /** Phones: the category whose page is open, or null for the list of categories. */
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANES_WIDTH
        val open = categories.firstOrNull { it.name == page }?.takeIf { !wide }
        PlatformBackHandler(enabled = open != null) { page = null }
        Column(Modifier.fillMaxSize()) {
            if (open != null) MobileTopBar(open.title, nav, showBack = true, onBack = { page = null })
            else MobileTopBar(
                "Settings",
                nav,
                showBack = false,
                actions = {
                    IconButton(onClick = { container.exitApp() }, modifier = Modifier.testTag("settings_exit_app")) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Exit YarmiplayTV", tint = AppColors.TextDim)
                    }
                },
            )
            if (wide) {
                val category = categories.firstOrNull { it.name == selected } ?: categories.first()
                Row(Modifier.fillMaxSize().testTag("settings_two_panes")) {
                    Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        categories.forEach { c ->
                            SideNavItem(c.title, c.icon, c == category, { selected = c.name }, Modifier.testTag("settings_category_${c.name.lowercase()}"))
                        }
                        Spacer(Modifier.height(16.dp))
                        ExitAppRow(container)
                    }
                    VerticalDivider(color = AppColors.SurfaceHigh)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        key(category) {
                            Column(
                                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                                    .padding(top = 12.dp, bottom = 24.dp).testTag("settings"),
                            ) {
                                Text(category.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                CategorySettings(category, container, nav)
                            }
                        }
                    }
                }
            } else if (open != null) {
                key(open) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(top = 4.dp, bottom = 24.dp).testTag("settings")) {
                        CategorySettings(open, container, nav)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp).testTag("settings")) {
                    categories.forEach { c -> CategoryRow(c, { page = c.name }, Modifier.testTag("settings_category_${c.name.lowercase()}")) }
                    Spacer(Modifier.height(16.dp))
                    ExitAppRow(container)
                }
            }
        }
    }
}

/** A category on the phone's list: its icon, name and what's in it; opens its page. */
@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(category.icon, contentDescription = null, tint = AppColors.Accent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(category.summary, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppColors.TextDim)
        }
    }
}

@Composable
private fun ExitAppRow(container: AppContainer, modifier: Modifier = Modifier) {
    Surface(
        onClick = { container.exitApp() },
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().testTag("settings_exit_app_button"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = AppColors.NotReady)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Exit YarmiplayTV", style = MaterialTheme.typography.titleMedium, color = AppColors.NotReady)
                Text("Stop background playback and close app", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ColumnScope.CategorySettings(category: SettingsCategory, container: AppContainer, nav: Navigator) {
    when (category) {
        SettingsCategory.MEDIA -> MediaSettings(container, nav)
        SettingsCategory.MAL -> MalSettings(container, nav)
        SettingsCategory.SYNCING -> SyncingSettings(container)
        SettingsCategory.PLAYBACK -> PlaybackSettings(container)
        SettingsCategory.APPEARANCE -> AppearanceSettings(container)
        SettingsCategory.UPDATES -> UpdatesSettings(container, nav)
        SettingsCategory.ABOUT -> AboutSettings(container, nav)
    }
}

@Composable
private fun MediaSettings(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val folders by container.local.folders.collectAsStateWithLifecycle()
    val servers by container.servers.collectAsStateWithLifecycle()
    fun saveServerPrefs(preferred: String = settings.preferredServer, report: Boolean = settings.reportPlayback) =
        container.scope.launch { container.settingsStore.saveServerPrefs(preferred, report) }
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
    SettingsToggle(
        "Report progress to your media servers",
        "Send watched state and how far you got to the servers that have the file",
        settings.reportPlayback,
        Modifier.testTag("toggle_report_playback"),
    ) { saveServerPrefs(report = it) }
    SettingsToggle(
        "Add media servers shared by Syncplay hosts",
        "When a YarmiplayServerTV host shares their Jellyfin, sign in to it automatically",
        settings.addSharedServers,
        Modifier.testTag("toggle_add_shared_servers"),
    ) { container.scope.launch { container.settingsStore.saveAddSharedServers(it) } }
    SettingsToggle(
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
}

/** One row: everything else about MyAnimeList is on its own screen. */
@Composable
private fun MalSettings(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val account = settings.mal
    if (account == null) {
        ValueSetting("MyAnimeList account", "Sign in to update your list as you watch", Modifier.testTag("settings_mal_sign_in")) { nav.push(Screen.MalLogin) }
    } else {
        ValueSetting(
            "MyAnimeList",
            "Signed in as ${account.userName} · your list, list updates and matched shows",
            Modifier.testTag("settings_mal_account"),
        ) { nav.push(Screen.Mal) }
    }
    Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SyncingSettings(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    fun saveSync(sync: SyncSettings = settings.sync, autoReady: Boolean = settings.autoReadyOnLoad) =
        container.scope.launch { container.settingsStore.saveSync(sync, autoReady) }
    val s = settings.sync
    ValueSetting(
        "When I unpause",
        when (s.unpauseMode) {
            UnpauseMode.IF_OTHERS_READY -> "Play if everyone is ready"
            UnpauseMode.IF_ALREADY_READY -> "Set ready first"
            UnpauseMode.ALWAYS -> "Always play"
        },
    ) { saveSync(s.copy(unpauseMode = UnpauseMode.entries[(s.unpauseMode.ordinal + 1) % UnpauseMode.entries.size])) }
    SettingsToggle("Rewind when I'm ahead", "Seek back when more than ${s.rewindThreshold.toInt()} s ahead of the room", s.rewindOnDesync) {
        saveSync(s.copy(rewindOnDesync = it))
    }
    SettingsToggle("Slow down to catch up", "Play at 0.95× when slightly ahead", s.slowOnDesync) { saveSync(s.copy(slowOnDesync = it)) }
    SettingsToggle("Ready when joining a room", null, s.readyAtStart, Modifier.testTag("toggle_ready_at_start")) { saveSync(s.copy(readyAtStart = it)) }
    SettingsToggle("Ready after loading a playlist item", "Mark yourself ready once the room's file is loaded", settings.autoReadyOnLoad) {
        saveSync(autoReady = it)
    }
    SettingsToggle(
        "Remember room playlists",
        "Put a room's playlist back when you rejoin and it's empty",
        settings.autosavePlaylists,
        Modifier.testTag("toggle_autosave_playlists"),
    ) { container.scope.launch { container.settingsStore.saveAutosavePlaylists(it) } }
    SettingsToggle(
        "Show room chat",
        "Off hides everyone's chat messages in rooms, whatever name they use",
        settings.showRoomChat,
        Modifier.testTag("toggle_show_chat"),
    ) { container.scope.launch { container.settingsStore.saveShowRoomChat(it) } }
    if (settings.showRoomChat) {
        SettingsToggle(
            "Chat on the video",
            CHAT_ON_VIDEO_NOTE,
            settings.chatOnVideo,
            Modifier.testTag("toggle_chat_on_video"),
        ) { container.scope.launch { container.settingsStore.saveChatOnVideo(it) } }
    }
    SettingsToggle(
        "Voice chat",
        "Talk in rooms on a YarmiplayServerTV server that has voice on; off hides the controls and disconnects",
        settings.voiceChat,
        Modifier.testTag("toggle_voice_chat"),
    ) { container.scope.launch { container.settingsStore.saveVoiceChat(it) } }
    if (container.supportsDeviceAccess) {
        SettingTextField(
            settings.deviceName,
            { name -> container.scope.launch { container.settingsStore.saveDeviceName(name) } },
            "Device name",
            DevicePlatform.deviceName,
            "Shown to YarmiplayServerTV hosts when you ask for access",
            Modifier.testTag("device_name"),
        )
    }
}

/** The theme: each with a little picture of its colours, the chosen one marked. */
@Composable
private fun AppearanceSettings(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val chosen = AppTheme.of(settings.theme)
    val systemDark = isSystemInDarkTheme()
    AppTheme.entries.forEach { theme ->
        Row(
            Modifier.fillMaxWidth().selectable(theme == chosen, role = Role.RadioButton) {
                container.scope.launch { container.settingsStore.saveTheme(theme.key) }
            }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("theme_${theme.key}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemeSwatch(theme.palette(systemDark))
            Column(Modifier.weight(1f)) {
                Text(theme.label, style = MaterialTheme.typography.bodyLarge)
                Text(theme.description, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(theme == chosen, onClick = null)
        }
    }
    Text(THEME_NOTE, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A theme in small: its background, a card, a line of text and its accent. */
@Composable
private fun ThemeSwatch(palette: Palette) {
    Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(palette.background).border(1.dp, AppColors.TextDim.copy(alpha = 0.4f), RoundedCornerShape(8.dp))) {
        Box(Modifier.padding(start = 6.dp, top = 6.dp).size(30.dp, 28.dp).clip(RoundedCornerShape(4.dp)).background(palette.surface)) {
            Box(Modifier.padding(start = 4.dp, top = 6.dp).size(20.dp, 3.dp).background(palette.text))
            Box(Modifier.padding(start = 4.dp, top = 13.dp).size(14.dp, 3.dp).background(palette.textDim))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(12.dp).clip(RoundedCornerShape(6.dp)).background(palette.accent))
    }
}

@Composable
private fun PlaybackSettings(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    // The latest saved prefs, so a field saving while another setting changes doesn't undo it.
    fun savePlayback(change: (PlaybackPrefs) -> PlaybackPrefs) =
        container.scope.launch { container.settingsStore.savePlayback(change(container.settings.value.playback)) }
    val pb = settings.playback
    SettingsToggle("Hardware decoding", "MediaCodec with software fallback. Takes effect after restarting the app", pb.hardwareDecoding) { on ->
        savePlayback { it.copy(hardwareDecoding = on) }
    }
    if (autoPictureInPictureSetting) {
        SettingsToggle(
            autoPictureInPictureTitle,
            autoPictureInPictureSubtitle,
            pb.pictureInPicture,
            Modifier.testTag("toggle_pip"),
        ) { on -> savePlayback { it.copy(pictureInPicture = on) } }
    }
    SettingsToggle(
        "Stay connected when minimized",
        "Keep room sync and playback active in background with playback controls in notifications",
        pb.stayConnectedMinimized,
        Modifier.testTag("toggle_stay_connected"),
    ) { on -> savePlayback { it.copy(stayConnectedMinimized = on) } }
    SettingsToggle(SKIP_ALONE_TITLE, SKIP_ALONE_SUBTITLE, pb.autoSkipAlone, Modifier.testTag("toggle_auto_skip")) { on ->
        savePlayback { it.copy(autoSkipAlone = on) }
    }
    SettingsToggle(SKIP_BUTTON_TITLE, SKIP_BUTTON_SUBTITLE, pb.showSkipButton, Modifier.testTag("toggle_skip_button")) { on ->
        savePlayback { it.copy(showSkipButton = on) }
    }
    SettingsToggle(DETECT_SKIPS_TITLE, DETECT_SKIPS_SUBTITLE, pb.detectSkips, Modifier.testTag("toggle_detect_skips")) { on ->
        savePlayback { it.copy(detectSkips = on) }
    }
    // A computer can't tell a metered network: there, server episodes are always listened to.
    if (pb.detectSkips && container.deviceKind != DeviceKind.DESKTOP) {
        SettingsToggle(
            DETECT_ANY_NETWORK_TITLE, DETECT_ANY_NETWORK_SUBTITLE, pb.detectOnAnyNetwork, Modifier.testTag("toggle_detect_any_network"),
        ) { on -> savePlayback { it.copy(detectOnAnyNetwork = on) } }
    }
    ValueSetting("Seek step", "${pb.seekStepSeconds} s · double-tap the left or right side of the video") {
        val steps = listOf(5, 10, 15, 30)
        savePlayback { it.copy(seekStepSeconds = steps[(steps.indexOf(it.seekStepSeconds) + 1) % steps.size]) }
    }
    SettingTextField(
        pb.audioLanguages, { text -> savePlayback { it.copy(audioLanguages = text) } },
        "Preferred audio languages", "jpn,ja,eng", "Language codes in order, separated by commas. Applies after restarting the app",
        Modifier.testTag("audio_languages"),
    )
    SettingTextField(
        pb.subtitleLanguages, { text -> savePlayback { it.copy(subtitleLanguages = text) } },
        "Preferred subtitle languages", "eng,en", "Applies after restarting the app",
        Modifier.testTag("subtitle_languages"),
    )
}

@Composable
private fun AboutSettings(container: AppContainer, nav: Navigator) {
    val uriHandler = LocalUriHandler.current
    Text("YarmiplayTV ${container.appVersion}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text(
        "Syncplay protocol 1.7 · mpv (libmpv) · free and open source",
        color = AppColors.TextDim,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(Modifier.size(4.dp))
    ValueSetting("What's new", "This version's changes", Modifier.testTag("about_whats_new")) { nav.push(Screen.WhatsNew) }
    aboutLinks.forEach { link ->
        LinkSetting(link.title, link.subtitle, Modifier.testTag(link.tag)) { uriHandler.openUri(link.url) }
    }
    ValueSetting("Open-source licenses", "mpv, FFmpeg and the other parts this app is built on") { nav.push(Screen.Licenses) }
    ValueSetting("Exit YarmiplayTV", "Close the app and stop background playback", Modifier.testTag("about_exit_app")) { container.exitApp() }
}

/**
 * A settings text box. It keeps what's typed and saves it once typing stops, so the caret stays after the
 * letter just typed (see [rememberSavedText]).
 */
@Composable
private fun SettingTextField(
    value: String,
    onSave: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String?,
    modifier: Modifier = Modifier,
) {
    var text by rememberSavedText(value, onSave)
    OutlinedTextField(
        text, { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ValueSetting(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = AppColors.Accent, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A row that opens a web page. */
@Composable
private fun LinkSetting(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = AppColors.TextDim, modifier = Modifier.size(18.dp))
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
internal fun SettingsToggle(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}
