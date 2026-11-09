package com.yarmiplaytv.desktop

import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.UIManager
import kotlin.system.exitProcess

/** Video types the Windows installer offers to open, the same set the old installer registered. */
internal val windowsVideoExtensions = listOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "wmv", "flv", "mpg", "ogv")

/** Magic at the end of setup.exe, after the zip length. The stub in packaging/windows/SetupStub.cs reads the same bytes. */
internal const val SETUP_PAYLOAD_MAGIC = "YPTVZIP1"

internal data class SetupChoices(
    val directory: File,
    val desktopShortcut: Boolean,
    val startMenuShortcut: Boolean,
    val videoFiles: Boolean,
    val launchWhenFinished: Boolean,
)

internal data class DiscoveredInstall(
    val directory: File?,
    val desktopShortcut: Boolean,
    val startMenuShortcut: Boolean,
    val videoFiles: Boolean,
    val msiProductCodes: List<String>,
    /** True when the branded installer wrote the uninstall entry (not an MSI product). */
    val managedByUs: Boolean,
)

internal data class SetupPaths(
    val desktopShortcut: File,
    val startMenuShortcut: File,
    val defaultDir: File,
)

internal data class ProbeResult(val discovered: DiscoveredInstall, val paths: SetupPaths)

/**
 * What the current PC already has, from the probe script's lines:
 * `desktop=`, `programs=`, `local=`, `desktopLnk=0|1`, `startLnk=0|1`, `ours=<dir>`, `msi=<code><tab><dir>`,
 * `lnkDir=<dir>`, `assoc=0|1`.
 */
internal fun parseProbe(
    text: String,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): ProbeResult {
    val body = text.removePrefix("\uFEFF")
    var desktop: String? = null
    var programs: String? = null
    var local: String? = null
    var desktopLnk = false
    var startLnk = false
    var ours: String? = null
    var lnkDir: String? = null
    var assoc = false
    val msi = mutableListOf<Pair<String, String>>()
    for (raw in body.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("desktop=") -> desktop = line.substringAfter("desktop=").trim().ifBlank { null }
            line.startsWith("programs=") -> programs = line.substringAfter("programs=").trim().ifBlank { null }
            line.startsWith("local=") -> local = line.substringAfter("local=").trim().ifBlank { null }
            line.startsWith("desktopLnk=") -> desktopLnk = line.endsWith("1")
            line.startsWith("startLnk=") -> startLnk = line.endsWith("1")
            line.startsWith("ours=") -> ours = line.substringAfter("ours=").trim().ifBlank { null }
            line.startsWith("lnkDir=") -> {
                if (lnkDir == null) lnkDir = line.substringAfter("lnkDir=").trim().ifBlank { null }
            }
            line.startsWith("assoc=") -> assoc = line.endsWith("1")
            line.startsWith("msi=") -> {
                val rest = line.substringAfter("msi=")
                val code = rest.substringBefore('\t').trim()
                val dir = rest.substringAfter('\t', "").trim()
                if (code.matches(Regex("\\{[0-9A-Fa-f-]+}"))) msi += code to dir
            }
        }
    }
    val homeFile = File(home.ifBlank { System.getProperty("user.home").orEmpty() })
    val desktopDir = desktop?.let(::cleanInstallPath) ?: File(homeFile, "Desktop")
    val programsDir = programs?.let(::cleanInstallPath) ?: File(homeFile, "AppData/Roaming/Microsoft/Windows/Start Menu/Programs")
    val localDir = local?.let(::cleanInstallPath)
        ?: env("LOCALAPPDATA")?.let(::cleanInstallPath)
        ?: File(homeFile, "AppData/Local")
    val oursDir = cleanInstallPath(ours)?.takeIf { isSafeWindowsInstallDir(it, homeFile.path, env) }
    val msiDir = msi.asSequence()
        .mapNotNull { cleanInstallPath(it.second) }
        .firstOrNull { isSafeWindowsInstallDir(it, homeFile.path, env) }
    val shortcutDir = cleanInstallPath(lnkDir)?.takeIf { isSafeWindowsInstallDir(it, homeFile.path, env) }
    return ProbeResult(
        discovered = DiscoveredInstall(
            directory = oursDir ?: msiDir ?: shortcutDir,
            desktopShortcut = desktopLnk,
            startMenuShortcut = startLnk,
            videoFiles = assoc,
            msiProductCodes = msi.map { it.first },
            managedByUs = oursDir != null,
        ),
        paths = SetupPaths(
            desktopShortcut = File(desktopDir, "YarmiplayTV.lnk"),
            startMenuShortcut = File(programsDir, "YarmiplayTV/YarmiplayTV.lnk"),
            defaultDir = File(localDir, "YarmiplayTV"),
        ),
    )
}

