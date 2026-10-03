package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The download page's name for this OS, which picks the package in its version.json; null in a store build
 * (the Microsoft Store package sets yarmiplaytv.store), which mustn't update outside its store.
 */
internal fun updatePlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    store: String? = System.getProperty("yarmiplaytv.store"),
): String? {
    if (!store.isNullOrEmpty()) return null
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> "windows"
        os.startsWith("mac") || os.startsWith("darwin") -> "macos"
        else -> "linux"
    }
}

/**
 * Installs updates with the download page's .msi. The app is installed per user, so the installer needs no
 * administrator rights and upgrades the installed version in place.
 */
internal class WindowsUpdater private constructor(private val launcher: File) : UpdateInstaller {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var msi: File? = null

    override suspend fun download(update: Update, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val expected = update.sha256 ?: error("the download page lists no checksum")
        val target = File(System.getProperty("java.io.tmpdir"), "YarmiplayTV-${update.version}.msi")
        val partial = File(target.path + ".part")
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
            if (!actual.equals(expected, ignoreCase = true)) {
                partial.delete()
                error("the download is damaged (checksum mismatch)")
            }
        }
        target.delete()
        if (!partial.renameTo(target)) error("couldn't save the installer")
        msi = target
    }

    override fun installAfterExit(relaunch: Boolean) {
        val msi = msi ?: return
        fun quoted(f: File) = "'" + f.path.replace("'", "''") + "'"
        // The launcher runs the app in a child process and holds YarmiplayTV.exe open until it exits too.
        val current = ProcessHandle.current()
        val pids = listOfNotNull(current, current.parent().orElse(null)?.takeIf { it.info().command().orElse("") == launcher.path })
            .joinToString(",") { it.pid().toString() }
        val script = buildString {
            append("Wait-Process -Id $pids -ErrorAction SilentlyContinue; ")
            append("\$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList ('/i \"' + ${quoted(msi)} + '\" /passive /norestart'); ")
            append("Remove-Item -LiteralPath ${quoted(msi)} -ErrorAction SilentlyContinue; ")
            if (relaunch) append("if (\$p.ExitCode -eq 0) { Start-Process -FilePath ${quoted(launcher)} }")
        }
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    companion object {
        /** Only for the installed app on Windows; its jpackage launcher sets jpackage.app-path. */
        fun createOrNull(): WindowsUpdater? {
            if (updatePlatform() != "windows") return null
            val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile } ?: return null
            return WindowsUpdater(launcher)
        }
    }
}
