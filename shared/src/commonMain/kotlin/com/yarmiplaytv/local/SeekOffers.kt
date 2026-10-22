package com.yarmiplaytv.local

import com.yarmiplaytv.media.StoredSeekFrames
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Seek-bar frames a server stored for the file now playing, keyed by the URL the player opened. */
object SeekOffers {
    private val offers = ConcurrentHashMap<String, StoredSeekFrames>()

    fun remember(url: String, frames: StoredSeekFrames) {
        offers[url] = frames
    }

    fun of(url: String): StoredSeekFrames? = offers[url]

    /** Drop a Plex index that answered nothing, so the next hover decodes a frame instead. */
    fun drop(url: String) {
        offers.remove(url)
    }
}

/** A finished relay download, as a path on disk, so the seek bar can read it like any local file. */
object SeekLocals {
    var completeRelay: ((String) -> String?)? = null
}

/** The local path of a finished relay file, or [url] itself. */
fun previewTarget(url: String): String = SeekLocals.completeRelay?.invoke(url) ?: url

/** The bytes at [url], or null when the server has nothing there. */
fun fetchPreview(url: String): ByteArray? = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        instanceFollowRedirects = true
    }
    try {
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}.getOrNull()
