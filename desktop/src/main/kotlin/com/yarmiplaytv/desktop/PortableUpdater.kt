package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File

/**
 * Updates the portable Windows app from the download page's zip: once the app has exited, the zip's files replace
 * the app's own (the launcher, `app` and `runtime`) in its folder, and `data` (the settings) is left alone. If
 * copying fails, the old `app` and `runtime` are put back.
 */
internal class PortableUpdater(
    private val launcher: File,
    private val args: List<String>,
) : UpdateInstaller {
    private var zip: File? = null
    private var version: String? = null

    override suspend fun download(update: Update, progress: (Float) -> Unit) {
        zip = downloadVerified(update, File(System.getProperty("java.io.tmpdir"), "YarmiplayTV-${update.version}-portable.zip"), progress)
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val zip = zip ?: return
        runDetachedPowerShell(script(zip, launcher, args, version.orEmpty(), appProcessIds(launcher), relaunch))
    }

    companion object {
        /** The folders of the app the zip replaces; anything else in the folder, `data` above all, stays. */
        val REPLACED = listOf("app", "runtime")

        fun script(zip: File, launcher: File, args: List<String>, version: String, pids: List<Long>, relaunch: Boolean): String {
            val dir = psQuoted(launcher.absoluteFile.parent)
            val exe = psQuoted(launcher.name)
            val replaced = REPLACED.joinToString(",") { psQuoted(it) }
            return buildString {
                append("Wait-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue; ")
                append("\$ok = \$false; \$dir = $dir; \$tmp = Join-Path \$env:TEMP ('YarmiplayTV-update-' + [guid]::NewGuid()); ")
                append("try { ")
                append("Expand-Archive -LiteralPath ${psQuoted(zip.path)} -DestinationPath \$tmp -Force -ErrorAction Stop; ")
                // The zip holds the app in a YarmiplayTV folder.
                append("\$src = if (Test-Path -LiteralPath (Join-Path \$tmp $exe)) { \$tmp } else { (Get-ChildItem -LiteralPath \$tmp -Directory | Select-Object -First 1).FullName }; ")
                append("if (-not \$src -or -not (Test-Path -LiteralPath (Join-Path \$src $exe))) { throw 'not the app' }; ")
                append("foreach (\$n in $replaced) { \$d = Join-Path \$dir \$n; if (Test-Path -LiteralPath \$d) { Move-Item -LiteralPath \$d -Destination (\$d + '.old') -Force -ErrorAction Stop } }; ")
                append("try { ")
                append("Get-ChildItem -LiteralPath \$src -Force | Where-Object { \$_.Name -ne 'data' } | Copy-Item -Destination \$dir -Recurse -Force -ErrorAction Stop; ")
                append("\$ok = \$true ")
                append("} catch { ")
                append("foreach (\$n in $replaced) { \$d = Join-Path \$dir \$n; if (Test-Path -LiteralPath (\$d + '.old')) { Remove-Item -LiteralPath \$d -Recurse -Force -ErrorAction SilentlyContinue; Move-Item -LiteralPath (\$d + '.old') -Destination \$d -Force } } ")
                append("}; ")
                append("if (\$ok) { foreach (\$n in $replaced) { Remove-Item -LiteralPath (Join-Path \$dir (\$n + '.old')) -Recurse -Force -ErrorAction SilentlyContinue } } ")
                append("} catch { }; ")
                append("Remove-Item -LiteralPath \$tmp -Recurse -Force -ErrorAction SilentlyContinue; ")
                append("Remove-Item -LiteralPath ${psQuoted(zip.path)} -ErrorAction SilentlyContinue; ")
                if (relaunch) append(psRelaunch(launcher, args, version))
            }
        }
    }
}
