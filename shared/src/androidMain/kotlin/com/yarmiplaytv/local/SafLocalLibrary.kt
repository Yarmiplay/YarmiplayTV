package com.yarmiplaytv.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.media.ExternalSubtitle
import com.yarmiplaytv.media.sidecarLanguage
import com.yarmiplaytv.media.subtitleSiblings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel

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
        if (Uri.parse(uri).scheme == "file") return
        runCatching { resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Logger.w(TAG, "No persistable permission for $uri", it) }
    }

    override fun onFolderRemoved(uri: String) {
        if (Uri.parse(uri).scheme == "file") return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Adds all currently readable mounted storage volumes (used after All Files access is granted on the website APK). */
    fun addMountedStorageRoots() {
        addFolders(storageFolderUris(app))
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

    override fun openChannel(uri: String): FileChannel {
        val pfd = resolver.openFileDescriptor(Uri.parse(uri), "r") ?: throw IOException("Can't open $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }

    override fun scan(folder: LocalFolder): List<LocalFile> {
        val treeUri = Uri.parse(folder.uri)
        if (treeUri.scheme == "file") return scanFiles(folder, treeUri.path?.let(::File))
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return browseSaf(rootId, childrenOf = { parent -> queryChildren(treeUri, parent) }).map { step ->
            LocalFile(
                step.name,
                step.size,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, step.id).toString(),
                folder.name,
                step.modified,
                step.directory,
                folder.uri,
            )
        }
    }

    private fun queryChildren(treeUri: Uri, parentId: String): List<SafEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val rows = ArrayList<SafEntry>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                rows += SafEntry(
                    id = c.getString(0),
                    name = name,
                    mime = c.getString(2),
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return rows
    }

    override fun sidecarsBeside(videoUri: String, videoName: String): List<ExternalSubtitle> {
        val parsed = Uri.parse(videoUri)
        if (parsed.scheme == "file") {
            val dir = parsed.path?.let { File(it).parentFile } ?: return emptyList()
            return fileSidecarsIn(dir, videoName)
        }
        if (videoUri.contains("/tree/")) {
            return treeSidecarsBeside(parsed, videoName)
        }
        // Single-document content:// URI (e.g. "Open with" or single-file picker):
        // 1. If All Files access is already granted (website APK), resolve to the underlying File.
        resolveLocalFile(parsed, videoName)?.parentFile?.let { dir ->
            return fileSidecarsIn(dir, videoName)
        }
        // 2. If the file lives inside a media folder the user already granted (SAF tree or scanned volume),
        //    list siblings through that folder's URI without needing another permission prompt.
        indexedMatch(parsed, videoName)?.let { match ->
            if (match.uri != videoUri && (match.uri.startsWith("file://") || match.uri.contains("/tree/"))) {
                return sidecarsBeside(match.uri, videoName)
            }
        }
        // 3. Some document providers allow listing siblings directly from the document URI.
        return nonTreeDocumentSidecars(parsed, videoName)
    }

    override fun sidecarsInFolder(folderUri: String, videoName: String): List<ExternalSubtitle> {
        val parsed = Uri.parse(folderUri)
        if (parsed.scheme == "file") {
            val dir = parsed.path?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
            return fileSidecarsIn(dir, videoName)
        }
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull() ?: return emptyList()
        return treeSidecarsInParent(parsed, treeId, videoName)
    }

    override fun needsSidecarFolderAccess(videoUri: String, videoName: String): Boolean {
        val parsed = Uri.parse(videoUri)
        if (parsed.scheme == "file") {
            val dir = parsed.path?.let { File(it).parentFile } ?: return false
            return runCatching { dir.listFiles() == null }.getOrDefault(true)
        }
        if (parsed.scheme != "content") return false
        if (videoUri.contains("/tree/")) return false
        if (resolveLocalFile(parsed, videoName) != null) return false
        if (indexedMatch(parsed, videoName) != null) return false
        if (canListNonTreeParent(parsed)) return false
        return true
    }

    private fun fileSidecarsIn(dir: File, videoName: String): List<ExternalSubtitle> {
        val files = runCatching { dir.listFiles()?.filter { it.isFile } }.getOrNull().orEmpty()
        val chosen = subtitleSiblings(videoName, files.map { it.name }).toSet()
        return files.filter { it.name in chosen }.map { file ->
            ExternalSubtitle(Uri.fromFile(file).toString(), file.name.substringBeforeLast('.'), sidecarLanguage(file.name))
        }
    }

    private fun treeSidecarsBeside(parsed: Uri, videoName: String): List<ExternalSubtitle> {
        val docId = runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull() ?: return emptyList()
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull() ?: return emptyList()
        val parentId = if ('/' in docId) docId.substringBeforeLast('/') else treeId
        if (parentId == docId) return emptyList()
        return treeSidecarsInParent(parsed, parentId, videoName)
    }

    private fun treeSidecarsInParent(treeUri: Uri, parentId: String, videoName: String): List<ExternalSubtitle> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    rows += c.getString(0) to name
                }
            }
        }
        val chosen = subtitleSiblings(videoName, rows.map { it.second }).toSet()
        return rows.filter { it.second in chosen }.mapNotNull { (id, name) ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
            val playable = if (name.endsWith(".idx", ignoreCase = true)) stageVobSub(uri, name, rows, treeUri) else uri
            playable?.let { ExternalSubtitle(it, name.substringBeforeLast('.'), sidecarLanguage(name)) }
        }
    }

    private fun nonTreeParentChildrenUri(uri: Uri): Uri? {
        val authority = uri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val path = runCatching { DocumentsContract.findDocumentPath(resolver, uri)?.path }.getOrNull()
        val parentId = path?.getOrNull(path.lastIndex - 1)
            ?: docId?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() && it != docId }
            ?: return null
        return DocumentsContract.buildChildDocumentsUri(authority, parentId)
    }

    private fun canListNonTreeParent(uri: Uri): Boolean {
        val children = nonTreeParentChildrenUri(uri) ?: return false
        return runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun nonTreeDocumentSidecars(uri: Uri, videoName: String): List<ExternalSubtitle> {
        val authority = uri.authority ?: return emptyList()
        val children = nonTreeParentChildrenUri(uri) ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    rows += id to name
                }
            }
        }
        val chosen = subtitleSiblings(videoName, rows.map { it.second }).toSet()
        return rows.filter { it.second in chosen }.mapNotNull { (id, name) ->
            val subUri = DocumentsContract.buildDocumentUri(authority, id).toString()
            val playable = if (name.endsWith(".idx", ignoreCase = true)) {
                val subName = name.dropLast(3) + "sub"
                val subId = rows.firstOrNull { it.second.equals(subName, ignoreCase = true) }?.first
                subId?.let { stageVobSubUris(subUri, name, DocumentsContract.buildDocumentUri(authority, it).toString(), subName) }
            } else {
                subUri
            }
            playable?.let { ExternalSubtitle(it, name.substringBeforeLast('.'), sidecarLanguage(name)) }
        }
    }

    private fun indexedMatch(uri: Uri, videoName: String): LocalFile? {
        val named = files.value.filter { it.name.equals(videoName, ignoreCase = true) }
        if (named.isEmpty()) return null
        if (named.size == 1) return named.single()
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: return null
        return named.firstOrNull { it.sizeBytes == size }
    }

    /**
     * Resolves a content:// URI to a real [File] when the app already holds All Files access (website APK).
     * Never asks for permissions; on Play builds `isExternalStorageManager()` is always false.
     */
    @Suppress("DEPRECATION")
    private fun resolveLocalFile(uri: Uri, videoName: String): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) return null
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (docId != null && ':' in docId) {
                val volume = docId.substringBefore(':')
                val rel = docId.substringAfter(':')
                val base = if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage/$volume")
                }
                val candidate = File(base, rel)
                if (candidate.isFile && candidate.parentFile?.canRead() == true) return candidate
            }
        }
        val dataPath = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                val col = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        }.getOrNull()
        dataPath?.let(::File)?.takeIf { it.isFile && it.name.equals(videoName, ignoreCase = true) && it.parentFile?.canRead() == true }?.let { return it }
        return null
    }

    /**
     * mpv opens one file descriptor for a content subtitle, so a VobSub index would not see its `.sub`.
     * Both files are copied next to each other in the cache and the index path is what gets loaded.
     */
    private fun stageVobSub(idxUri: String, idxName: String, rows: List<Pair<String, String>>, tree: Uri): String? {
        val subName = idxName.dropLast(3) + "sub"
        val subId = rows.firstOrNull { it.second.equals(subName, ignoreCase = true) }?.first ?: return null
        val subUri = DocumentsContract.buildDocumentUriUsingTree(tree, subId).toString()
        return stageVobSubUris(idxUri, idxName, subUri, subName)
    }

    private fun stageVobSubUris(idxUri: String, idxName: String, subUri: String, subName: String): String? {
        val idxBytes = readLimited(idxUri) ?: return null
        val subBytes = readLimited(subUri) ?: return null
        val folder = File(app.cacheDir, "vobsub/" + Integer.toHexString((idxUri + subUri).hashCode()))
        if (!folder.mkdirs() && !folder.isDirectory) return null
        val idxFile = File(folder, idxName.substringAfterLast('/'))
        val subFile = File(folder, subName.substringAfterLast('/'))
        return runCatching {
            idxFile.writeBytes(idxBytes)
            subFile.writeBytes(subBytes)
            Uri.fromFile(idxFile).toString()
        }.getOrNull()
    }

    private fun readLimited(uri: String): ByteArray? = runCatching {
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_VOBSUB) null else bytes
        }
    }.getOrNull()

    private fun scanFiles(folder: LocalFolder, root: File?): List<LocalFile> {
        if (root == null || !root.isDirectory) return emptyList()
        val rootPath = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val out = ArrayList<LocalFile>()
        val pending = ArrayDeque<File>()
        pending.add(rootPath)
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val dir = pending.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                    val rootPrefix = rootPath.path.trimEnd('/') + "/"
                    if (canonical.path == rootPath.path || canonical.path.startsWith(rootPrefix)) pending.add(canonical)
                } else if (child.isFile && LocalMatcher.isMedia(child.name, null)) {
                    val parent = child.parentFile
                    val directory = if (parent == null || parent == rootPath) "" else rootPath.toPath().relativize(parent.toPath()).toString().replace('\\', '/')
                    out += LocalFile(
                        child.name,
                        child.length(),
                        Uri.fromFile(child).toString(),
                        folder.name,
                        child.lastModified(),
                        directory,
                        folder.uri,
                    )
                }
            }
        }
        return out
    }

    override fun folderOf(uri: String): LocalFolder {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val name = parsed.path?.trimEnd('/')?.substringAfterLast('/').orEmpty().ifEmpty { "Storage" }
            return LocalFolder(uri, name)
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrDefault(uri)
        return LocalFolder(uri, documentTreeLabel(docId))
    }

    private companion object {
        const val TAG = "LocalLibrary"
        const val MAX_VOBSUB = 32 * 1024 * 1024
    }
}
