package com.yarmiplaytv.local

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel

data class LocalFolder(val uri: String, val name: String)

/**
 * The user's media folders (persisted in settings) and an index of the videos inside them, used to
 * resolve shared-playlist entries like desktop Syncplay's media directories, plus helpers for
 * single files opened directly. Platforms supply folder scanning and file descriptions.
 */
abstract class LocalLibrary(
    private val settingsStore: SettingsStore,
    protected val scope: CoroutineScope,
) {
    private val _folders = MutableStateFlow<List<LocalFolder>>(emptyList())
    val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()

    private val _files = MutableStateFlow<List<LocalFile>>(emptyList())
    val files: StateFlow<List<LocalFile>> = _files.asStateFlow()

    private val _indexing = MutableStateFlow(false)
    val indexing: StateFlow<Boolean> = _indexing.asStateFlow()

    private val indexLock = Mutex()
    private var lastIndexAt = 0L

    val hasFolders: Boolean get() = _folders.value.isNotEmpty()

    /** Loads the saved folders and indexes them; subclasses call this at the end of their constructor. */
    protected fun start(initialFolders: List<String>) {
        _folders.value = initialFolders.map(::folderOf)
        if (initialFolders.isNotEmpty()) scope.launch { refresh() }
    }

    fun addFolder(uri: String) {
        onFolderAdded(uri)
        if (_folders.value.any { it.uri == uri }) return
        _folders.value = _folders.value + folderOf(uri)
        persistAndRefresh()
    }

    fun removeFolder(folder: LocalFolder) {
        onFolderRemoved(folder.uri)
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
                    runCatching { scan(folder) }
                        .onFailure { Logger.w(TAG, "Cannot read ${folder.name}", it) }
                        .getOrDefault(emptyList())
                }
            }
            _files.value = found.sortedBy { it.name.lowercase() }
            lastIndexAt = System.currentTimeMillis()
            Logger.i(TAG, "Indexed ${found.size} videos in ${_folders.value.size} folder(s)")
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

    /** Name and size of a single file URI. */
    abstract suspend fun describe(uri: String): LocalFile

    /** Opens [uri] for reading at any offset (to serve it to the room's file relay); blocking, call on IO. */
    abstract fun openChannel(uri: String): FileChannel

    /** Videos inside [folder] and its subfolders (at most [MAX_FILES]); runs on the IO dispatcher. */
    protected abstract fun scan(folder: LocalFolder): List<LocalFile>

    /** The folder entry (with display name) for a folder URI. */
    protected abstract fun folderOf(uri: String): LocalFolder

    protected open fun onFolderAdded(uri: String) {}
    protected open fun onFolderRemoved(uri: String) {}

    companion object {
        private const val TAG = "LocalLibrary"
        private const val MIN_REFRESH_MS = 30_000L
        const val MAX_FILES = 20_000
    }
}
