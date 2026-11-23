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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
    val pickVideo = rememberVideoPicker { uri -> if (inRoom) picked = uri else container.playlist.playLocal(uri, inRoom = false) }
    var storageNote by remember { mutableStateOf<String?>(null) }
    val scanStorage = rememberStorageScan(container) { storageNote = it }
    // The media folder being browsed and the path inside it; none shows the media folders themselves.
    var openUri by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var shareId by rememberSaveable { mutableStateOf<String?>(null) }
    var sharePath by rememberSaveable { mutableStateOf("") }
    var addingShare by rememberSaveable { mutableStateOf(false) }
    val places by container.shares.locations.collectAsStateWithLifecycle()
    val share = places.firstOrNull { it.id == shareId }
    var shareError by remember { mutableStateOf<String?>(null) }
    val shareEntries by produceState(emptyList<NetworkEntry>(), shareId, sharePath) {
        val id = shareId ?: run { value = emptyList(); return@produceState }
        val listed = runCatching { container.shares.list(id, sharePath) }
        shareError = listed.exceptionOrNull()?.message
        value = listed.getOrDefault(emptyList())
    }
    val open = folders.firstOrNull { it.uri == openUri }
    fun up() {
        if (path.isEmpty()) openUri = null else path = path.substringBeforeLast('/', "")
    }
    PlatformBackHandler(enabled = open != null || share != null) {
        when {
            open != null -> up()
            sharePath.isNotEmpty() -> sharePath = sharePath.substringBeforeLast('/', "")
            else -> shareId = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        MobileTopBar(
            title = when {
                open != null -> path.substringAfterLast('/').ifEmpty { open.name }
                share != null -> sharePath.substringAfterLast('/').ifEmpty { share.name }
                else -> "Files on this device"
            },
            nav = nav,
            subtitle = open?.let { listOf("Files on this device", it.name).plus(path.split('/').dropLast(1).filter(String::isNotEmpty)).joinToString(" / ") },
            showBack = open != null || share != null || nav.canGoBack,
            onBack = {
                when {
                    open != null -> up()
                    sharePath.isNotEmpty() -> sharePath = sharePath.substringBeforeLast('/', "")
                    share != null -> shareId = null
                    else -> nav.back()
                }
            },
        ) {
            IconButton({ scope.launch { container.local.refresh() } }) { Icon(Icons.Filled.Refresh, "Rescan") }
        }
        if (indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val listing = remember(files, open, path) { open?.let { LocalBrowse.list(files, it, path) } }
        LazyColumn(Modifier.fillMaxSize().testTag("local_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (share != null) {
                item {
                    Text(
                        listOf(share.protocol.uppercase(), share.host).joinToString(" · ") +
                            shareError?.let { " — $it" }.orEmpty(),
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(shareEntries, key = { "n:" + sharePath + "/" + it.name }) { entry ->
                    val child = if (sharePath.isEmpty()) entry.name else "$sharePath/${entry.name}"
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (entry.directory) sharePath = child
                            else {
                                val uri = NetworkUris.of(share.id, child)
                                if (inRoom) picked = uri else container.playlist.playLocal(uri, inRoom = false)
                            }
                        }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (entry.directory) Icons.Filled.Folder else Icons.Filled.Movie, contentDescription = null, tint = AppColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
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
                        FilledTonalButton(scanStorage.run, Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("scan_storage")) {
                            Text(scanStorage.label)
                        }
                    }
                    storageNote?.let { note ->
                        Text(
                            note,
                            color = AppColors.TextDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("storage_note"),
                        )
                    }
                    Text(
                        "Media folders work like Syncplay's media directories: when the room's playlist moves to a file, " +
                            "YarmiplayTV looks for it here first (subfolders included), then on Jellyfin and Plex.",
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item { SectionHeader("FTP and SMB", Modifier.padding(top = 8.dp)) }
                items(places, key = { "s:" + it.id }) { place ->
                    Row(
                        Modifier.fillMaxWidth().clickable { shareId = place.id; sharePath = place.startPath; shareError = null }
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${place.protocol.uppercase()} · ${place.host}", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ scope.launch { container.shares.remove(place.id) } }) {
                            Icon(Icons.Filled.Delete, "Remove", tint = AppColors.TextDim)
                        }
                    }
                }
                item {
                    TextButton({ addingShare = true }, Modifier.padding(horizontal = 8.dp)) { Text("Add FTP or SMB") }
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

    if (addingShare) AddShareDialog(onDismiss = { addingShare = false }) { draft ->
        scope.launch {
            container.shares.add(draft)
            addingShare = false
        }
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

private fun videoCount(n: Int) = if (n == 1) "1 video" else "$n videos"

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "unknown size"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun AddShareDialog(onDismiss: () -> Unit, onSave: (com.yarmiplaytv.network.NetworkLocation) -> Unit) {
    var protocol by remember { mutableStateOf(NetworkLocations.FTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var shareName by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add FTP or SMB") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton({ protocol = NetworkLocations.FTP }) { Text(if (protocol == NetworkLocations.FTP) "FTP" else "Use FTP") }
                    TextButton({ protocol = NetworkLocations.SMB }) { Text(if (protocol == NetworkLocations.SMB) "SMB" else "Use SMB") }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Server") }, singleLine = true)
                OutlinedTextField(
                    port,
                    { port = it },
                    label = { Text(if (protocol == NetworkLocations.SMB) "Port (445)" else "Port (21)") },
                    singleLine = true,
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true)
                if (protocol == NetworkLocations.SMB) {
                    OutlinedTextField(shareName, { shareName = it }, label = { Text("Share name") }, singleLine = true)
                }
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder (optional)") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "The password stays in this app's settings and is not put in the playback address.",
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton({
                when (
                    val draft = NetworkLocations.draft(
                        UUID.randomUUID().toString(), protocol, host, port, username, password, shareName, label, folder,
                    )
                ) {
                    is NetworkDraft.Ok -> onSave(draft.location)
                    is NetworkDraft.Invalid -> error = draft.reason
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
