package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.local.LocalBrowse
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalMatcher
import com.yarmiplaytv.network.NetworkDraft
import com.yarmiplaytv.network.NetworkEntry
import com.yarmiplaytv.network.NetworkLocations
import com.yarmiplaytv.network.NetworkUris
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.ManagedRooms
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

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
    val scanStorage = rememberStorageScanner(container)
    val pickVideo = rememberVideoPicker { uri -> if (inRoom) picked = uri else container.playlist.playLocal(uri, inRoom = false) }
    val places by container.network.locations.collectAsStateWithLifecycle()
    // The media folder being browsed and the path inside it; none shows the media folders themselves.
    var openUri by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var networkId by rememberSaveable { mutableStateOf<String?>(null) }
    var networkPath by rememberSaveable { mutableStateOf("") }
    var addingNetwork by rememberSaveable { mutableStateOf(false) }
    val open = folders.firstOrNull { it.uri == openUri }
    val place = places.firstOrNull { it.id == networkId }
    fun up() {
        when {
            place != null && networkPath.isNotEmpty() -> networkPath = networkPath.substringBeforeLast('/', "")
            place != null -> networkId = null
            path.isNotEmpty() -> path = path.substringBeforeLast('/', "")
            else -> openUri = null
        }
    }
    PlatformBackHandler(enabled = open != null || place != null) { up() }

    Column(Modifier.fillMaxSize()) {
        MobileTopBar(
            title = when {
                place != null -> networkPath.substringAfterLast('/').ifEmpty { place.name }
                open != null -> path.substringAfterLast('/').ifEmpty { open.name }
                else -> "Files on this device"
            },
            nav = nav,
            subtitle = open?.let { listOf("Files on this device", it.name).plus(path.split('/').dropLast(1).filter(String::isNotEmpty)).joinToString(" / ") },
            showBack = open != null || place != null || nav.canGoBack,
            onBack = { if (open != null || place != null) up() else nav.back() },
        ) {
            IconButton({ scope.launch { container.local.refresh() } }) { Icon(Icons.Filled.Refresh, "Rescan") }
        }
        if (indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val listing = remember(files, open, path) { open?.let { LocalBrowse.list(files, it, path) } }
        val networkListing by produceState<Result<List<NetworkEntry>>?>(null, place?.id, networkPath) {
            val current = place ?: run { value = null; return@produceState }
            value = runCatching { container.network.list(current.id, networkPath) }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("local_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (place != null) {
                val entries = networkListing?.getOrNull().orEmpty()
                val failure = networkListing?.exceptionOrNull()?.message
                if (failure != null) {
                    item { Text(failure, color = AppColors.NotReady, modifier = Modifier.padding(16.dp)) }
                }
                items(entries.filter { it.directory }, key = { "nd:" + networkPath + "/" + it.name }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            NetworkLocations.childPath(networkPath, entry.name)?.let { networkPath = it }
                        }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(entries.filter { !it.directory && LocalMatcher.isMedia(it.name, null) }, key = { "nv:" + networkPath + "/" + it.name }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val child = NetworkLocations.childPath(networkPath, entry.name) ?: return@clickable
                            val uri = NetworkUris.of(place.id, child)
                            if (inRoom) picked = uri else container.playlist.playLocal(uri, inRoom = false)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null, tint = AppColors.TextDim)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(entry.size), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (networkListing?.isSuccess == true && entries.none { it.directory || LocalMatcher.isMedia(it.name, null) }) {
                    item { EmptyMessage("No videos in this folder") }
                }
            } else if (open == null || listing == null) {
                item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(pickVideo, Modifier.weight(1f).testTag("open_video")) {
                            Icon(Icons.Filled.VideoFile, contentDescription = null); Text(" Open a video")
                        }
                        FilledTonalButton(pickFolder, Modifier.weight(1f).testTag("add_folder")) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = null); Text(" Add folder")
                        }
                    }
                    if (scanStorage != null) {
                        OutlinedButton(scanStorage, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("scan_storage")) {
                            Text("Scan USB and internal storage")
                        }
                    }
                    OutlinedButton({ addingNetwork = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).testTag("add_network")) {
                        Text("Add FTP or SMB")
                    }
                    Text(
                        "Media folders work like Syncplay's media directories: when the room's playlist moves to a file, " +
                            "YarmiplayTV looks for it here first (subfolders included), then on Jellyfin and Plex. " +
                            "FTP and SMB open videos on another computer on your network.",
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                    )
                }
                if (places.isNotEmpty()) {
                    item { SectionHeader("Network", Modifier.padding(top = 8.dp)) }
                    items(places, key = { "n:" + it.id }) { location ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                networkId = location.id
                                networkPath = location.startPath
                            }.padding(start = 16.dp, end = 4.dp).testTag("network_place"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(location.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    location.protocol.uppercase() + " · " + location.host,
                                    color = AppColors.TextDim,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton({ scope.launch { container.network.remove(location.id) } }) {
                                Icon(Icons.Filled.Delete, "Remove network place", tint = AppColors.TextDim)
                            }
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    item { SectionHeader("Media folders", Modifier.padding(top = 8.dp)) }
                    items(folders, key = { "f:" + it.uri }) { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openUri = folder.uri; path = "" }.padding(start = 16.dp, end = 4.dp).testTag("local_folder"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                            Spacer(Modifier.width(12.dp))
                            Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(videoCount(LocalBrowse.inFolder(files, folder).size), color = AppColors.TextDim)
                            IconButton({ container.local.removeFolder(folder) }) { Icon(Icons.Filled.Delete, "Remove folder", tint = AppColors.TextDim) }
                        }
                    }
                }
            } else {
                items(listing.folders, key = { "d:" + it.path }) { sub ->
                    Row(
                        Modifier.fillMaxWidth().clickable { path = sub.path }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("local_subfolder"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(sub.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(videoCount(sub.videos), color = AppColors.TextDim)
                    }
                }
                items(listing.files, key = { "v:" + it.uri }) { file ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (inRoom) picked = file.uri else container.playlist.playLocal(file.uri, inRoom = false)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VideoThumbnail(container, file, Modifier.width(96.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(file.sizeBytes), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (listing.folders.isEmpty() && listing.files.isEmpty() && !indexing) {
                    item { EmptyMessage("No videos in this folder") }
                }
            }
        }
    }

    if (addingNetwork) {
        AddNetworkDialog(
            onDismiss = { addingNetwork = false },
            onSave = { location ->
                scope.launch {
                    container.network.add(location)
                    addingNetwork = false
                }
            },
        )
    }

    picked?.let { uri ->
        ModalBottomSheet(onDismissRequest = { picked = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = AppColors.Surface, contentColor = AppColors.OnSurface, modifier = Modifier.exposeTestTags()) {
            SurfaceInk {
            val file = files.firstOrNull { it.uri == uri }
            LocalFileActions(container, uri, file, file?.name ?: uriFileName(uri) ?: "", ManagedRooms.label(room.room)) { picked = null }
            }
        }
    }
}

@Composable
private fun LocalFileActions(container: AppContainer, uri: String, file: LocalFile?, name: String, roomName: String, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Files opened from outside the media folders aren't in the index, so they have no preview.
            if (file != null) {
                VideoThumbnail(container, file, Modifier.width(160.dp))
                Spacer(Modifier.width(14.dp))
            }
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Button({ container.playlist.playLocal(uri, inRoom = true); onDone() }, Modifier.fillMaxWidth().testTag("local_play_room")) {
            Icon(Icons.Filled.Groups, contentDescription = null); Text("  Play for everyone in '$roomName'")
        }
        FilledTonalButton({ container.playlist.addLocalToRoomPlaylist(uri); onDone() }, Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null); Text("  Add to room playlist")
        }
        OutlinedButton({ container.playlist.playLocal(uri, inRoom = false); onDone() }, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text("  Play only on this device")
        }
        Text("Others need the same file (same name) in their Syncplay media folders, Jellyfin or Plex.", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** The video's preview frame in a 16:9 tile, over a film icon that shows until it's ready or when there can't be one. */
@Composable
private fun VideoThumbnail(container: AppContainer, file: LocalFile, modifier: Modifier = Modifier) {
    val thumbnails = container.thumbnails
    val image by produceState<File?>(null, thumbnails, file) { value = thumbnails?.thumbnail(file) }
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Movie, contentDescription = null, tint = AppColors.TextDim, modifier = Modifier.size(24.dp))
        image?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("local_thumbnail"))
        }
    }
}

