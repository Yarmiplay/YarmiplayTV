package com.yarmiplaytv.local

import com.yarmiplaytv.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Writes one frame of a local video as a JPEG; blocking. False when the video can't be read or decoded. */
fun interface FrameGrabber {
    /** [uri] is a local file's URI; the frame is scaled to [width] pixels wide and written to [target]. */
    fun grab(uri: String, width: Int, target: File): Boolean
}

/**
 * Preview frames of the videos in the file lists, made by the platform's [grabber] and kept as JPEGs in [dir].
 * Each is named after the file's URI, size and modification time, so a changed file gets a new preview. A few
 * are made at a time; a video that can't be decoded isn't tried again until the app restarts.
 */
class VideoThumbnails(private val dir: File, private val grabber: FrameGrabber) {
    private val gate = Semaphore(PARALLEL)
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pruned = AtomicBoolean(false)
    /** One lock per preview being made. */
    private val making = ConcurrentHashMap<String, Mutex>()

    /** The cached preview of [file], made first when there isn't one yet; null when there can't be one. */
    suspend fun thumbnail(file: LocalFile): File? {
        val key = keyOf(file)
        if (key in failed) return null
        val target = File(dir, "$key.jpg")
        cached(target)?.let { return it }
        val lock = making.computeIfAbsent(key) { Mutex() }
        return try {
            // Another request for the same file (the list row and the sheet) waits for this one's result.
            lock.withLock { if (key in failed) null else cached(target) ?: make(file, key, target) }
        } finally {
            making.remove(key, lock)
        }
    }

    private suspend fun make(file: LocalFile, key: String, target: File): File? =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                if (pruned.compareAndSet(false, true)) prune()
                dir.mkdirs()
                val part = File(dir, "$key.part")
                val made = try {
                    grabber.grab(file.uri, WIDTH, part) && part.length() > 0 && part.renameTo(target)
                } catch (e: Exception) {
                    Logger.w(TAG, "No preview for ${file.name}", e)
                    false
                }
                if (made) target else {
                    part.delete()
                    failed += key
                    null
                }
            }
        }

    private fun cached(target: File): File? = target.takeIf { it.isFile && it.length() > 0 }

    /** Keeps the cache to the [MAX_CACHED] most recent previews. */
    private fun prune() {
        val files = dir.listFiles()?.takeIf { it.size > MAX_CACHED } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_CACHED).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "VideoThumbnails"
        /** Pixels; twice the widest preview the lists show, for sharp images on dense screens. */
        const val WIDTH = 320
        private const val PARALLEL = 2
        private const val MAX_CACHED = 5_000

        internal fun keyOf(file: LocalFile): String =
            MessageDigest.getInstance("SHA-1").digest("${file.uri}|${file.sizeBytes}|${file.modified}".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
