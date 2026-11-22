package com.yarmiplaytv.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.media.ExternalSubtitle
import com.yarmiplaytv.media.matchingSidecarNames
import com.yarmiplaytv.media.sidecarLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.security.MessageDigest

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
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val path = parsed.path ?: throw IOException("Can't open $uri")
            return FileChannel.open(File(path).toPath(), java.nio.file.StandardOpenOption.READ)
        }
        val pfd = resolver.openFileDescriptor(parsed, "r") ?: throw IOException("Can't open $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }

    override fun scan(folder: LocalFolder): List<LocalFile> {
        if (Uri.parse(folder.uri).scheme == "file") return scanFileFolder(folder)
        val treeUri = Uri.parse(folder.uri)
        val out = ArrayList<LocalFile>()
        // Document id and its path under the media folder.
        val pending = ArrayDeque<Pair<String, String>>().apply { add(DocumentsContract.getTreeDocumentId(treeUri) to "") }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val (parent, directory) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending.add(id to if (directory.isEmpty()) name else "$directory/$name")
                    } else if (LocalMatcher.isMedia(name, mime)) {
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                        out += LocalFile(name, size, uri, folder.name, modified, directory, folder.uri)
                    }
                }
            }
        }
        return out
    }

    override fun sidecarsBeside(videoUri: String, videoName: String): List<ExternalSubtitle> {
        val parsed = Uri.parse(videoUri)
        if (parsed.scheme == "file") {
            val video = parsed.path?.let(::File) ?: return emptyList()
            return fileSidecars(video.parentFile, videoName)
        }
        val siblings = documentSiblings(videoUri) ?: return emptyList()
        return sidecarsFromDocuments(videoName, siblings)
    }

    override fun needsSidecarFolderPermission(videoUri: String, videoName: String): Boolean {
        val parsed = Uri.parse(videoUri)
        if (parsed.scheme == "file") {
            val directory = parsed.path?.let { File(it).parentFile } ?: return false
            return directory.listFiles() == null
        }
        return documentSiblings(videoUri) == null
    }

    override fun folderOf(uri: String): LocalFolder {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val directory = parsed.path?.let(::File)
            val internal = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            val name = when {
                directory == null -> uri
                directory.absolutePath == internal -> "Internal storage"
                else -> directory.name.ifEmpty { directory.absolutePath }
            }
            return LocalFolder(uri, name)
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrDefault(uri)
        val path = docId.substringAfter(':', docId)
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { docId.substringBefore(':').let { if (it == "primary") "Internal storage" else it } }
        return LocalFolder(uri, name)
    }

    private fun scanFileFolder(folder: LocalFolder): List<LocalFile> {
        val root = Uri.parse(folder.uri).path?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val out = ArrayList<LocalFile>()
        val pending = ArrayDeque<Pair<File, String>>().apply { add(root to "") }
        var visited = 0
        while (pending.isNotEmpty() && out.size < MAX_FILES && visited < MAX_FILES) {
            val (directory, relative) = pending.removeFirst()
            visited++
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (canonical != rootCanonical && !canonical.startsWith(rootCanonical + File.separator)) continue
            val children = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                Logger.w(TAG, "Cannot scan ${directory.path}", e)
                null
            } ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".")) continue
                    val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                    if (childPath == "Android/data" || childPath == "Android/obb") continue
                    pending.add(child to childPath)
                } else if (child.isFile && LocalMatcher.isMedia(child.name, null)) {
                    out += LocalFile(
                        child.name,
                        child.length(),
                        Uri.fromFile(child).toString(),
                        folder.name,
                        child.lastModified(),
                        relative,
                        folder.uri,
                    )
                    if (out.size >= MAX_FILES) break
                }
            }
        }
        return out
    }

    private fun fileSidecars(directory: File?, videoName: String): List<ExternalSubtitle> {
        val files = try {
            directory?.listFiles()?.filter { it.isFile } ?: return emptyList()
        } catch (e: SecurityException) {
            Logger.w(TAG, "Cannot list subtitles beside $videoName", e)
            return emptyList()
        }
        val matching = matchingSidecarNames(videoName, files.map { it.name }).toSet()
        return files.filter { it.name in matching }.map { file ->
            ExternalSubtitle(Uri.fromFile(file).toString(), file.name.substringBeforeLast('.'), sidecarLanguage(file.name))
        }
    }

    private data class Sibling(val uri: String, val name: String)

    private fun documentSiblings(videoUri: String): List<Sibling>? {
        val uri = Uri.parse(videoUri)
        val authority = uri.authority ?: return null
        val usingTree = uri.pathSegments.contains("tree")
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val treeId = if (usingTree) runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() else null
        val documentPath = if (usingTree) null else try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) DocumentsContract.findDocumentPath(resolver, uri)?.path else null
        } catch (_: Exception) {
            null
        }
        val parentId = when {
            usingTree && documentId != null && documentId != treeId && '/' in documentId -> documentId.substringBeforeLast('/')
            usingTree -> treeId
            else -> documentPath?.getOrNull(documentPath.lastIndex - 1)
                ?: documentId?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        } ?: return null
        val children = if (usingTree && treeId != null) {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
        } else {
            DocumentsContract.buildChildDocumentsUri(authority, parentId)
        }
        return try {
            val cursor = resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: return null
            cursor.use {
                buildList {
                    while (it.moveToNext()) {
                        val id = it.getString(0) ?: continue
                        val name = it.getString(1) ?: continue
                        val sibling = if (usingTree) DocumentsContract.buildDocumentUriUsingTree(uri, id) else DocumentsContract.buildDocumentUri(authority, id)
                        add(Sibling(sibling.toString(), name))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Cannot list subtitles beside this video", e)
            null
        }
    }

    private fun sidecarsFromDocuments(videoName: String, siblings: List<Sibling>): List<ExternalSubtitle> {
        val matching = matchingSidecarNames(videoName, siblings.map { it.name }).map { it.lowercase() }.toSet()
        val byName = siblings.associateBy { it.name.lowercase() }
        return siblings.mapNotNull { sibling ->
            if (sibling.name.lowercase() !in matching) return@mapNotNull null
            val subtitleUri = if (sibling.name.endsWith(".idx", ignoreCase = true)) {
                val paired = byName["${sibling.name.substringBeforeLast('.').lowercase()}.sub"] ?: return@mapNotNull null
                cacheVobSub(sibling, paired) ?: return@mapNotNull null
            } else {
                sibling.uri
            }
            ExternalSubtitle(subtitleUri, sibling.name.substringBeforeLast('.'), sidecarLanguage(sibling.name))
        }
    }

    /** mpv opens an `.idx` from a real path and looks for the `.sub` in that same folder. */
    private fun cacheVobSub(index: Sibling, sub: Sibling): String? {
        val key = MessageDigest.getInstance("SHA-256").digest("${index.uri}\n${sub.uri}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(app.cacheDir, "sidecars/$key")
        val indexFile = File(directory, safeName(index.name))
        val subFile = File(directory, safeName(sub.name))
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Can't create subtitle cache")
            copyToCache(index.uri, indexFile)
            copyToCache(sub.uri, subFile)
            Uri.fromFile(indexFile).toString()
        } catch (e: Exception) {
            indexFile.delete()
            subFile.delete()
            Logger.w(TAG, "Cannot cache VobSub ${index.name}", e)
            null
        }
    }

    private fun copyToCache(uri: String, target: File) {
        val input = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Can't open subtitle")
        input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
    }

    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: throw IOException("Invalid subtitle filename")

    private companion object {
        const val TAG = "LocalLibrary"
    }
}