/** Fresh installs tick everything the old installer always did. An upgrade keeps whatever is already there. */
internal fun choicesFor(
    discovered: DiscoveredInstall,
    args: Array<String>,
    defaultDir: File,
    unattended: Boolean,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): SetupChoices {
    val upgrade = discovered.directory != null || discovered.msiProductCodes.isNotEmpty() || discovered.managedByUs
    val argDir = WindowsSetupArgs.installDir(args)?.let(::cleanInstallPath)?.takeIf { isSafeWindowsInstallDir(it, home, env) }
    val discDir = discovered.directory?.takeIf { isSafeWindowsInstallDir(it, home, env) }
    val safeDefault = defaultDir.takeIf { isSafeWindowsInstallDir(it, home, env) }
        ?: resolveWindowsInstallDir(null, home = home, env = env)
    val dir = argDir ?: discDir ?: safeDefault
    return SetupChoices(
        directory = dir,
        desktopShortcut = if (upgrade) discovered.desktopShortcut else true,
        startMenuShortcut = if (upgrade) discovered.startMenuShortcut else true,
        videoFiles = when {
            !upgrade -> true
            discovered.videoFiles -> true
            discovered.msiProductCodes.isNotEmpty() -> true
            else -> false
        },
        launchWhenFinished = if (unattended) WindowsSetupArgs.relaunch(args) else true,
    )
}

internal fun assertInstallable(
    source: File,
    dest: File,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
) {
    val cleaned = cleanInstallPath(dest.path)
        ?: error("Choose a valid install folder (${dest.path})")
    if (!isSafeWindowsInstallDir(cleaned, home, env)) {
        error("Choose a dedicated install folder for YarmiplayTV, not ${dest.path}")
    }
    val src = source.canonicalFile
    val dst = cleaned.canonicalFile
    if (dst == src || dst.path.startsWith(src.path + File.separator)) {
        error("Choose a folder that isn't the setup package itself")
    }
}

/**
 * Formats an error thrown while copying the app image into [dest] without duplicating [dest]'s path
 * when [error]'s message already starts with it (e.g. `FileNotFoundException` on Windows).
 */
internal fun formatReplaceFailure(dest: File, error: Throwable): String {
    val raw = error.message?.trim().orEmpty()
    if (raw.isEmpty()) return "Couldn't replace files in ${dest.path}: ${error.javaClass.simpleName}"
    if (raw.startsWith("Couldn't replace files in ", ignoreCase = true)) return raw
    val prefixes = listOfNotNull(
        dest.path.trimEnd('\\', '/'),
        runCatching { dest.canonicalPath.trimEnd('\\', '/') }.getOrNull(),
    ).distinct()
    var detail = raw
    for (prefix in prefixes) {
        if (prefix.isNotEmpty() && detail.startsWith(prefix, ignoreCase = true)) {
            val rest = detail.substring(prefix.length).trimStart('\\', '/', ' ', ':')
            if (rest.isNotEmpty()) {
                detail = rest
                break
            }
        }
    }
    return "Couldn't replace files in ${dest.path}: $detail"
}

/**
 * Copies [source] onto [dest], then deletes files in [dest] that the new image doesn't have. [onFraction] is 0 to 1.
 * Retries individual files when Windows still has them locked (common right after the app exits).
 */
