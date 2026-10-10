package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.DesktopPaths
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
 * The download page's name for this OS, which picks the package in its version.json; null in a store build,
 * which mustn't update outside its store. The Microsoft Store and AUR packages set yarmiplaytv.store; Flatpak
 * and Snap are recognised by the variables their sandboxes set.
 */
internal fun updatePlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    store: String? = System.getProperty("yarmiplaytv.store"),
    env: (String) -> String? = System::getenv,
): String? {
    if (!store.isNullOrEmpty() || listOf("FLATPAK_ID", "SNAP").any { !env(it).isNullOrEmpty() }) return null
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> "windows"
        os.startsWith("mac") || os.startsWith("darwin") -> "macos"
        else -> "linux"
    }
}

/**
 * [args] as one Windows command line, quoted the way the C runtime splits it again (CommandLineToArgvW):
 * backslashes only escape when they come before a quote.
 */
internal fun windowsCommandLine(args: List<String>): String = args.joinToString(" ") { arg ->
    if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return@joinToString arg
    buildString {
        append('"')
        var slashes = 0
        for (c in arg) {
            if (c == '\\') {
                slashes++
                continue
            }
            append("\\".repeat(if (c == '"') slashes * 2 + 1 else slashes))
            slashes = 0
            append(c)
        }
        append("\\".repeat(slashes * 2))
        append('"')
    }
}

/**
 * Installs updates with the download page's .msi. The app is installed per user, so the installer needs no
 * administrator rights and upgrades the installed version in place.
 */
internal class WindowsUpdater private constructor(
    private val launcher: File,
    /** The arguments this run started with, for starting the app again after installing. */
    private val args: List<String>,
) : UpdateInstaller {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var msi: File? = null
    private var version: String? = null

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
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val msi = msi ?: return
        fun quoted(s: String) = "'" + s.replace("'", "''") + "'"
        // The launcher runs the app in a child process and holds YarmiplayTV.exe open until it exits too.
        val current = ProcessHandle.current()
        val pids = listOfNotNull(current, current.parent().orElse(null)?.takeIf { it.info().command().orElse("") == launcher.path })
            .joinToString(",") { it.pid().toString() }
        val script = buildString {
            append("Wait-Process -Id $pids -ErrorAction SilentlyContinue; ")
            append("\$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList ('/i \"' + ${quoted(msi.path)} + '\" /qn /norestart'); ")
            append("Remove-Item -LiteralPath ${quoted(msi.path)} -ErrorAction SilentlyContinue; ")
            if (relaunch) {
                // 3010: installed, Windows wants a restart for something else.
                append("if (\$p.ExitCode -in 0, 3010) { \$env:$FAILED_INSTALL = \$null } else { \$env:$FAILED_INSTALL = ${quoted(version.orEmpty())} }; ")
                append("Start-Process -FilePath ${quoted(launcher.path)}")
                if (args.isNotEmpty()) append(" -ArgumentList ${quoted(windowsCommandLine(args))}")
            }
        }
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    companion object {
        /** Set to the version whose installer failed when the app is started again after it. */
        const val FAILED_INSTALL = "YARMIPLAYTV_UPDATE_FAILED"

        /** The installed app's launcher (YarmiplayTV.exe), which jpackage names in jpackage.app-path. */
        val installedLauncher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

        /**
         * Only for the installed app on Windows. The portable app only points to the download page: the .msi
         * would install a second copy next to it.
         */
        fun createOrNull(args: List<String> = emptyList()): WindowsUpdater? {
            if (updatePlatform() != "windows" || DesktopPaths.portableDir() != null) return null
            return WindowsUpdater(installedLauncher ?: return null, args)
        }
    }
}
