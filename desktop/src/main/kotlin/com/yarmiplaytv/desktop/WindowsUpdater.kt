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

/**
 * PowerShell that starts [launcher] with [args], with [FAILED_INSTALL] cleared when `$ok` is true.
 * When the install failed, callers should set `$code` (int) and optional `$detail` (string) first so the
 * relaunched app can show the real reason; older scripts that only flip `$ok` still work (exit=-1).
 */
internal fun psRelaunch(launcher: File, args: List<String>, version: String): String = buildString {
    append("if (\$ok) { \$env:$FAILED_INSTALL = \$null } else { ")
    append("if (\$null -eq \$code) { \$code = -1 }; ")
    append("if (\$null -eq \$detail) { \$detail = '' }; ")
    append("\$sig = ${psQuoted(version)} + '|exit=' + \$code; ")
    append("if (\$detail) { \$sig = \$sig + '|' + ([string]\$detail -replace '[\\r\\n]+',' ').Trim() }; ")
    append("\$env:$FAILED_INSTALL = \$sig")
    append(" }; ")
    append("Start-Process -FilePath ${psQuoted(launcher.path)}")
    if (args.isNotEmpty()) append(" -ArgumentList ${psQuoted(windowsCommandLine(args))}")
}

/** Waits for known PIDs, then any remaining YarmiplayTV process, then a short settle for file locks. */
internal fun psWaitForAppExit(pids: List<Long>): String = buildString {
    if (pids.isNotEmpty()) {
        append("Wait-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue; ")
    }
    append("Get-Process -Name 'YarmiplayTV' -ErrorAction SilentlyContinue | Wait-Process -ErrorAction SilentlyContinue; ")
    append("Start-Sleep -Seconds 2; ")
}

/**
 * Installs updates from the download page's Windows package.
 *
 * The branded setup.exe (version.json `setup`) is installed with `--silent --dir` into this copy's folder,
 * showing the same progress card as the download. `file` stays the `.msi` so 2.6.x and 2.7.0, which only
 * know `msiexec`, keep updating. This process starts the app again after a successful install.
 */
internal class WindowsUpdater(
    private val launcher: File,
    /** The arguments this run started with, for starting the app again after installing. */
    private val args: List<String>,
) : UpdateInstaller {
    private var packageFile: File? = null
    private var version: String? = null
    private var exeSetup = false

    override suspend fun download(update: Update, progress: (Float?) -> Unit) {
        val setup = update.setupUrl
        val setupSha = update.setupSha256
        val branded = !setup.isNullOrBlank() && !setupSha.isNullOrBlank()
        exeSetup = branded || update.downloadUrl.substringAfterLast('/').substringBefore('?').endsWith(".exe", ignoreCase = true)
        val packageUpdate = if (branded) update.copy(downloadUrl = setup!!, sha256 = setupSha) else update
        val destName = if (exeSetup) "YarmiplayTV-${update.version}-setup.exe" else "YarmiplayTV-${update.version}.msi"
        packageFile = downloadVerified(packageUpdate, File(System.getProperty("java.io.tmpdir"), destName), progress)
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val pkg = packageFile ?: return
        runDetachedPowerShell(
            if (exeSetup) exeScript(pkg, appProcessIds(launcher), relaunch)
            else msiScript(pkg, appProcessIds(launcher), relaunch),
        )
    }

    /**
     * Branded setup.exe: `--silent` still shows the progress card, `--dir` is this install's folder.
     * Arguments are a PowerShell array so a path with spaces stays one argument. Relaunch is [psRelaunch],
     * after the installer exits, so the new copy is what starts.
     */
    internal fun exeScript(exe: File, pids: List<Long>, relaunch: Boolean): String = buildString {
        val setupArgs = listOf("--silent", "--dir", launcher.parentFile?.path.orEmpty())
        val status = updateStatusFile().path
        append(psWaitForAppExit(pids))
        append("Unblock-File -LiteralPath ${psQuoted(exe.path)} -ErrorAction SilentlyContinue; ")
        append("Remove-Item -LiteralPath ${psQuoted(status)} -Force -ErrorAction SilentlyContinue; ")
        append("\$p = \$null; \$code = -1; \$detail = ''; ")
        append("try { ")
        append("\$p = Start-Process -FilePath ${psQuoted(exe.path)} -Wait -PassThru")
        append(" -ArgumentList ${setupArgs.joinToString(",") { psQuoted(it) }}")
        append(" } catch { \$detail = \$_.Exception.Message; \$code = -3 }; ")
        append("if (\$null -ne \$p) { if (\$null -eq \$p.ExitCode) { \$code = -2 } else { \$code = \$p.ExitCode } }; ")
        append("\$ok = \$code -in 0, 3010; ")
        append("if (-not \$ok -and (Test-Path -LiteralPath ${psQuoted(status)})) { ")
        append("\$detail = ((Get-Content -LiteralPath ${psQuoted(status)} -Raw -ErrorAction SilentlyContinue) + '').Trim() ")
        append("}; ")
        append("if (-not \$ok -and -not \$detail) { ")
        append("if (\$code -eq -2) { \$detail = 'Windows blocked or elevated the installer (no exit code)' } ")
        append("elseif (\$code -eq -3) { } ")
        append("elseif (\$code -eq -1) { \$detail = 'the installer did not start' } ")
        append("else { \$detail = \"installer exit code \$code\" } ")
        append("}; ")
        append("Remove-Item -LiteralPath ${psQuoted(exe.path)} -ErrorAction SilentlyContinue; ")
        if (relaunch) append(psRelaunch(launcher, args, version.orEmpty()))
    }

    internal fun msiScript(msi: File, pids: List<Long>, relaunch: Boolean): String = buildString {
        // The launcher runs the app in a child process and holds YarmiplayTV.exe open until it exits too.
        val status = updateStatusFile().path
        append(psWaitForAppExit(pids))
        append("Unblock-File -LiteralPath ${psQuoted(msi.path)} -ErrorAction SilentlyContinue; ")
        append("Remove-Item -LiteralPath ${psQuoted(status)} -Force -ErrorAction SilentlyContinue; ")
        append("\$p = \$null; \$code = -1; \$detail = ''; ")
        append("try { ")
        append("\$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList ('/i \"' + ${psQuoted(msi.path)} + '\" /passive /norestart') ")
        append("} catch { \$detail = \$_.Exception.Message; \$code = -3 }; ")
        append("if (\$null -ne \$p) { if (\$null -eq \$p.ExitCode) { \$code = -2 } else { \$code = \$p.ExitCode } }; ")
        // 3010: installed, Windows wants a restart for something else.
        append("\$ok = \$code -in 0, 3010; ")
        append("if (-not \$ok -and -not \$detail) { \$detail = \"msiexec exit code \$code\" }; ")
        append("Remove-Item -LiteralPath ${psQuoted(msi.path)} -ErrorAction SilentlyContinue; ")
        if (relaunch) append(psRelaunch(launcher, args, version.orEmpty()))
    }

    /** @deprecated Use [msiScript] / [exeScript]. Kept for existing tests. */
    internal fun script(msi: File, pids: List<Long>, relaunch: Boolean): String = msiScript(msi, pids, relaunch)
}