internal fun copyAppImage(source: File, dest: File, onFraction: (Float) -> Unit) {
    val files = source.walkTopDown().filter { it.isFile }.toList()
    val total = files.sumOf { it.length() }.coerceAtLeast(1L)
    var copied = 0L
    var lastPct = -1
    for (file in files) {
        val target = dest.resolve(file.relativeTo(source))
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw java.io.IOException("Couldn't create folder ${parent.path}")
        }
        copyFileWithRetry(file, target)
        copied += file.length()
        val pct = ((copied * 100) / total).toInt()
        if (pct != lastPct) {
            lastPct = pct
            onFraction((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        }
    }
    val keep = files.map { it.relativeTo(source).invariantSeparatorsPath }.toSet()
    dest.walkBottomUp().filter { it != dest }.forEach { file ->
        val rel = file.relativeTo(dest).invariantSeparatorsPath
        if (rel == "data" || rel.startsWith("data/")) return@forEach
        if (file.isFile && rel !in keep) file.delete()
        else if (file.isDirectory && file.list().isNullOrEmpty()) file.delete()
    }
    onFraction(1f)
}

/** Retries when the destination is still locked by a process that has not fully released the file. */
internal fun copyFileWithRetry(source: File, target: File, attempts: Int = 10) {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        try {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            return
        } catch (e: Exception) {
            last = e
            try {
                Thread.sleep(300L * (attempt + 1).toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }
    throw last ?: error("Couldn't copy ${source.name}")
}

/** Little-endian zip length, then [SETUP_PAYLOAD_MAGIC]. Sits at the end of setup.exe. */
internal fun writeSetupFooter(out: OutputStream, zipLength: Long) {
    var value = zipLength
    repeat(8) {
        out.write((value and 0xFF).toInt())
        value = value ushr 8
    }
    out.write(SETUP_PAYLOAD_MAGIC.toByteArray(Charsets.US_ASCII))
}

internal interface SetupHost {
    fun uninstallProducts(codes: List<String>, onProgress: (String) -> Unit)
    fun runScript(script: String)
    fun launch(exe: File)
    fun scheduleDelete(dir: File, pids: List<Long>)
}

internal fun performInstall(
    source: File,
    choices: SetupChoices,
    discovered: DiscoveredInstall,
    paths: SetupPaths,
    progress: (Float, String) -> Unit,
    host: SetupHost,
) {
    val dest = choices.directory
    assertInstallable(source, dest)
    if (!File(source, "YarmiplayTV.exe").isFile) error("The setup package is missing YarmiplayTV.exe")
    try {
        if (discovered.msiProductCodes.isNotEmpty()) {
            progress(0.05f, "Removing the previous version…")
            host.uninstallProducts(discovered.msiProductCodes) { progress(0.08f, it) }
        }
        progress(0.12f, "Copying files…")
        if (!dest.mkdirs() && !dest.isDirectory) {
            error("Couldn't create the install folder (${dest.path})")
        }
        try {
            copyAppImage(source, dest) { fraction ->
                val pct = (fraction * 100).toInt().coerceIn(0, 100)
                progress(0.12f + 0.73f * fraction, "Copying files · $pct%")
            }
        } catch (e: Exception) {
            error(formatReplaceFailure(dest, e))
        }
        val previous = discovered.directory
        if (discovered.managedByUs && previous != null && isSafeWindowsInstallDir(previous) &&
            previous.canonicalFile != dest.canonicalFile && File(previous, "YarmiplayTV.exe").isFile
        ) {
            progress(0.88f, "Removing the previous copy…")
            previous.deleteRecursively()
        }
        progress(0.92f, "Adding shortcuts…")
        val exe = File(dest, "YarmiplayTV.exe")
        val kb = (dest.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024L).toInt().coerceAtLeast(1)
        host.runScript(installScript(exe, dest, APP_VERSION, kb, choices, paths.desktopShortcut, paths.startMenuShortcut))
        progress(1f, "Installed")
        writeUpdateStatus(null)
        if (choices.launchWhenFinished) host.launch(exe)
    } catch (e: Exception) {
        writeUpdateStatus(e.message ?: "Couldn't install")
        throw e
    }
}

internal fun performUninstall(
    dir: File,
    paths: SetupPaths,
    pids: List<Long>,
    progress: (Float, String) -> Unit,
    host: SetupHost,
) {
    if (!File(dir, "YarmiplayTV.exe").isFile) error("YarmiplayTV isn't installed in ${dir.path}")
    val root = dir.canonicalFile
    if (root.parentFile == null || !isSafeWindowsInstallDir(root)) error("Refusing to remove ${dir.path}")
    progress(0.35f, "Removing shortcuts…")
    host.runScript(removalScript(paths.desktopShortcut, paths.startMenuShortcut))
    progress(0.8f, "Removing files…")
    host.scheduleDelete(root, pids)
    progress(1f, "Uninstalled")
}

/** Reads install location, shortcuts and the old MSI product, if there is one. Settings in AppData are not touched. */
internal fun probeScript(): String = """
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    ${'$'}ErrorActionPreference = 'Continue'
    ${'$'}desk = [Environment]::GetFolderPath('Desktop')
    ${'$'}prog = [Environment]::GetFolderPath('Programs')
    ${'$'}local = [Environment]::GetFolderPath('LocalApplicationData')
    Write-Output ('desktop=' + ${'$'}desk)
    Write-Output ('programs=' + ${'$'}prog)
    Write-Output ('local=' + ${'$'}local)
    ${'$'}deskLnk = Join-Path ${'$'}desk 'YarmiplayTV.lnk'
    ${'$'}startLnk = Join-Path ${'$'}prog 'YarmiplayTV\YarmiplayTV.lnk'
    Write-Output ('desktopLnk=' + [int](Test-Path -LiteralPath ${'$'}deskLnk))
    Write-Output ('startLnk=' + [int](Test-Path -LiteralPath ${'$'}startLnk))
    ${'$'}uninstallRoots = @(
      'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
      'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
      'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    Get-ItemProperty ${'$'}uninstallRoots -ErrorAction SilentlyContinue |
      Where-Object { ${'$'}_.DisplayName -eq 'YarmiplayTV' } |
      ForEach-Object {
        ${'$'}id = ${'$'}_.PSChildName
        ${'$'}loc = ([string]${'$'}_.InstallLocation).Trim().Trim('"')
        if (-not ${'$'}loc -and ${'$'}_.DisplayIcon) {
          ${'$'}iconExe = (([string]${'$'}_.DisplayIcon) -replace ',[0-9-]+${'$'}','').Trim().Trim('"')
          if (${'$'}iconExe) { ${'$'}loc = Split-Path -Parent ${'$'}iconExe }
        }
        ${'$'}un = [string]${'$'}_.UninstallString
        if (-not ${'$'}loc -and ${'$'}un -match '"([^"]+YarmiplayTV\.exe)"') {
          ${'$'}loc = Split-Path -Parent ${'$'}Matches[1]
        }
        if (${'$'}un -match 'msiexec') { Write-Output ('msi=' + ${'$'}id + "`t" + ${'$'}loc) }
        elseif (${'$'}id -eq 'YarmiplayTV') { Write-Output ('ours=' + ${'$'}loc) }
      }
    foreach (${'$'}lnk in @(${'$'}startLnk, ${'$'}deskLnk)) {
      if (Test-Path -LiteralPath ${'$'}lnk) {
        try {
          ${'$'}target = (New-Object -ComObject WScript.Shell).CreateShortcut(${'$'}lnk).TargetPath
          if (${'$'}target -and (Test-Path -LiteralPath ${'$'}target)) {
            Write-Output ('lnkDir=' + (Split-Path -Parent ${'$'}target))
          }
        } catch {}
      }
    }
    ${'$'}assoc = ${'$'}false
    foreach (${'$'}ext in @(${windowsVideoExtensions.joinToString(",") { "'$it'" }})) {
      ${'$'}key = Get-Item -LiteralPath ('HKCU:\Software\Classes\.' + ${'$'}ext) -ErrorAction SilentlyContinue
      ${'$'}value = if (${'$'}key) { [string]${'$'}key.GetValue('') } else { '' }
      if (${'$'}value -like '*YarmiplayTV*') { ${'$'}assoc = ${'$'}true }
    }
    Write-Output ('assoc=' + [int]${'$'}assoc)
""".trimIndent()

internal fun installScript(
    exe: File,
    dir: File,
    version: String,
    sizeKb: Int,
    choices: SetupChoices,
    desktopLnk: File,
    startLnk: File,
): String = buildString {
    appendLine("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8")
    appendLine("\$ErrorActionPreference = 'Stop'")
    appendLine("\$shell = New-Object -ComObject WScript.Shell")
    appendLine("function Set-Link(\$path, \$target, \$work) {")
    appendLine("  \$parent = Split-Path -Parent \$path")
    appendLine("  if (-not (Test-Path -LiteralPath \$parent)) { New-Item -ItemType Directory -Force -Path \$parent | Out-Null }")
    appendLine("  \$link = \$shell.CreateShortcut(\$path)")
    appendLine("  \$link.TargetPath = \$target")
    appendLine("  \$link.WorkingDirectory = \$work")
    appendLine("  \$link.IconLocation = (\$target + ',0')")
    appendLine("  \$link.Description = 'YarmiplayTV'")
    appendLine("  \$link.Save()")
    appendLine("}")
    linkLine(choices.desktopShortcut, desktopLnk, exe, dir)
    linkLine(choices.startMenuShortcut, startLnk, exe, dir)
    append(associationScript(exe.absolutePath, choices.videoFiles))
    append(uninstallKeyScript(exe.absolutePath, dir.absolutePath, version, sizeKb))
}

internal fun removalScript(desktopLnk: File, startLnk: File): String = buildString {
    appendLine("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8")
    appendLine("\$ErrorActionPreference = 'Continue'")
    appendLine("Remove-Item -LiteralPath ${psQuoted(desktopLnk.absolutePath)} -Force -ErrorAction SilentlyContinue")
    appendLine("Remove-Item -LiteralPath ${psQuoted(startLnk.absolutePath)} -Force -ErrorAction SilentlyContinue")
    append(associationScript("", enable = false))
    appendLine(
        "Remove-Item -LiteralPath ${psQuoted("HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\YarmiplayTV")} -Recurse -Force -ErrorAction SilentlyContinue",
    )
}

internal fun deleteAfterExitScript(dir: File, pids: List<Long>): String = buildString {
    append("Wait-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue; ")
    append("Start-Sleep -Milliseconds 600; ")
    append("Remove-Item -LiteralPath ${psQuoted(dir.absolutePath)} -Recurse -Force -ErrorAction SilentlyContinue")
}

private fun StringBuilder.linkLine(create: Boolean, lnk: File, exe: File, dir: File) {
    if (create) {
        appendLine(
            "Set-Link ${psQuoted(lnk.absolutePath)} ${psQuoted(exe.absolutePath)} ${psQuoted(dir.absolutePath)}",
        )
    } else {
        appendLine("Remove-Item -LiteralPath ${psQuoted(lnk.absolutePath)} -Force -ErrorAction SilentlyContinue")
    }
}

private fun associationScript(exe: String, enable: Boolean): String = buildString {
    val prog = "HKCU:\\Software\\Classes\\YarmiplayTV.video"
    if (enable) {
        val command = "\"$exe\" \"%1\""
        appendLine("New-Item -Force -Path ${psQuoted("$prog\\shell\\open\\command")} | Out-Null")
        appendLine("Set-Item -LiteralPath ${psQuoted(prog)} -Value 'YarmiplayTV video'")
        appendLine("Set-Item -LiteralPath ${psQuoted("$prog\\shell\\open\\command")} -Value ${psQuoted(command)}")
        for (ext in windowsVideoExtensions) {
            val key = "HKCU:\\Software\\Classes\\.$ext"
            appendLine("New-Item -Force -Path ${psQuoted(key)} | Out-Null")
            appendLine("Set-Item -LiteralPath ${psQuoted(key)} -Value 'YarmiplayTV.video'")
        }
    } else {
        appendLine("Remove-Item -LiteralPath ${psQuoted(prog)} -Recurse -Force -ErrorAction SilentlyContinue")
        for (ext in windowsVideoExtensions) {
            val key = "HKCU:\\Software\\Classes\\.$ext"
            appendLine(
                "if ((Get-Item -LiteralPath ${psQuoted(key)} -ErrorAction SilentlyContinue).GetValue('') -eq 'YarmiplayTV.video') { Set-Item -LiteralPath ${psQuoted(key)} -Value '' }",
            )
        }
    }
}

private fun uninstallKeyScript(exe: String, dir: String, version: String, sizeKb: Int): String {
    val key = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\YarmiplayTV"
    val uninstall = "\"$exe\" --setup --uninstall"
    return buildString {
        appendLine("New-Item -Force -Path ${psQuoted(key)} | Out-Null")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name DisplayName -Value 'YarmiplayTV'")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name DisplayVersion -Value ${psQuoted(version)}")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name Publisher -Value 'Yarmiplay'")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name InstallLocation -Value ${psQuoted(dir)}")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name DisplayIcon -Value ${psQuoted("$exe,0")}")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name UninstallString -Value ${psQuoted(uninstall)}")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name NoModify -Type DWord -Value 1")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name NoRepair -Type DWord -Value 1")
        appendLine("Set-ItemProperty -LiteralPath ${psQuoted(key)} -Name EstimatedSize -Type DWord -Value $sizeKb")
    }
}

