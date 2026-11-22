package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateFailedException
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File

/**
 * Updates the app installed from the download page's .deb: once the app has exited, `pkexec apt-get install`
 * installs the new package (the system asks for the password), then the app starts again. Without pkexec, the
 * .deb is opened in the system's software installer instead, and the app isn't started again.
 */
internal class DebUpdater(
    private val launcher: File,
    private val args: List<String>,
    private val hasPkexec: () -> Boolean = { onPath("pkexec") },
) : UpdateInstaller {
    private var deb: File? = null
    private var version: String? = null

    override suspend fun download(update: Update, progress: (Float?) -> Unit) {
        // apt reads local packages as its own user, so the file goes where everyone can read it: a new folder that
        // only this user can write to, which others can read.
        deb = downloadIntoNewDir(update, "yarmiplaytv_${update.version}_amd64.deb", progress, othersCanRead = true)
            .apply { setReadable(true, false) }
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val deb = deb ?: throw UpdateFailedException("launch", "the downloaded package is missing")
        runDetachedShell(
            script(deb, launcher, args, version.orEmpty(), ProcessHandle.current().pid(), relaunch, hasPkexec(), cleanupDir = deb.parentFile),
            "yarmiplaytv-update.sh",
        )
    }

    companion object {
        /** [cleanupDir], when given, is the download's own folder, removed with the package once it is installed. */
        fun script(
            deb: File,
            launcher: File,
            args: List<String>,
            version: String,
            pid: Long,
            relaunch: Boolean,
            pkexec: Boolean,
            cleanupDir: File? = null,
        ): String =
            buildString {
                val debPath = shellQuoted(deb.invariantSeparatorsPath)
                val run = shellQuoted(launcher.invariantSeparatorsPath) + args.joinToString("") { " " + shellQuoted(it) }
                appendLine("while kill -0 $pid 2>/dev/null; do sleep 0.5; done")
                appendLine("ok=0")
                appendLine("code=0")
                appendLine("detail=")
                appendLine("ver=${shellQuoted(version)}")
                appendLine("failfile=\"\${XDG_CONFIG_HOME:-\$HOME/.config}/yarmiplaytv/update-failure.txt\"")
                if (!pkexec) {
                    // The software installer takes a package it could open. A failed open is not a finished install.
                    appendLine("stage=launch")
                    appendLine("xdg-open $debPath")
                    appendLine("code=\$?")
                    appendLine("if [ \"\$code\" -eq 0 ]; then")
                    appendLine("  exit 0")
                    appendLine("fi")
                    appendLine("detail=\"xdg-open exited with code \$code\"")
                    append(shRecordFailure())
                    appendLine()
                    if (relaunch) appendLine("$FAILED_INSTALL=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\" nohup $run >/dev/null 2>&1 &")
                    return@buildString
                }
                appendLine("stage=install")
                appendLine("log=\$(mktemp)")
                appendLine("pkexec apt-get install -y $debPath >\"\$log\" 2>&1")
                appendLine("code=\$?")
                appendLine("if [ \"\$code\" -eq 0 ]; then")
                appendLine("  ok=1")
                appendLine("else")
                appendLine("  detail=\$(tail -c 400 \"\$log\")")
                appendLine("fi")
                appendLine("rm -f \"\$log\" $debPath")
                if (cleanupDir != null) appendLine("rmdir ${shellQuoted(cleanupDir.invariantSeparatorsPath)} 2>/dev/null")
                append(shRecordFailure())
                appendLine()
                if (relaunch) {
                    appendLine("if [ \"\$ok\" = 1 ]; then")
                    appendLine("  unset $FAILED_INSTALL")
                    appendLine("else")
                    appendLine("  export $FAILED_INSTALL=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\"")
                    appendLine("fi")
                    appendLine("nohup $run >/dev/null 2>&1 &")
                }
            }

        /** Whether [command] is a program on PATH. */
        fun onPath(command: String, path: String? = System.getenv("PATH")): Boolean =
            path.orEmpty().split(File.pathSeparatorChar).any { it.isNotEmpty() && File(it, command).canExecute() }
    }
}
