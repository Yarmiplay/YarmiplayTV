package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.reportByteProgress
import com.yarmiplaytv.update.updateDownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Set to the version whose installer failed when the app is started again after it. */
internal const val FAILED_INSTALL = "YARMIPLAYTV_UPDATE_FAILED"

/**
 * What [FAILED_INSTALL] carries after a relaunch.
 * New writers use `version|exit=<code>|detail`; older clients wrote only `version`.
 */
internal data class FailedInstallSignal(
    val version: String,
    val exitCode: Int? = null,
    val detail: String? = null,
)

/** Parses [raw] from [FAILED_INSTALL]; null when blank. */
internal fun parseFailedInstall(raw: String?): FailedInstallSignal? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split('|', limit = 3)
    val version = parts[0].trim().ifEmpty { return null }
    var exit: Int? = null
    var detail: String? = null
    if (parts.size >= 2) {
        val second = parts[1].trim()
        exit = second.removePrefix("exit=").trim().toIntOrNull()
            ?: second.toIntOrNull()
        if (parts.size >= 3) detail = parts[2].trim().ifEmpty { null }
        else if (exit == null && second.isNotEmpty() && !second.startsWith("exit=")) detail = second
    }
    return FailedInstallSignal(version, exit, detail)
}

/** Builds the [FAILED_INSTALL] value a relaunching updater writes. */
internal fun formatFailedInstall(version: String, exitCode: Int? = null, detail: String? = null): String {
    if (exitCode == null && detail.isNullOrBlank()) return version
    val cleaned = detail?.replace(Regex("[\\r\\n]+"), " ")?.trim().orEmpty()
    return buildString {
        append(version)
        append("|exit=")
        append(exitCode ?: -1)
        if (cleaned.isNotEmpty()) {
            append('|')
            append(cleaned)
        }
    }
}

/**
 * Where the silent Windows setup writes its last error for the updater to pick up after a non-zero exit.
 * `%LOCALAPPDATA%\YarmiplayTV\update-status.txt`.
 */
internal fun updateStatusFile(): File {
    val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), "AppData${File.separator}Local").path
    return File(local, "YarmiplayTV${File.separator}update-status.txt")
}

internal fun writeUpdateStatus(message: String?) {
    val file = updateStatusFile()
    if (message == null) {
        file.delete()
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(message.trim())
}

internal val updateHttp: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

/**
 * Downloads [update]'s package to [target] and checks it against the download page's SHA-256: a package without
 * a checksum, or one that doesn't match, is refused and deleted. [progress] gets 0 to 1, or null when the
 * length isn't known.
 */
internal suspend fun downloadVerified(
    update: Update,
    target: File,
    progress: (Float?) -> Unit,
    http: OkHttpClient = updateHttp,
): File = withContext(Dispatchers.IO) {
    val expected = update.sha256 ?: error("the download page lists no checksum")
    val partial = File(target.path + ".part")
    try {
        http.newCall(updateDownloadRequest(update.downloadUrl)).execute().use { response ->
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
                        percent = reportByteProgress(done, total, percent, progress)
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

/**
 * This process and related YarmiplayTV launchers/JVMs to wait for before replacing files.
 * Matches by exact launcher path (case-insensitive) or a command that ends with YarmiplayTV(.exe), so
 * OneDrive / short-path / casing mismatches still include the parent launcher.
 */
internal fun appProcessIds(launcher: File?): List<Long> {
    val current = ProcessHandle.current()
    val launcherPath = launcher?.absoluteFile?.path
    val related = ProcessHandle.allProcesses()
        .map { it.pid() to it.info().command().orElse("") }
        .filter { (pid, cmd) ->
            if (pid == current.pid()) return@filter true
            if (cmd.isEmpty()) return@filter false
            if (launcherPath != null && cmd.equals(launcherPath, ignoreCase = true)) return@filter true
            if (launcher != null && cmd.equals(launcher.path, ignoreCase = true)) return@filter true
            val name = cmd.substringAfterLast('\\').substringAfterLast('/')
            name.equals("YarmiplayTV.exe", ignoreCase = true) || name.equals("YarmiplayTV", ignoreCase = true)
        }
        .map { it.first }
        .toList()
    return (listOf(current.pid()) + related).distinct()
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
