package com.syncplaytv.syncplay

import java.net.URLDecoder
import java.security.MessageDigest

object Filenames {
    private val stripRegex = Regex("""[-~_.\[\](): ]""")

    fun isUrl(path: String): Boolean = path.contains("://")

    /** Syncplay's utils.stripfilename. */
    fun strip(filename: String, stripUrl: Boolean): String {
        var name = decode(filename)
        if (stripUrl) name = decode(name.substringAfterLast('/'))
        return name.replace(stripRegex, "")
    }

    /** Syncplay's utils.sameFilename: ignores separators/brackets and URL-encoding. */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val stripUrl = isUrl(a) xor isUrl(b)
        return strip(a, stripUrl) == strip(b, stripUrl)
    }

    /** Filename without directories, for either Windows or POSIX paths. */
    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}

internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
