package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateFailedException
import com.yarmiplaytv.update.UpdateFailure
import com.yarmiplaytv.update.formatUpdateFailure
import com.yarmiplaytv.update.mergeUpdateFailures
import com.yarmiplaytv.update.parseUpdateFailure
import com.yarmiplaytv.update.parseUpdateFailureFile
import com.yarmiplaytv.update.redactUpdateText
import com.yarmiplaytv.update.reportByteProgress
import com.yarmiplaytv.update.updateDownloadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
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
    val stage: String = "install",
)

/** Parses [raw] from [FAILED_INSTALL] or a failure file; null when blank. */
internal fun parseFailedInstall(raw: String?): FailedInstallSignal? {
    val failure = parseUpdateFailure(raw) ?: return null
    val version = failure.version ?: return null
    return FailedInstallSignal(version, failure.exitCode, failure.detail, failure.stage)
}

/** Builds the [FAILED_INSTALL] value a relaunching updater writes. */
internal fun formatFailedInstall(version: String, exitCode: Int? = null, detail: String? = null, stage: String = "install"): String {
    if (exitCode == null && detail.isNullOrBlank() && stage == "install") return version
    return formatUpdateFailure(UpdateFailure(version, stage, exitCode, detail))
}

/**
 * Where a helper writes the failure the next launch reads, even if it could not start the app again.
 * Windows: `%LOCALAPPDATA%\YarmiplayTV\update-failure.txt`. macOS and Linux match the app's config folder.
 */
internal fun updateFailureFile(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home").orEmpty(),
): File {
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> {
            val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: File(home, "AppData${File.separator}Local").path
            File(local, "YarmiplayTV${File.separator}update-failure.txt")
        }
        os.startsWith("mac") || os.startsWith("darwin") ->
            File(home, "Library/Application Support/YarmiplayTV/update-failure.txt")
        else -> {
            val base = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path
            File(base, "yarmiplaytv${File.separator}update-failure.txt")
        }
    }
}

internal fun formatFailureFile(failure: UpdateFailure): String = buildString {
    append("version=").append(failure.version.orEmpty()).append('\n')
    append("stage=").append(failure.stage.ifBlank { "install" }).append('\n')
    append("exit=").append(failure.exitCode?.toString().orEmpty()).append('\n')
    append("detail=").append(failure.detail?.let(::redactUpdateText).orEmpty()).append('\n')
}

/** The note to leave when the helper process itself cannot be started. The detail is whatever the system reported. */
internal fun failureFromHelperStart(version: String, error: Throwable): UpdateFailure {
    val failed = error as? UpdateFailedException
    return UpdateFailure(
        version = version,
        stage = failed?.stage ?: "launch",
        exitCode = failed?.exitCode,
        detail = (failed?.detail ?: error.message)?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() },
    )
}

internal fun writeUpdateFailure(failure: UpdateFailure, file: File = updateFailureFile()) {
    file.parentFile?.mkdirs()
    file.writeText(formatFailureFile(failure))
}

internal fun readUpdateFailureFile(file: File = updateFailureFile()): UpdateFailure? =
    if (file.isFile) parseUpdateFailureFile(file.readText()) else null

/**
 * The note to show on this start: the helper's file, the relaunch environment, and on Windows the setup's
 * own status line when that is the only text we have. Does not delete anything; [clearPendingUpdateFailure] does,
 * once the notice has taken the text.
 */
internal fun loadPendingUpdateFailure(env: String?, failureFile: File = updateFailureFile(), statusFile: File = updateStatusFile()): String? {
    val fromEnv = parseUpdateFailure(env)
    val fromFile = runCatching { readUpdateFailureFile(failureFile) }.getOrNull()
    val status = runCatching {
        if (statusFile.isFile) statusFile.readText().trim().ifEmpty { null } else null
    }.getOrNull()
    return mergeUpdateFailures(fromFile, fromEnv, status)?.let(::formatUpdateFailure)?.ifBlank { null }
}

internal fun clearPendingUpdateFailure(failureFile: File = updateFailureFile()) {
    runCatching { failureFile.delete() }
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        runCatching { writeUpdateStatus(null) }
    }
}

/** Shell lines that write or remove `$failfile` from `$ok`, `$ver`, `$stage`, `$code` and `$detail`. */
internal fun shRecordFailure(): String = """
    detail_one=${'$'}(printf '%s' "${'$'}detail" | awk 'NR==1{printf "%s", ${'$'}0; next} {printf " %s", ${'$'}0}')
    detail_one=${'$'}(printf '%s' "${'$'}detail_one" | tr '|' '/')
    if [ "${'$'}ok" = 1 ]; then
      rm -f "${'$'}failfile"
    else
      mkdir -p "${'$'}(dirname "${'$'}failfile")"
      {
        printf 'version=%s\n' "${'$'}ver"
        printf 'stage=%s\n' "${'$'}stage"
        printf 'exit=%s\n' "${'$'}code"
        printf 'detail=%s\n' "${'$'}detail_one"
      } > "${'$'}failfile"
    fi
""".trimIndent() + "\n"

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
    val name = update.downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank { "the file" }
    val expected = update.sha256 ?: throw UpdateFailedException("validate", "the download page lists no checksum for $name")
    val partial = File(target.path + ".part")
    try {
        http.newCall(updateDownloadRequest(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateFailedException("download", "the server answered HTTP ${response.code} for $name")
            }
            val body = response.body ?: throw UpdateFailedException("download", "the server sent an empty response for $name")
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
            if (!actual.equals(expected, ignoreCase = true)) {
                throw UpdateFailedException("validate", "checksum mismatch (expected $expected, got $actual)")
            }
        }
        target.delete()
        if (!partial.renameTo(target)) {
            throw UpdateFailedException("unpack", "couldn't move the download into place (${partial.path} -> ${target.path})")
        }
        target
    } catch (e: Throwable) {
        partial.delete()
        when (e) {
            is CancellationException, is UpdateFailedException -> throw e
            else -> throw UpdateFailedException("download", e.message?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() })
        }
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

/**
 * Runs [script] with /bin/sh in the background, so it outlives this process.
 * Starting the shell is not a finished update: if the shell cannot be started, this throws.
 */
internal fun runDetachedShell(script: String, name: String) {
    val file = File(System.getProperty("java.io.tmpdir"), name).apply {
        writeText(script)
        setExecutable(true, true)
    }
    try {
        ProcessBuilder("nohup", "/bin/sh", file.path)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (e: IOException) {
        throw UpdateFailedException("launch", e.message?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() } ?: "couldn't start the update helper")
    }
}
