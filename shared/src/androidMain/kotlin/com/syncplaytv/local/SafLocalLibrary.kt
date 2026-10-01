package com.syncplaytv.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.syncplaytv.Logger
import com.syncplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media folders picked with the system folder picker (access persisted) and indexed through the
 * Storage Access Framework.
 */
class SafLocalLibrary(
    context: Context,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
    initialFolders: List<String>,
) : LocalLibrary(settingsStore, scope) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    init {
        start(initialFolders)
    }

    override fun onFolderAdded(uri: String) {
        runCatching { resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Logger.w(TAG, "No persistable permission for $uri", it) }
    }

    override fun onFolderRemoved(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Name and size of a single content:// or file:// URI. */
    override suspend fun describe(uri: String): LocalFile = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        var name: String? = null
        var size = 0L
        if (parsed.scheme == "content") {
            runCatching {
                resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
        } else if (parsed.scheme == "file") {
            parsed.path?.let { java.io.File(it) }?.let { f -> name = f.name; size = f.length() }
        }
        LocalFile(name ?: parsed.lastPathSegment?.substringAfterLast('/') ?: uri, size, uri)
    }

    override fun scan(folder: LocalFolder): List<LocalFile> {
        val treeUri = Uri.parse(folder.uri)
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
                        out += LocalFile(name, size, DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), folder.name)
                    }
                }
            }
        }
        return out
    }

    override fun folderOf(uri: String): LocalFolder {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrDefault(uri)
        val path = docId.substringAfter(':', docId)
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { docId.substringBefore(':').let { if (it == "primary") "Internal storage" else it } }
        return LocalFolder(uri, name)
    }

    private companion object {
        const val TAG = "LocalLibrary"
    }
}
