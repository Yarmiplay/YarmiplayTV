package com.syncplaytv.local

import com.syncplaytv.Logger
import com.syncplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Media folders on the local file system (file:// URIs), indexed with a directory walk and
 * re-indexed when videos or subfolders inside them change.
 */
class FileLocalLibrary(
    settingsStore: SettingsStore,
    scope: CoroutineScope,
    initialFolders: List<String>,
    watchForChanges: Boolean = true,
) : LocalLibrary(settingsStore, scope) {
    private val watcher: WatchService? =
        if (watchForChanges) runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() else null
    private val watched = ConcurrentHashMap<Path, WatchKey>()

    init {
        start(initialFolders)
        watcher?.let { w -> scope.launch(Dispatchers.IO) { watchLoop(w) } }
    }

    /** Stops watching the folders. */
    fun close() {
        runCatching { watcher?.close() }
        watched.clear()
    }

    override suspend fun describe(uri: String): LocalFile = withContext(Dispatchers.IO) {
        val path = runCatching { pathOf(uri) }.getOrNull()
        if (path != null && Files.isRegularFile(path)) {
            LocalFile(path.fileName.toString(), Files.size(path), uriOf(path))
        } else {
            LocalFile(uri.substringBefore('?').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { uri }, 0L, uri)
        }
    }

    override fun scan(folder: LocalFolder): List<LocalFile> {
        val root = pathOf(folder.uri)
        val out = ArrayList<LocalFile>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                watch(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (attrs.isRegularFile && LocalMatcher.isVideo(name, null)) {
                    out += LocalFile(name, attrs.size(), uriOf(file), folder.name)
                }
                return if (out.size >= MAX_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out
    }

    override fun folderOf(uri: String): LocalFolder {
        val path = runCatching { pathOf(uri) }.getOrNull()
        return LocalFolder(uri, path?.fileName?.toString() ?: path?.toString() ?: uri)
    }

    override fun onFolderRemoved(uri: String) {
        val root = runCatching { pathOf(uri) }.getOrNull() ?: return
        watched.keys.filter { it.startsWith(root) }.forEach { watched.remove(it)?.cancel() }
    }

    private fun watch(dir: Path) {
        val w = watcher ?: return
        if (watched.containsKey(dir)) return
        runCatching { dir.register(w, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
            .onSuccess { watched[dir] = it }
            .onFailure { Logger.w(TAG, "Cannot watch $dir", it) }
    }

    private suspend fun watchLoop(w: WatchService) {
        try {
            while (currentCoroutineContext().isActive) {
                val first = runInterruptible { w.take() }
                var changed = drain(first)
                // Copies and extractions arrive as bursts of events; index once they settle.
                delay(SETTLE_MS)
                while (true) {
                    val next = w.poll() ?: break
                    changed = drain(next) || changed
                }
                if (changed) refresh()
            }
        } catch (_: ClosedWatchServiceException) {
        }
    }

    private fun drain(key: WatchKey): Boolean {
        val dir = key.watchable() as Path
        var relevant = false
        for (event in key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                relevant = true
                continue
            }
            val name = (event.context() as? Path)?.toString() ?: continue
            val child = dir.resolve(name)
            if (LocalMatcher.isVideo(name, null) || watched.containsKey(child) || Files.isDirectory(child)) relevant = true
        }
        if (!key.reset()) watched.remove(dir)
        return relevant
    }

    companion object {
        private const val TAG = "LocalLibrary"
        private const val SETTLE_MS = 1_500L

        fun uriOf(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()

        fun pathOf(uri: String): Path = if (uri.startsWith("file:")) Paths.get(URI(uri)) else Paths.get(uri)
    }
}
