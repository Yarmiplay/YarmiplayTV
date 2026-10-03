package com.yarmiplaytv.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Basename index of every video file on a server, so a Syncplay playlist entry can be found without
 * a search. Each source supplies the (item id, file name) pairs; this keeps the lookups and throttling.
 */
class FilenameIndex(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minRefreshMs: Long = 30_000L,
) {
    data class Entry(val id: String, val fileName: String)

    private val lock = Mutex()
    @Volatile private var byName: Map<String, List<Entry>> = emptyMap()
    @Volatile private var byNormalized: Map<String, List<Entry>> = emptyMap()
    @Volatile private var builtAt = 0L

    val isBuilt: Boolean get() = builtAt != 0L
    val size: Int get() = byName.values.sumOf { it.size }

    /** Rebuilds from [load], at most once per [minRefreshMs] unless [force]d. */
    suspend fun refresh(force: Boolean = false, load: suspend () -> List<Entry>) = lock.withLock {
        if (!force && builtAt != 0L && clock() - builtAt < minRefreshMs) return@withLock
        val entries = load()
        byName = entries.groupBy { it.fileName.lowercase() }
        byNormalized = entries.groupBy { FileNames.normalize(it.fileName) }
        builtAt = clock()
    }

    /** Entries whose file name equals [name], ignoring case. */
    fun exact(name: String): List<Entry> = byName[FileNames.baseName(name).lowercase()].orEmpty()

    /** Exact matches, else Syncplay-normalized ones; null when neither exists. */
    fun lookup(name: String): Pair<List<Entry>, MatchKind>? {
        exact(name).takeIf { it.isNotEmpty() }?.let { return it to MatchKind.EXACT_FILENAME }
        val normalized = byNormalized[FileNames.normalize(name)]
        return if (normalized.isNullOrEmpty()) null else normalized to MatchKind.NORMALIZED_FILENAME
    }
}
