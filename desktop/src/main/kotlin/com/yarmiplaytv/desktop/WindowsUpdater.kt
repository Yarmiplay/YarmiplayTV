package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File
import java.util.Base64

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

/** Runs [script] in a hidden PowerShell that outlives this process. */
internal fun runDetachedPowerShell(script: String) {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

/** PowerShell that starts [launcher] with [args], with [FAILED_INSTALL] set to [version] unless `$ok` is true. */
internal fun psRelaunch(launcher: File, args: List<String>, version: String): String = buildString {
    append("if (\$ok) { \$env:$FAILED_INSTALL = \$null } else { \$env:$FAILED_INSTALL = ${psQuoted(version)} }; ")
    append("Start-Process -FilePath ${psQuoted(launcher.path)}")
    if (args.isNotEmpty()) append(" -ArgumentList ${psQuoted(windowsCommandLine(args))}")
}

/**
 * Installs updates with the download page's .msi. The app is installed per user, so the installer needs no
 * administrator rights and upgrades the installed version in place. The installer shows its own progress bar
 * (`/passive`) after this process has exited, because it replaces the running app.
 */
internal class WindowsUpdater(
    private val launcher: File,
    /** The arguments this run started with, for starting the app again after installing. */
    private val args: List<String>,
) : UpdateInstaller {
    private var msi: File? = null
    private var version: String? = null

    override suspend fun download(update: Update, progress: (Float?) -> Unit) {
        msi = downloadVerified(update, File(System.getProperty("java.io.tmpdir"), "YarmiplayTV-${update.version}.msi"), progress)
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val msi = msi ?: return
        runDetachedPowerShell(script(msi, appProcessIds(launcher), relaunch))
    }

    internal fun script(msi: File, pids: List<Long>, relaunch: Boolean): String = buildString {
        // The launcher runs the app in a child process and holds YarmiplayTV.exe open until it exits too.
        append("Wait-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue; ")
        append("\$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList ('/i \"' + ${psQuoted(msi.path)} + '\" /passive /norestart'); ")
        append("Remove-Item -LiteralPath ${psQuoted(msi.path)} -ErrorAction SilentlyContinue; ")
        if (relaunch) {
            // 3010: installed, Windows wants a restart for something else.
            append("\$ok = \$p.ExitCode -in 0, 3010; ")
            append(psRelaunch(launcher, args, version.orEmpty()))
        }
    }
}