@Composable
private fun AddNetworkDialog(onDismiss: () -> Unit, onSave: (com.yarmiplaytv.network.NetworkLocation) -> Unit) {
    var protocol by remember { mutableStateOf(NetworkLocations.FTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var startPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add FTP or SMB") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (protocol == NetworkLocations.FTP) {
                        Button({ protocol = NetworkLocations.FTP }, Modifier.weight(1f)) { Text("FTP") }
                    } else {
                        OutlinedButton({ protocol = NetworkLocations.FTP }, Modifier.weight(1f)) { Text("FTP") }
                    }
                    if (protocol == NetworkLocations.SMB) {
                        Button({ protocol = NetworkLocations.SMB }, Modifier.weight(1f)) { Text("SMB") }
                    } else {
                        OutlinedButton({ protocol = NetworkLocations.SMB }, Modifier.weight(1f)) { Text("SMB") }
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it },
                    label = { Text(if (protocol == NetworkLocations.SMB) "Port (445)" else "Port (21)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (protocol == NetworkLocations.SMB) {
                    OutlinedTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startPath, { startPath = it }, label = { Text("Folder (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = AppColors.NotReady, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({
                when (val draft = NetworkLocations.draft(UUID.randomUUID().toString(), protocol, host, port, username, password, share, name, startPath)) {
                    is NetworkDraft.Ok -> onSave(draft.location)
                    is NetworkDraft.Invalid -> error = draft.reason
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun videoCount(n: Int) = if (n == 1) "1 video" else "$n videos"

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "unknown size"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
