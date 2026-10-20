package com.yarmiplaytv.update

import okhttp3.Request

/**
 * A download of an installer. OkHttp would otherwise ask for gzip, and a compressed installer has no decoded
 * length, so [reportByteProgress] would stay at nothing for the whole file.
 */
fun updateDownloadRequest(url: String): Request =
    Request.Builder().url(url).header("Accept-Encoding", "identity").build()

/**
 * Reports [done] of [total] bytes, at most once per percent. An unknown length ([total] <= 0) reports null once,
 * so the notice can show an ongoing download instead of a stuck 0%. [lastPercent] starts at -1. Returns the slot
 * to pass back next time.
 */
fun reportByteProgress(done: Long, total: Long, lastPercent: Int, progress: (Float?) -> Unit): Int {
    if (total <= 0L) {
        if (lastPercent != 0) progress(null)
        return 0
    }
    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
    if (percent != lastPercent) progress(percent / 100f)
    return percent
}
