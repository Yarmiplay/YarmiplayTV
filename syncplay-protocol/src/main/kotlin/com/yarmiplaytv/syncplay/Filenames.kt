package com.yarmiplaytv.syncplay

import java.net.URLDecoder
import java.security.MessageDigest

object Filenames {
    private val stripRegex = Regex("""[-~_.\[\](): ]""")
    private val hashRegex = Regex("[0-9a-f]{12}")

    /** What a Syncplay client in "don't send" privacy mode reports instead of its filename. */
    const val HIDDEN = "**Hidden filename**"

    fun isUrl(path: String): Boolean = path.contains("://")

    /** Syncplay's utils.stripfilename. */
    fun strip(filename: String, stripUrl: Boolean): String {
        var name = decode(filename)
        if (stripUrl) name = decode(name.substringAfterLast('/'))
        return name.replace(stripRegex, "")
    }

    /**
     * Syncplay's utils.sameFilename: ignores case, separators/brackets and URL-encoding, matches a name sent hashed
     * ("send hashed" privacy mode) against the plain one, and a hidden name against anything.
     */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == HIDDEN || b == HIDDEN) return true
        val stripUrl = isUrl(a) xor isUrl(b)
        val strippedA = strip(a, stripUrl)
        val strippedB = strip(b, stripUrl)
        if (strippedA.equals(strippedB, ignoreCase = true)) return true
        return (isHash(a) && a == hash(b, stripUrl)) || (isHash(b) && b == hash(a, stripUrl))
    }

    /**
     * A filename with what [same] works out from it (URL-decoding, stripping, hashing) done once and kept, for
     * comparing one name against many.
     */
    class Key(val name: String) {
        internal val hidden = name == HIDDEN
        internal val url = isUrl(name)
        internal val hashed = isHash(name)
        private val stripped = arrayOfNulls<String>(2)
        private val hashes = arrayOfNulls<String>(2)

        internal fun stripped(stripUrl: Boolean): String {
            val i = if (stripUrl) 1 else 0
            return stripped[i] ?: strip(name, stripUrl).also { stripped[i] = it }
        }

        /** [hash] of the name as [same] compares it. */
        internal fun hash(stripUrl: Boolean): String {
            val i = if (stripUrl) 1 else 0
            return hashes[i] ?: sha256Prefix(stripped(stripUrl || url)).also { hashes[i] = it }
        }
    }

    /** [same] for names already made into [Key]s. */
    fun same(a: Key, b: Key): Boolean {
        if (a.hidden || b.hidden) return true
        val stripUrl = a.url xor b.url
        if (a.stripped(stripUrl).equals(b.stripped(stripUrl), ignoreCase = true)) return true
        return (a.hashed && a.name == b.hash(stripUrl)) || (b.hashed && b.name == a.hash(stripUrl))
    }

    /** Syncplay's utils.hashFilename: what "send hashed" privacy mode sends instead of the filename. */
    fun hash(filename: String, stripUrl: Boolean = false): String =
        sha256Prefix(strip(filename, stripUrl || isUrl(filename)))

    /** Syncplay's utils.hashFilesize: what "send hashed" privacy mode sends instead of the size. */
    fun hashSize(size: Long): String = sha256Prefix(size.toString())

    /** Whether [text] has the shape of [hash] or [hashSize]'s output. */
    fun isHash(text: String): Boolean = hashRegex.matches(text)

    /** Filename without directories, for either Windows or POSIX paths. */
    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun sha256Prefix(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
}

internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