private fun setupSourceDir(): File {
    val launcher = System.getProperty("jpackage.app-path")?.let(::cleanInstallPath)?.absoluteFile?.takeIf { it.isFile }
        ?: error("This installer has to run from the YarmiplayTV setup package")
    return parentDirOf(launcher) ?: error("This installer has to run from the YarmiplayTV setup package")
}

private fun runPowerShell(script: String) {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    if (process.waitFor() != 0) error(out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "A setup step failed")
}

private val realHost = object : SetupHost {
    override fun uninstallProducts(codes: List<String>, onProgress: (String) -> Unit) {
        for (code in codes) {
            onProgress("Removing the previous version…")
            var process = ProcessBuilder("msiexec.exe", "/x", code, "/qn", "/norestart").start()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("Removing the previous version took too long")
            }
            // Per-machine MSI uninstalls fail with 1603 under /qn when not elevated; /passive allows UAC elevation.
            if (process.exitValue() == 1603) {
                process = ProcessBuilder("msiexec.exe", "/x", code, "/passive", "/norestart").start()
                if (!process.waitFor(5, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    error("Removing the previous version took too long")
                }
            }
            // 1605: nothing to remove. 3010: removed, Windows wants a restart for something else.
            if (process.exitValue() !in setOf(0, 1605, 3010)) error("Couldn't remove the previous version (${process.exitValue()})")
        }
    }

    override fun runScript(script: String) = runPowerShell(script)

    override fun launch(exe: File) {
        ProcessBuilder(exe.absolutePath).directory(exe.parentFile).start()
    }

    override fun scheduleDelete(dir: File, pids: List<Long>) {
        runDetachedPowerShell(deleteAfterExitScript(dir, pids))
    }
}

