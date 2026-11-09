package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateFailedException
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Splits a Windows command line the way CommandLineToArgvW does, so tests can show a path with spaces
 * survives [windowsCommandLine]. Not used to launch anything.
 */
internal fun splitWindowsCommandLine(command: String): List<String> {
    val args = mutableListOf<String>()
    var i = 0
    val n = command.length
    while (i < n) {
        while (i < n && (command[i] == ' ' || command[i] == '\t')) i++
        if (i >= n) break
        val arg = StringBuilder()
        var quoted = false
        while (i < n) {
            if (command[i] == '\\') {
                var slashes = 0
                while (i < n && command[i] == '\\') {
                    slashes++
                    i++
                }
                if (i < n && command[i] == '"') {
                    arg.append("\\".repeat(slashes / 2))
                    if (slashes % 2 == 1) {
                        arg.append('"')
                        i++
                    } else {
                        quoted = !quoted
                        i++
                    }
                } else {
                    arg.append("\\".repeat(slashes))
                }
                continue
            }
            if (command[i] == '"') {
                quoted = !quoted
                i++
                continue
            }
            if (!quoted && (command[i] == ' ' || command[i] == '\t')) break
            arg.append(command[i])
            i++
        }
        args += arg.toString()
    }
    return args
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

/** UTF-16LE base64 for `powershell.exe -EncodedCommand`. The command line then holds no path. */
internal fun psEncodedCommand(script: String): String =
    Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

/**
 * Runs [script] in a hidden PowerShell that outlives this process.
 * A started PowerShell is not a finished update. If PowerShell itself cannot start, this throws.
 */
internal fun runDetachedPowerShell(script: String) {
    val encoded = psEncodedCommand(script)
    try {
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (e: IOException) {
        throw UpdateFailedException("launch", e.message?.takeIf { it.isNotBlank() } ?: "couldn't start PowerShell")
    }
}

/**
 * `$fail` under LocalApplicationData, which [updateFailureFile] reads on the next launch.
 * The path is built in PowerShell so a profile like `Dor Farhi` stays one path.
 */
internal fun psFailureFile(): String = buildString {
    append("\$base = [Environment]::GetFolderPath('LocalApplicationData'); ")
    append("if (-not \$base) { \$base = Join-Path \$env:USERPROFILE 'AppData\\Local' }; ")
    append("\$fail = Join-Path \$base 'YarmiplayTV\\update-failure.txt'; ")
}

/**
 * Writes `$fail` when `$ok` is false, from `$stage`, `$code` and `$detail`, and deletes it when the install worked.
 * Callers must set those variables from the installer's own result, not from the fact that a process was created.
 */
internal fun psWriteOutcome(version: String): String = buildString {
    append(psFailureFile())
    append("if (\$ok) { ")
    append("Remove-Item -LiteralPath \$fail -Force -ErrorAction SilentlyContinue ")
    append("} else { ")
    append("if (-not \$stage) { \$stage = 'install' }; ")
    append("if (\$null -eq \$code) { \$code = -1 }; ")
    append("\$clean = (([string]\$detail) -replace '[\\r\\n|]+',' ').Trim(); ")
    append("\$parent = Split-Path -Parent \$fail; ")
    append("if (-not (Test-Path -LiteralPath \$parent)) { New-Item -ItemType Directory -Force -Path \$parent | Out-Null }; ")
    append("Set-Content -LiteralPath \$fail -Encoding utf8 -Value @(")
    append("('version=' + ${psQuoted(version)}), ")
    append("('stage=' + \$stage), ")
    append("('exit=' + \$code), ")
    append("('detail=' + \$clean)); ")
    append("}; ")
}

/**
 * Saves the outcome, then starts [launcher] with `UseShellExecute = $false`, so a path or argument
 * containing spaces stays one argument. `$ok` must already reflect the installer exit.
 * If the app itself cannot be started after a successful install, that is recorded as a restart failure.
 */
internal fun psRelaunch(launcher: File, args: List<String>, version: String): String = buildString {
    append(psWriteOutcome(version))
    append("if (\$ok) { \$env:$FAILED_INSTALL = \$null } else { ")
    append("if (\$null -eq \$code) { \$code = -1 }; ")
    append("if (-not \$stage) { \$stage = 'install' }; ")
    append("\$clean = (([string]\$detail) -replace '[\\r\\n|]+',' ').Trim(); ")
    append("\$sig = ${psQuoted(version)} + '|stage=' + \$stage + '|exit=' + \$code; ")
    append("if (\$clean) { \$sig = \$sig + '|' + \$clean }; ")
    append("\$env:$FAILED_INSTALL = \$sig; ")
    append("}; ")
    append("try { ")
    append(psNewProcess(launcher.path, if (args.isEmpty()) null else psQuoted(windowsCommandLine(args))))
    append("\$null = [System.Diagnostics.Process]::Start(\$psi); ")
    append(" } catch { ")
    append("if (\$ok) { \$ok = \$false; \$stage = 'restart'; \$code = -3; \$detail = \$_.Exception.Message; ")
    append(psWriteOutcome(version))
    append("} }; ")
}

/**
 * Starts [file] with [argumentsExpr] (a PowerShell expression for one Windows command line) and waits.
 *
 * Windows PowerShell 5.1 `Start-Process -ArgumentList` joins an array with spaces and drops quotes.
 * `UseShellExecute = $true` (`ShellExecuteEx`) does the same to a quoted argument string. Either one
 * splits `C:\Users\Dor Farhi\AppData\Local\YarmiplayTV` into `C:\Users\Dor` plus a leftover token, and the
 * installer then tries to copy `app\.jpackage.xml` into `C:\Users\Dor`.
 *
 * The process is started with `UseShellExecute = $false`, so `FileName` (spaces allowed) and `Arguments`
 * (already quoted for CommandLineToArgvW) reach `CreateProcess` unchanged. A folder that cannot be written
 * is installed elevated: the same command line is inside an encoded PowerShell script, and the elevated
 * `powershell.exe` argument list itself has no path, so ShellExecute has nothing to split.
 * `$ok` is not set here. The caller sets it from `$code`.
 */
internal fun psStartWait(file: String, argumentsExpr: String, elevateIfNeededDir: String? = null): String = buildString {
    append("\$p = \$null; \$stage = 'launch'; \$code = -1; \$detail = ''; \$ok = \$false; ")
    append("try { ")
    append(psNewProcess(file, argumentsExpr))
    if (!elevateIfNeededDir.isNullOrBlank()) append(psElevateIfUnwritable(elevateIfNeededDir, file, argumentsExpr))
    append("\$p = [System.Diagnostics.Process]::Start(\$psi); ")
    append("if (\$null -eq \$p) { \$code = -2; \$detail = 'the installer returned no process' } ")
    append("else { \$p.WaitForExit(); ")
    append("if (\$null -eq \$p.ExitCode) { \$code = -2; \$detail = 'the installer returned no exit code' } ")
    append("else { \$code = \$p.ExitCode; ")
    append("if (\$code -eq 1223 -and -not \$detail) { \$detail = 'the permission prompt was declined' } } } ")
    append("} catch { \$detail = \$_.Exception.Message; \$code = -3 }; ")
}

/**
 * `$psi` for [file] and [argumentsExpr], without a shell. [argumentsExpr] is one command line already
 * quoted the way CommandLineToArgvW splits it, or a PowerShell expression that produces that line.
 */
private fun psNewProcess(file: String, argumentsExpr: String?): String = buildString {
    append("\$psi = New-Object System.Diagnostics.ProcessStartInfo; ")
    append("\$psi.FileName = ${psQuoted(file)}; ")
    if (argumentsExpr != null) append("\$psi.Arguments = $argumentsExpr; ")
    append("\$psi.UseShellExecute = \$false; ")
}

/**
 * When [dir] (or the nearest existing parent, if the folder is not there yet) cannot be written, replace
 * `$psi` with an elevated PowerShell that runs the same [file] and [argumentsExpr] and waits.
 * The elevated argument list is only `-EncodedCommand` plus base64, so a profile path with spaces is not
 * on that command line.
 */
private fun psElevateIfUnwritable(dir: String, file: String, argumentsExpr: String): String {
    val encoded = psEncodedCommand(buildString {
        append("\$ErrorActionPreference = 'Stop'; ")
        append(psNewProcess(file, argumentsExpr))
        append("\$p = [System.Diagnostics.Process]::Start(\$psi); ")
        append("if (\$null -eq \$p) { exit 2 }; ")
        append("\$p.WaitForExit(); ")
        append("if (\$null -eq \$p.ExitCode) { exit 2 }; ")
        append("exit \$p.ExitCode")
    })
    val quotedDir = psQuoted(dir)
    return buildString {
        append("\$probeDir = $quotedDir; ")
        append("while (\$probeDir -and -not (Test-Path -LiteralPath \$probeDir)) { ")
        append("\$parent = Split-Path -Parent \$probeDir; ")
        append("if (-not \$parent -or \$parent -eq \$probeDir) { break }; ")
        append("\$probeDir = \$parent }; ")
        append("\$needAdmin = \$false; ")
        append("if (\$probeDir -and (Test-Path -LiteralPath \$probeDir)) { ")
        append("try { ")
        append("\$probe = Join-Path \$probeDir ('.yptv-write-' + [guid]::NewGuid().ToString('n')); ")
        append("[IO.File]::WriteAllText(\$probe, ''); ")
        append("Remove-Item -LiteralPath \$probe -Force -ErrorAction SilentlyContinue ")
        append("} catch { \$needAdmin = \$true } ")
        append("}; ")
        append("if (\$needAdmin) { ")
        append("\$psi = New-Object System.Diagnostics.ProcessStartInfo; ")
        append("\$psi.FileName = 'powershell.exe'; ")
        append("\$psi.Arguments = ${psQuoted("-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded")}; ")
        append("\$psi.UseShellExecute = \$true; ")
        append("\$psi.Verb = 'runas'; ")
        append("\$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden; ")
        append("}; ")
    }
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
        val pkg = packageFile ?: throw UpdateFailedException("launch", "the downloaded installer is missing")
        runDetachedPowerShell(
            if (exeSetup) exeScript(pkg, appProcessIds(launcher), relaunch)
            else msiScript(pkg, appProcessIds(launcher), relaunch),
        )
    }

    /**
     * Branded setup.exe: `--silent` still shows the progress card, `--dir` is this install's folder.
     * `--dir` is one quoted command-line argument, including when the folder is `C:\Users\Dor Farhi\...`.
     * Relaunch is [psRelaunch], after the installer exits, so the new copy is what starts.
     */
    internal fun exeScript(
        exe: File,
        pids: List<Long>,
        relaunch: Boolean,
        home: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): String = buildString {
        val targetDir = resolveWindowsInstallDir(launcher, home = home, env = env)
        val relaunchExe = if (isSafeWindowsInstallDir(parentDirOf(launcher), home, env)) launcher else File(targetDir, "YarmiplayTV.exe")
        val setupArgs = windowsCommandLine(listOf("--silent", "--dir", targetDir.path))
        val status = updateStatusFile().path
        append(psWaitForAppExit(pids))
        append("Unblock-File -LiteralPath ${psQuoted(exe.path)} -ErrorAction SilentlyContinue; ")
        append("Remove-Item -LiteralPath ${psQuoted(status)} -Force -ErrorAction SilentlyContinue; ")
        append(psStartWait(exe.path, psQuoted(setupArgs), elevateIfNeededDir = targetDir.path))
        append("if (\$code -in -2, -3) { \$stage = 'launch' } else { \$stage = 'install' }; ")
        // 3010: installed, Windows wants a restart for something else. Anything else is not success.
        append("\$ok = \$code -in 0, 3010; ")
        append("if (-not \$ok -and (Test-Path -LiteralPath ${psQuoted(status)})) { ")
        append("\$fromSetup = ((Get-Content -LiteralPath ${psQuoted(status)} -Encoding UTF8 -Raw -ErrorAction SilentlyContinue) + '').Trim(); ")
        append("if (\$fromSetup) { \$detail = \$fromSetup } ")
        append("}; ")
        append("if (-not \$ok -and -not \$detail) { \$detail = \"exit code \$code\" }; ")
        append("Remove-Item -LiteralPath ${psQuoted(exe.path)} -ErrorAction SilentlyContinue; ")
        append(if (relaunch) psRelaunch(relaunchExe, args, version.orEmpty()) else psWriteOutcome(version.orEmpty()))
    }

    internal fun msiScript(msi: File, pids: List<Long>, relaunch: Boolean): String = buildString {
        // The launcher runs the app in a child process and holds YarmiplayTV.exe open until it exits too.
        append(psWaitForAppExit(pids))
        append("Unblock-File -LiteralPath ${psQuoted(msi.path)} -ErrorAction SilentlyContinue; ")
        append("\$log = Join-Path \$env:TEMP 'YarmiplayTV-msiexec.log'; ")
        append("Remove-Item -LiteralPath \$log -Force -ErrorAction SilentlyContinue; ")
        val arguments = "('/i \"' + ${psQuoted(msi.path)} + '\" /passive /norestart /le \"' + \$log + '\"')"
        append(psStartWait("msiexec.exe", arguments))
        append("if (\$code -in -2, -3) { \$stage = 'launch' } else { \$stage = 'install' }; ")
        append("\$ok = \$code -in 0, 3010; ")
        append("if (-not \$ok -and -not \$detail -and (Test-Path -LiteralPath \$log)) { ")
        append("\$line = @(Get-Content -LiteralPath \$log -ErrorAction SilentlyContinue | Where-Object { \$_.Trim() }) | Select-Object -Last 1; ")
        append("if (\$line) { \$detail = ([string]\$line).Trim() } ")
        append("}; ")
        append("if (-not \$ok -and -not \$detail) { \$detail = \"exit code \$code\" }; ")
        append("Remove-Item -LiteralPath ${psQuoted(msi.path)} -ErrorAction SilentlyContinue; ")
        append(if (relaunch) psRelaunch(launcher, args, version.orEmpty()) else psWriteOutcome(version.orEmpty()))
    }

    /** @deprecated Use [msiScript] / [exeScript]. Kept for existing tests. */
    internal fun script(msi: File, pids: List<Long>, relaunch: Boolean): String = msiScript(msi, pids, relaunch)
}
