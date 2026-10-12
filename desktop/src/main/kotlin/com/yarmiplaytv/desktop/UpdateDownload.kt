package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Set to the version whose installer failed when the app is started again after it. */
internal const val FAILED_INSTALL = "YARMIPLAYTV_UPDATE_FAILED"

internal val updateHttp: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

/**
 * Downloads [update]'s package to [target] and checks it against the download page's SHA-256: a package without
 * a checksum, or one that doesn't match, is refused and deleted. [progress] gets 0 to 1.
 */
internal suspend fun downloadVerified(
    update: Update,
    target: File,
    progress: (Float) -> Unit,
    http: OkHttpClient = updateHttp,
): File = withContext(Dispatchers.IO) {
    val expected = update.sha256 ?: error("the download page lists no checksum")
    val partial = File(target.path + ".part")
    try {
        http.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body!!
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var percent = -1
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != percent) progress(p / 100f).also { percent = p }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) error("the download is damaged (checksum mismatch)")
        }
        target.delete()
        if (!partial.renameTo(target)) error("couldn't save the update")
        target
    } catch (e: Throwable) {
        partial.delete()
        throw e
    }
}

/** [s] as one POSIX shell word. */
internal fun shellQuoted(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** [s] as a PowerShell string literal. */
internal fun psQuoted(s: String): String = "'" + s.replace("'", "''") + "'"

/** This process and, when [launcher] started it as a child (Windows), the launcher: what to wait for before replacing the app. */
internal fun appProcessIds(launcher: File?): List<Long> {
    val current = ProcessHandle.current()
    val parent = current.parent().orElse(null)?.takeIf { launcher != null && it.info().command().orElse("") == launcher.path }
    return listOfNotNull(current, parent).map { it.pid() }
}

/** Runs [script] with /bin/sh in the background, so it outlives this process. */
internal fun runDetachedShell(script: String, name: String) {
    val file = File(System.getProperty("java.io.tmpdir"), name).apply {
        writeText(script)
        setExecutable(true, true)
    }
    ProcessBuilder("nohup", "/bin/sh", file.path)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