/** Windows setup entry (`YarmiplayTV.exe --setup`). Returns the process exit code. */
internal fun runWindowsSetup(args: List<String>): Int {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        System.err.println("The YarmiplayTV installer runs on Windows.")
        return 1
    }
    return try {
        System.setProperty("sun.java2d.dpiaware", "true")
        System.setProperty("sun.java2d.uiScale.enabled", "true")
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val argv = args.toTypedArray()
        val probe = runCatching { parseProbe(runProbe()) }.getOrElse { parseProbe("") }
        val source = setupSourceDir()
        when {
            WindowsSetupArgs.isUninstall(argv) -> uninstall(argv, source, probe)
            WindowsSetupArgs.unattended(argv) -> installUnattended(argv, source, probe)
            else -> installInteractive(source, probe)
        }
    } catch (e: Exception) {
        System.err.println(e.message ?: "Couldn't install")
        1
    }
}

private fun runProbe(): String {
    val encoded = Base64.getEncoder().encodeToString(probeScript().toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ).start()
    val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val err = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
    if (process.waitFor() != 0) error(err.ifBlank { text }.ifBlank { "Couldn't see the current installation" })
    return text
}

private fun installUnattended(argv: Array<String>, source: File, probe: ProbeResult): Int {
    val choices = choicesFor(probe.discovered, argv, probe.paths.defaultDir, unattended = true)
    val ui = WindowsSetupUi()
    val title = if (probe.discovered.directory != null || probe.discovered.msiProductCodes.isNotEmpty()) {
        "Updating YarmiplayTV"
    } else {
        "Installing YarmiplayTV"
    }
    ui.show(APP_VERSION, title)
    return try {
        performInstall(source, choices, probe.discovered, probe.paths, { fraction, detail ->
            ui.progress(fraction, "Version $APP_VERSION · $detail")
        }, realHost)
        ui.close()
        0
    } catch (e: Exception) {
        val message = e.message ?: "Couldn't install"
        System.err.println(message)
        writeUpdateStatus(message)
        ui.progress(1f, message)
        Thread.sleep(3500)
        ui.close()
        1
    }
}

