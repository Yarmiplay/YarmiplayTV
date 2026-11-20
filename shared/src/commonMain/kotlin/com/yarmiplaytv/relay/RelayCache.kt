package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

    /**
     * The cached copy of the file with [key] and [size], created empty if needed. [partialBlocks] also counts the
     * written start of blocks still arriving, for files whose last block is only ever partly written (see
     * [CachedFile.notePartial]).
     */
    @Synchronized
    fun open(key: String, size: Long, partialBlocks: Boolean = false): CachedFile {
        files[key]?.takeIf { it.size == size && it.partialBlocks == partialBlocks }?.let {
            it.lastUsed = now()
            return it
        }
        files.remove(key)?.delete()
        val file = CachedFile(File(dir, "${safe(key)}.part"), size, now(), partialBlocks)
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

/**
 * One relayed file in the cache: a sparse file and which of its blocks are complete. With [partialBlocks] it also
 * keeps how much of each incomplete block is written from its start, so bytes at the end of a file that is still
 * growing can be read before their block fills.
 */
class CachedFile internal constructor(
    private val file: File,
    val size: Long,
    val created: Long,
    val partialBlocks: Boolean = false,
) {
    private val raf = RandomAccessFile(file, "rw")
    private val blocks = BitSet()
    /** Bytes written from the start of blocks not yet complete ([partialBlocks] only). */
    private val partial = HashMap<Int, Long>()
    private val partialAnnounced = HashMap<Int, Long>()
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

    /** The file on disk once every block is here, so the seek bar can read it without the proxy. */
    fun pathIfComplete(): String? = if (complete && !deleted) file.absolutePath else null

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
        if (!blocks[start]) return (blockStart(start) + partialOf(start) - offset).coerceAtLeast(0)
        val end = blocks.nextClearBit(start).coerceAtMost(blockCount)
        val whole = minOf(size, blockStart(end)) - offset
        return if (end < blockCount) whole + partialOf(end) else whole
    }

    /** How much of block [index] is written from its start while it isn't complete ([partialBlocks] only, else 0). */
    @Synchronized
    fun partialOf(index: Int): Long = if (blocks[index]) 0 else partial[index] ?: 0

    /**
     * Records that block [index] holds [bytes] from its start ([partialBlocks] only). Readers hear about it every
     * [PARTIAL_STEP] bytes, not on every write.
     */
    @Synchronized
    fun notePartial(index: Int, bytes: Long) {
        if (!partialBlocks || deleted || index !in 0 until blockCount || blocks[index]) return
        val have = bytes.coerceAtMost(blockLength(index))
        if (have <= (partial[index] ?: 0)) return
        partial[index] = have
        if (have - (partialAnnounced[index] ?: 0) >= PARTIAL_STEP) {
            partialAnnounced[index] = have
            _version.value++
        }
    }

    /** Readers waiting at the end of a growing file hear about everything written so far (the last write may be small). */
    @Synchronized
    fun announcePartial() {
        if (!partialBlocks || deleted) return
        var changed = false
        for ((i, have) in partial) {
            if ((partialAnnounced[i] ?: 0) < have) {
                partialAnnounced[i] = have
                changed = true
            }
        }
        if (changed) _version.value++
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
        partial.remove(index)
        partialAnnounced.remove(index)
        _version.value++
    }

    /** Reads up to [length] cached bytes at [offset] into [buffer]; returns how many. */
    fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(raf) {
        if (deleted) return -1
        raf.seek(offset)
        raf.read(buffer, 0, length)
    }

    /** Writes the whole file to [out]; call once it's [complete]. Blocking. */
    fun copyTo(out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        var pos = 0L
        while (pos < size) {
            val n = read(pos, buffer, minOf(COPY_BUFFER.toLong(), size - pos).toInt())
            if (n <= 0) throw IOException("the cached copy went away")
            out.write(buffer, 0, n)
            pos += n
        }
    }

    @Synchronized
    internal fun delete() {
        deleted = true
        synchronized(raf) { runCatching { raf.close() } }
        if (!file.delete() && file.exists()) Logger.w("RelayCache", "Couldn't delete ${file.name}")
        _version.value++
    }

    val isDeleted: Boolean get() = deleted

    private companion object {
        const val COPY_BUFFER = 256 * 1024
        /** How much a partial block grows between wake-ups of the readers waiting on it. */
        const val PARTIAL_STEP = 128L * 1024
    }
}
