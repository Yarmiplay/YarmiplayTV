package com.yarmiplaytv

import java.io.File
import java.io.IOException

/**
 * What another app may ask MainActivity to play. Any app can send it a VIEW intent or a `url` extra, so a file
 * inside this app's own storage (settings with room and server passwords, caches) is never opened from one, and
 * an address in the extra or in a VIEW intent is played only when it is a network stream.
 */
internal object IntentMedia {
    private val STREAM_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "rtmps")

    /** True for an http(s), rtsp or rtmp(s) address; anything else (mpv's own protocols, local paths) is refused. */
    fun isStreamUrl(url: String): Boolean {
        val end = url.indexOf("://")
        if (end <= 0) return false
        return url.substring(0, end).lowercase() in STREAM_SCHEMES
    }

    /**
     * True when the file at [path] is inside one of [privateDirs] (or the process's own /proc entries), following
     * `..` and symbolic links (/data/data is a link to /data/user/0). A path that can't be resolved counts as private.
     */
    fun isPrivateFile(path: String?, privateDirs: List<String>): Boolean {
        if (path.isNullOrBlank()) return true
        val target = canonical(path) ?: return true
        val roots = (privateDirs + "/proc").mapNotNull { canonical(it) }
        return roots.any { root -> target == root || target.startsWith(root.trimEnd(File.separatorChar) + File.separator) }
    }

    private fun canonical(path: String): String? = try {
        File(path).canonicalPath
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