private fun installInteractive(source: File, probe: ProbeResult): Int {
    val initial = choicesFor(probe.discovered, emptyArray(), probe.paths.defaultDir, unattended = false)
    val upgrading = probe.discovered.directory != null || probe.discovered.msiProductCodes.isNotEmpty()
    val latch = CountDownLatch(1)
    val code = AtomicInteger(2)
    val window = WindowsSetupWindow()
    window.showInstall(APP_VERSION, initial, upgrading, onInstall = { choices ->
        Thread {
            try {
                performInstall(source, choices, probe.discovered, probe.paths, window::progress, realHost)
                if (choices.launchWhenFinished) {
                    window.close()
                    code.set(0)
                    latch.countDown()
                } else {
                    window.succeeded("Installed") { window.close(); code.set(0); latch.countDown() }
                }
            } catch (e: Exception) {
                System.err.println(e.message ?: "Couldn't install")
                window.failed(e.message ?: "Couldn't install") { window.close(); code.set(1); latch.countDown() }
            }
        }.start()
    }, onClose = {
        window.close()
        code.set(2)
        latch.countDown()
    })
    latch.await()
    return code.get()
}

private fun uninstall(argv: Array<String>, source: File, probe: ProbeResult): Int {
    val dir = probe.discovered.directory?.takeIf { File(it, "YarmiplayTV.exe").isFile } ?: source
    val pids = appProcessIds(File(dir, "YarmiplayTV.exe"))
    if (WindowsSetupArgs.unattended(argv)) {
        val ui = WindowsSetupUi()
        ui.show(APP_VERSION, "Removing YarmiplayTV")
        return try {
            performUninstall(dir, probe.paths, pids, { fraction, detail -> ui.progress(fraction, detail) }, realHost)
            ui.close()
            0
        } catch (e: Exception) {
            ui.progress(1f, e.message ?: "Couldn't remove YarmiplayTV")
            Thread.sleep(2500)
            ui.close()
            1
        }
    }
    val latch = CountDownLatch(1)
    val code = AtomicInteger(2)
    val window = WindowsSetupWindow()
    window.showUninstall(onUninstall = {
        Thread {
            try {
                performUninstall(dir, probe.paths, pids, window::progress, realHost)
                window.close()
                code.set(0)
                latch.countDown()
            } catch (e: Exception) {
                window.failed(e.message ?: "Couldn't remove YarmiplayTV") { window.close(); code.set(1); latch.countDown() }
            }
        }.start()
    }, onClose = {
        window.close()
        code.set(2)
        latch.countDown()
    })
    latch.await()
    return code.get()
}

/** Used by the launcher. Never returns. */
fun enterWindowsSetup(args: List<String>): Nothing = exitProcess(runWindowsSetup(args))
