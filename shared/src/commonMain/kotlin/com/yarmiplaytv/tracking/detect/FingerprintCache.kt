package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.Logger
import com.yarmiplaytv.media.SkipSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Where a shared stretch of sound is in one episode, in seconds. */
data class Span(val start: Double, val end: Double)

/** By kind, then by the other episode's key. */
private typealias Observations = Map<String, Map<String, List<Span>>>

/**
 * What listening for openings and endings has learned, kept in [dir] so it's never done twice: each episode's
 * fingerprints (its start and its end), and for each pair of episodes compared, the stretches they share (none, too).
 * Episodes are known by file name and size, so a file on this device and the same file on a server are one. Kept
 * under [MAX_BYTES]; the least recently used go first.
 */
class FingerprintCache(private val dir: File) {
    private val pruned = AtomicBoolean(false)

    /** A fingerprint of the sound from [start] seconds into the episode. */
    class Stored(val start: Double, val points: IntArray)

    fun fingerprint(key: String, kind: SkipSegment.Kind): Stored? = synchronized(this) {
        val file = fingerprintFile(key, kind)
        if (!file.isFile) return null
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val start = input.readDouble()
                val points = IntArray(input.readInt()) { input.readInt() }
                file.setLastModified(System.currentTimeMillis())
                Stored(start, points)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Unreadable fingerprint ${file.name}", e)
            file.delete()
            null
        }
    }

    fun saveFingerprint(key: String, kind: SkipSegment.Kind, stored: Stored) = synchronized(this) {
        if (pruned.compareAndSet(false, true)) prune()
        write(fingerprintFile(key, kind)) { out ->
            DataOutputStream(out.buffered()).use { data ->
                data.writeInt(MAGIC)
                data.writeDouble(stored.start)
                data.writeInt(stored.points.size)
                stored.points.forEach(data::writeInt)
            }
        }
    }

    /** For episode [key], what each other episode compared with it shares with it, by that episode's key. */
    fun observations(key: String, kind: SkipSegment.Kind): Map<String, List<Span>> = synchronized(this) {
        read(key)[kind.name].orEmpty()
    }

    /** Remembers that episode [key] shares [spans] (its own times) with episode [other]. */
    fun observe(key: String, other: String, kind: SkipSegment.Kind, spans: List<Span>) = synchronized(this) {
        val now = read(key)
        val next = now + (kind.name to (now[kind.name].orEmpty() + (other to spans)))
        val encoded = JsonObject(next.mapValues { (_, byOther) ->
            JsonObject(byOther.mapValues { (_, list) -> JsonArray(list.map { JsonArray(listOf(JsonPrimitive(it.start), JsonPrimitive(it.end))) }) })
        })
        write(observationsFile(key)) { out -> out.write(encoded.toString().toByteArray()) }
    }

    private fun read(key: String): Observations {
        val file = observationsFile(key)
        if (!file.isFile) return emptyMap()
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, byOther) ->
                byOther.jsonObject.mapValues { (_, list) ->
                    list.jsonArray.map { pair -> pair.jsonArray.let { Span(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
                }
            }.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (e: Exception) {
            file.delete()
            emptyMap()
        }
    }

    private fun write(target: File, body: (java.io.OutputStream) -> Unit) {
        try {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.outputStream().use(body)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) part.delete()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Can't save ${target.name}", e)
        }
    }

    private fun fingerprintFile(key: String, kind: SkipSegment.Kind) = File(dir, "fp/$key-${if (kind == SkipSegment.Kind.OPENING) "op" else "ed"}.bin")
    private fun observationsFile(key: String) = File(dir, "obs/$key.json")

    /** Down to three quarters of [MAX_BYTES] when over it, least recently used first. */
    private fun prune() {
        val files = listOf("fp", "obs").flatMap { File(dir, it).listFiles()?.toList().orEmpty() }
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        private const val TAG = "FingerprintCache"
        private const val MAGIC = 0x59465031 // "YFP1"
        const val MAX_BYTES = 64L * 1024 * 1024

        /** Who an episode file is: its name and size when known (the same file anywhere), else its address. */
        fun keyOf(fileName: String, sizeBytes: Long, url: String): String {
            val identity = if (sizeBytes > 0 && fileName.isNotBlank()) "${fileName.lowercase()}|$sizeBytes" else url.substringBefore('?')
            return MessageDigest.getInstance("SHA-1").digest("v1|$identity".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
