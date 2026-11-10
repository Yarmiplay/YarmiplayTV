package com.yarmiplaytv.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

/** Title, artist and cover image read from the start of a local file, without opening a second player. */
data class EmbeddedTags(val title: String?, val artist: String?, val picture: ByteArray?)

/**
 * Cover art and tags from the front of an audio file (ID3, FLAC, Opus/Vorbis picture blocks, MP4 `covr`).
 * Anything it doesn't recognise is skipped; a miss just means the screen has no picture.
 */
object EmbeddedMedia {
    const val PREFIX_BYTES = 2_000_000

    fun read(bytes: ByteArray): EmbeddedTags {
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return readId3(bytes)
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(FLAC)) return readFlac(bytes)
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(OGG)) {
            val ogg = readOgg(bytes)
            if (ogg.picture != null || ogg.title != null || ogg.artist != null) return ogg
        }
        return EmbeddedTags(null, null, readMp4Cover(bytes))
    }

    /** A filesystem path for [url], or null for a stream. */
    fun pathOf(url: String): String? = when {
        url.startsWith("file:") -> runCatching { Paths.get(URI(url)).toString() }.getOrNull()
        url.startsWith("/") -> url
        url.length > 2 && url[1] == ':' -> url
        else -> null
    }

    /** The first [maxBytes] of a local file, or null when it isn't one. */
    fun readPrefix(url: String, maxBytes: Int = PREFIX_BYTES): ByteArray? {
        val path = pathOf(url) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        return file.inputStream().use { readPrefix(it, maxBytes.coerceAtMost(file.length().toInt())) }
    }

    fun readPrefix(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(8192)
        var left = maxBytes
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun readId3(bytes: ByteArray): EmbeddedTags {
        if (bytes.size < 10) return EmbeddedTags(null, null, null)
        val version = bytes[3].toInt() and 0xFF
        var offset = 10
        val tagSize = synchsafe(bytes, 6)
        val end = minOf(bytes.size, 10 + tagSize)
        if (version >= 3 && bytes[5].toInt() and 0x40 != 0 && offset + 4 <= end) {
            val ext = if (version == 4) synchsafe(bytes, offset) else u32(bytes, offset)
            offset += ext.coerceAtLeast(4)
        }
        var title: String? = null
        var artist: String? = null
        var picture: ByteArray? = null
        val v2 = version <= 2
        val idLen = if (v2) 3 else 4
        val sizeLen = if (v2) 3 else 4
        val flagsLen = if (v2) 0 else 2
        while (offset + idLen + sizeLen + flagsLen <= end) {
            val id = String(bytes, offset, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || id.any { ch ->
                    val c = ch.code
                    c !in 'A'.code..'Z'.code && c !in '0'.code..'9'.code
                }
            ) break
            offset += idLen
            val size = if (v2) u24(bytes, offset) else if (version >= 4) synchsafe(bytes, offset) else u32(bytes, offset)
            offset += sizeLen
            if (!v2) offset += 2
            if (size <= 0 || offset + size > end) break
            val frame = bytes.copyOfRange(offset, offset + size)
            offset += size
            when (id) {
                "TIT2", "TT2" -> title = title ?: textFrame(frame)
                "TPE1", "TP1" -> artist = artist ?: textFrame(frame)
                "APIC", "PIC" -> if (picture == null) picture = if (id == "PIC") picV2(frame) else apic(frame)
            }
        }
        return EmbeddedTags(title, artist, picture)
    }

    private fun textFrame(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xFF
        return decodeText(encoding, frame.copyOfRange(1, frame.size)).trim().takeIf { it.isNotEmpty() }
    }

    private fun apic(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        var i = 1
        while (i < frame.size && frame[i] != 0.toByte()) i++
        i += 2 // null then picture type
        if (i >= frame.size) return null
        i = skipTerminated(frame, i, encoding)
        if (i < 0 || i >= frame.size) return null
        return imageOrNull(frame.copyOfRange(i, frame.size))
    }

    /** ID3v2.2 PIC: encoding, 3-byte image format, picture type, description, data. */
    private fun picV2(frame: ByteArray): ByteArray? {
        if (frame.size < 6) return null
        val encoding = frame[0].toInt() and 0xFF
        var i = 5 // past format (3) and picture type (1)
        i = skipTerminated(frame, i, encoding)
        if (i < 0 || i >= frame.size) return null
        return imageOrNull(frame.copyOfRange(i, frame.size))
    }

    private fun readFlac(bytes: ByteArray): EmbeddedTags {
        var offset = 4
        var title: String? = null
        var artist: String? = null
        var picture: ByteArray? = null
        var guard = 0
        while (offset + 4 <= bytes.size && guard++ < 64) {
            val header = bytes[offset].toInt() and 0xFF
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val size = u24(bytes, offset + 1)
            offset += 4
            if (size < 0 || offset + size > bytes.size) break
            val block = bytes.copyOfRange(offset, offset + size)
            offset += size
            when (type) {
                4 -> {
                    val tags = vorbisComments(block)
                    title = title ?: tags.title
                    artist = artist ?: tags.artist
                }
                6 -> picture = picture ?: pictureBlock(block)
            }
            if (last) break
        }
        return EmbeddedTags(title, artist, picture)
    }

    private fun readOgg(bytes: ByteArray): EmbeddedTags {
        val marker = "METADATA_BLOCK_PICTURE="
        val ascii = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrNull() ?: return EmbeddedTags(null, null, null)
        var title: String? = null
        var artist: String? = null
        for (key in listOf("TITLE=", "title=")) {
            val at = ascii.indexOf(key)
            if (at >= 0) {
                title = ascii.substring(at + key.length).substringBefore('\u0000').substringBefore('\n').trim().takeIf { it.isNotEmpty() }
                if (title != null) break
            }
        }
        for (key in listOf("ARTIST=", "artist=")) {
            val at = ascii.indexOf(key)
            if (at >= 0) {
                artist = ascii.substring(at + key.length).substringBefore('\u0000').substringBefore('\n').trim().takeIf { it.isNotEmpty() }
                if (artist != null) break
            }
        }
        val at = ascii.indexOf(marker)
        if (at < 0) return EmbeddedTags(title, artist, null)
        val b64 = ascii.substring(at + marker.length).takeWhile { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val decoded = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return EmbeddedTags(title, artist, null)
        return EmbeddedTags(title, artist, pictureBlock(decoded))
    }

    private fun vorbisComments(block: ByteArray): EmbeddedTags {
        if (block.size < 8) return EmbeddedTags(null, null, null)
        var offset = 0
        val vendor = u32le(block, offset)
        offset += 4 + vendor
        if (offset + 4 > block.size) return EmbeddedTags(null, null, null)
        val count = u32le(block, offset)
        offset += 4
        var title: String? = null
        var artist: String? = null
        var n = 0
        while (n < count && offset + 4 <= block.size && n < 64) {
            val len = u32le(block, offset)
            offset += 4
            if (len < 0 || offset + len > block.size) break
            val comment = String(block, offset, len, Charsets.UTF_8)
            offset += len
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).lowercase()
                val value = comment.substring(eq + 1).trim()
                if (value.isNotEmpty()) {
                    if (key == "title") title = title ?: value
                    if (key == "artist" || key == "albumartist") artist = artist ?: value
                }
            }
            n++
        }
        return EmbeddedTags(title, artist, null)
    }

    /** A FLAC METADATA_BLOCK_PICTURE body (also what Opus stores, base64-encoded). */
    private fun pictureBlock(block: ByteArray): ByteArray? {
        if (block.size < 32) return null
        var offset = 4 // picture type
        val mimeLen = u32(block, offset)
        offset += 4 + mimeLen
        if (mimeLen < 0 || offset + 4 > block.size) return null
        val descLen = u32(block, offset)
        offset += 4 + descLen + 16 // description, width, height, depth, colours
        if (descLen < 0 || offset + 4 > block.size) return null
        val dataLen = u32(block, offset)
        offset += 4
        if (dataLen < 32 || offset + dataLen > block.size) return null
        return imageOrNull(block.copyOfRange(offset, offset + dataLen))
    }

    private fun readMp4Cover(bytes: ByteArray): ByteArray? {
        val covr = "covr".encodeToByteArray()
        var from = 0
        while (from < bytes.size - 16) {
            val at = indexOf(bytes, covr, from) ?: return null
            val dataAt = indexOf(bytes, "data".encodeToByteArray(), at) ?: return null
            if (dataAt < 4 || dataAt - at > 64) {
                from = at + 4
                continue
            }
            val atomStart = dataAt - 4
            val atomSize = u32(bytes, atomStart)
            if (atomSize < 16 || atomStart + atomSize > bytes.size) {
                from = dataAt + 4
                continue
            }
            val end = atomStart + atomSize
            // version/flags and a reserved word sit between "data" and the image; a few files skip one of them.
            var payload = dataAt + 4
            while (payload + 32 <= end && payload <= dataAt + 24) {
                imageOrNull(bytes.copyOfRange(payload, end))?.let { return it }
                payload++
            }
            from = dataAt + 4
        }
        return null
    }

    private fun imageOrNull(data: ByteArray): ByteArray? {
        if (data.size < 32 || data.size > PREFIX_BYTES) return null
        val jpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val png = data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()
        val webp = data.size > 12 &&
            data.copyOfRange(0, 4).contentEquals(RIFF) &&
            data.copyOfRange(8, 12).contentEquals(WEBP)
        return if (jpeg || png || webp) data else null
    }

    private fun skipTerminated(frame: ByteArray, start: Int, encoding: Int): Int {
        var i = start
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < frame.size) {
                if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return -1
        }
        while (i < frame.size) {
            if (frame[i] == 0.toByte()) return i + 1
            i++
        }
        return -1
    }

    private fun decodeText(encoding: Int, data: ByteArray): String {
        val raw = when (encoding) {
            0 -> String(data, Charsets.ISO_8859_1)
            1 -> String(data, Charsets.UTF_16)
            2 -> String(data, Charsets.UTF_16BE)
            else -> String(data, Charsets.UTF_8)
        }
        return raw.trim('\u0000', ' ')
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || from >= haystack.size) return null
        var i = from
        while (i + needle.size <= haystack.size) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return null
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun u32le(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun u24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private val FLAC = "fLaC".encodeToByteArray()
    private val OGG = "OggS".encodeToByteArray()
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP = "WEBP".encodeToByteArray()
}
