package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet

/**
 * Temporary copies of relayed files in [dir] (the platform's cache folder, never the media folders), filled in
 * 1 MiB blocks as they arrive. Wiped when created; at most [capBytes] on disk, the least recently used files
 * going first; nothing older than [maxAgeMs].
 */
class RelayCache(
    private val dir: File,
    private val capBytes: () -> Long = { defaultCap(dir) },
    private val maxAgeMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val files = LinkedHashMap<String, CachedFile>()

    init {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    /** The cached copy of the file with [key] and [size], created empty if needed. */
    @Synchronized
    fun open(key: String, size: Long): CachedFile {
        files[key]?.takeIf { it.size == size }?.let {
            it.lastUsed = now()
            return it
        }
        files.remove(key)?.delete()
        val file = CachedFile(File(dir, "${safe(key)}.part"), size, now())
        files[key] = file
        return file
    }

    @Synchronized
    fun get(key: String): CachedFile? = files[key]

    @Synchronized
    fun keys(): Set<String> = files.keys.toSet()

    @Synchronized
    fun delete(key: String) {
        files.remove(key)?.delete()
    }

    /** Drops expired files, then the least recently used ones until the cache fits; files in [inUse] stay. */
    @Synchronized
    fun trim(inUse: Set<String> = emptySet()) {
        val t = now()
        files.entries.filter { (k, f) -> k !in inUse && t - f.created > maxAgeMs }.forEach { (k, f) ->
            f.delete()
            files.remove(k)
        }
        val cap = capBytes()
        var total = files.values.sumOf { it.cachedBytes }
        for ((k, f) in files.entries.sortedBy { it.value.lastUsed }) {
            if (total <= cap) break
            if (k in inUse) continue
            total -= f.cachedBytes
            f.delete()
            files.remove(k)
        }
    }

    @Synchronized
    fun totalBytes(): Long = files.values.sumOf { it.cachedBytes }

    companion object {
        const val BLOCK = 1L shl 20
        private const val MAX_CAP = 4L shl 30

        /** 4 GiB, or half the free space when there's less. */
        fun defaultCap(dir: File): Long = minOf(MAX_CAP, (dir.usableSpace / 2).coerceAtLeast(256L shl 20))

        private fun safe(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)
    }
}

/** One relayed file in the cache: a sparse file and which of its blocks are complete. */
class CachedFile internal constructor(private val file: File, val size: Long, val created: Long) {
    private val raf = RandomAccessFile(file, "rw")
    private val blocks = BitSet()
    private val blockCount = ((size + RelayCache.BLOCK - 1) / RelayCache.BLOCK).toInt()
    @Volatile var lastUsed: Long = created
    @Volatile private var deleted = false

    private val _version = MutableStateFlow(0L)
    /** Bumped whenever a block completes, for readers waiting on data. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Bytes in complete blocks. */
    @get:Synchronized
    val cachedBytes: Long
        get() = (0 until blockCount).sumOf { if (blocks[it]) blockLength(it) else 0L }

    val complete: Boolean @Synchronized get() = blocks.cardinality() == blockCount

    fun blockOf(offset: Long): Int = (offset / RelayCache.BLOCK).toInt()

    fun blockStart(index: Int): Long = index * RelayCache.BLOCK

    fun blockLength(index: Int): Long = minOf(RelayCache.BLOCK, size - blockStart(index))

    @Synchronized
    fun has(index: Int): Boolean = index in 0 until blockCount && blocks[index]

    /** The first missing block at or after [index], or null when the rest of the file is here. */
    @Synchronized
    fun firstMissing(index: Int = 0): Int? = blocks.nextClearBit(index.coerceAtLeast(0)).takeIf { it < blockCount }

    /** Bytes available from [offset] on, without a gap. */
    @Synchronized
    fun availableFrom(offset: Long): Long {
        if (offset >= size) return 0
        val start = blockOf(offset)
        if (!blocks[start]) return 0
        val end = blocks.nextClearBit(start).coerceAtMost(blockCount)
        return minOf(size, blockStart(end)) - offset
    }

    /** Writes [length] bytes of [data] at [offset]; blocks count only once [markComplete]d. */
    fun write(offset: Long, data: ByteArray, length: Int) {
        synchronized(raf) {
            if (deleted) return
            raf.seek(offset)
            raf.write(data, 0, length)
        }
    }

    @Synchronized
    fun markComplete(index: Int) {
        if (deleted || index !in 0 until blockCount || blocks[index]) return
        blocks.set(index)
        _version.value++
    }

    /** Reads up to [length] cached bytes at [offset] into [buffer]; returns how many. */
    fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(raf) {
        if (deleted) return -1
        raf.seek(offset)
        raf.read(buffer, 0, length)
    }

    @Synchronized
    internal fun delete() {
        deleted = true
        synchronized(raf) { runCatching { raf.close() } }
        if (!file.delete() && file.exists()) Logger.w("RelayCache", "Couldn't delete ${file.name}")
        _version.value++
    }

    val isDeleted: Boolean get() = deleted
}
