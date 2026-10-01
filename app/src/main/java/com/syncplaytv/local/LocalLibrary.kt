package com.syncplaytv.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.syncplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LocalFolder(val uri: String, val name: String)

/**
 * The device's media folders (picked with the system folder picker, access persisted) and an index
 * of the videos inside them, used to resolve shared-playlist entries like desktop Syncplay's media
 * directories, plus helpers for single files opened directly.
 */
class LocalLibrary(
    context: Context,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    initialFolders: List<String>,
) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    private val _folders = MutableStateFlow(initialFolders.map(::folderOf))
    val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()

    private val _files = MutableStateFlow<List<LocalFile>>(emptyList())
    val files: StateFlow<List<LocalFile>> = _files.asStateFlow()

    private val _indexing = MutableStateFlow(false)
    val indexing: StateFlow<Boolean> = _indexing.asStateFlow()

    private val indexLock = Mutex()
    private var lastIndexAt = 0L

    val hasFolders: Boolean get() = _folders.value.isNotEmpty()

    init {
        if (initialFolders.isNotEmpty()) scope.launch { refresh() }
    }

    fun addFolder(treeUri: Uri) {
        runCatching { resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "No persistable permission for $treeUri", it) }
        if (_folders.value.any { it.uri == treeUri.toString() }) return
        _folders.value = _folders.value + folderOf(treeUri.toString())
        persistAndRefresh()
    }

    fun removeFolder(folder: LocalFolder) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        _folders.value = _folders.value.filterNot { it.uri == folder.uri }
        persistAndRefresh()
    }

    private fun persistAndRefresh() {
        scope.launch {
            settingsStore.saveLocalFolders(_folders.value.map { it.uri })
            refresh()
        }
    }

    suspend fun refresh() = indexLock.withLock {
        _indexing.value = true
        try {
            val found = withContext(Dispatchers.IO) {
                _folders.value.flatMap { folder ->
                    runCatching { scanTree(Uri.parse(folder.uri), folder.name) }
                        .onFailure { Log.w(TAG, "Cannot read ${folder.name}", it) }
                        .getOrDefault(emptyList())
                }
            }
            _files.value = found.sortedBy { it.name.lowercase() }
            lastIndexAt = System.currentTimeMillis()
            Log.i(TAG, "Indexed ${found.size} videos in ${_folders.value.size} folder(s)")
        } finally {
            _indexing.value = false
        }
    }

    /** Looks up a playlist filename; re-indexes once if it isn't there (files may have been added). */
    suspend fun resolve(fileName: String): LocalMatcher.Match? {
        if (!hasFolders) return null
        LocalMatcher.find(_files.value, fileName)?.let { return it }
        if (System.currentTimeMillis() - lastIndexAt < MIN_REFRESH_MS) return null
        refresh()
        return LocalMatcher.find(_files.value, fileName)
    }

    /** Name and size of a single content:// or file:// URI. */
    suspend fun describe(uri: Uri): LocalFile = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = 0L
        if (uri.scheme == "content") {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it) }?.let { f -> name = f.name; size = f.length() }
        }
        LocalFile(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString(), size, uri.toString())
    }

    private fun scanTree(treeUri: Uri, folderName: String): List<LocalFile> {
        val out = ArrayList<LocalFile>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending.add(id)
                    } else if (LocalMatcher.isVideo(name, mime)) {
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        out += LocalFile(name, size, DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), folderName)
                    }
                }
            }
        }
        return out
    }

    private fun folderOf(uri: String): LocalFolder {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrDefault(uri)
        val path = docId.substringAfter(':', docId)
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { docId.substringBefore(':').let { if (it == "primary") "Internal storage" else it } }
        return LocalFolder(uri, name)
    }

    companion object {
        private const val TAG = "LocalLibrary"
        private const val MIN_REFRESH_MS = 30_000L
        private const val MAX_FILES = 20_000
    }
}
