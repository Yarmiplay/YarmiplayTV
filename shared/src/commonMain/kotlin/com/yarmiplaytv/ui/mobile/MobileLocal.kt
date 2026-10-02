package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileLocalFilesScreen(container: AppContainer, nav: Navigator) {
    val folders by container.local.folders.collectAsStateWithLifecycle()
    val files by container.local.files.collectAsStateWithLifecycle()
    val indexing by container.local.indexing.collectAsStateWithLifecycle()
    val room by container.sync.room.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberFolderPicker(container)
    val pickVideo = rememberVideoPicker { uri -> if (inRoom) picked = uri else container.playlist.playLocal(uri, inRoom = false) }

    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Files on this device", nav) {
            IconButton({ scope.launch { container.local.refresh() } }) { Icon(Icons.Filled.Refresh, "Rescan") }
        }
        if (indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize().testTag("local_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(pickVideo, Modifier.weight(1f).testTag("open_video")) {
                        Icon(Icons.Filled.VideoFile, contentDescription = null); Text(" Open a video")
                    }
                    FilledTonalButton(pickFolder, Modifier.weight(1f).testTag("add_folder")) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null); Text(" Add folder")
                    }
                }
                Text(
                    "Media folders work like Syncplay's media directories: when the room's playlist moves to a file, " +
                        "SyncplayTV looks for it here first, then in Jellyfin.",
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (folders.isNotEmpty()) {
                item { SectionHeader("Media folders", Modifier.padding(top = 8.dp)) }
                items(folders, key = { "f:" + it.uri }) { folder ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${files.count { it.folder == folder.name }}", color = AppColors.TextDim)
                        IconButton({ container.local.removeFolder(folder) }) { Icon(Icons.Filled.Delete, "Remove folder", tint = AppColors.TextDim) }
                    }
                }
            }
            if (files.isNotEmpty()) {
                item { SectionHeader("Videos", Modifier.padding(top = 8.dp)) }
                items(files, key = { "v:" + it.uri }) { file ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (inRoom) picked = file.uri else container.playlist.playLocal(file.uri, inRoom = false)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null, tint = AppColors.TextDim)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${file.folder} · ${formatSize(file.sizeBytes)}", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (folders.isNotEmpty() && !indexing) {
                item { EmptyMessage("No videos found in these folders") }
            }
        }
    }

    picked?.let { uri ->
        ModalBottomSheet(onDismissRequest = { picked = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = AppColors.Surface, modifier = Modifier.exposeTestTags()) {
            val name = files.firstOrNull { it.uri == uri }?.name ?: uriFileName(uri) ?: ""
            LocalFileActions(container, uri, name, room.room) { picked = null }
        }
    }
}

@Composable
private fun LocalFileActions(container: AppContainer, uri: String, name: String, roomName: String, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button({ container.playlist.playLocal(uri, inRoom = true); onDone() }, Modifier.fillMaxWidth().testTag("local_play_room")) {
            Icon(Icons.Filled.Groups, contentDescription = null); Text("  Play for everyone in '$roomName'")
        }
        FilledTonalButton({ container.playlist.addLocalToRoomPlaylist(uri); onDone() }, Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null); Text("  Add to room playlist")
        }
        OutlinedButton({ container.playlist.playLocal(uri, inRoom = false); onDone() }, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text("  Play only on this device")
        }
        Text("Others need the same file (same name) in their Syncplay media folders or Jellyfin.", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "unknown size"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
