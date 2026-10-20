package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
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
        // apt reads local packages as its own user, so the file goes where everyone can read it.
        deb = downloadVerified(update, File(System.getProperty("java.io.tmpdir"), "yarmiplaytv_${update.version}_amd64.deb"), progress)
            .apply { setReadable(true, false) }
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val deb = deb ?: return
        runDetachedShell(script(deb, launcher, args, version.orEmpty(), ProcessHandle.current().pid(), relaunch, hasPkexec()), "yarmiplaytv-update.sh")
    }

    companion object {
        fun script(deb: File, launcher: File, args: List<String>, version: String, pid: Long, relaunch: Boolean, pkexec: Boolean): String =
            buildString {
                appendLine("while kill -0 $pid 2>/dev/null; do sleep 0.5; done")
                if (!pkexec) {
                    // The software installer takes it from here; the file stays for it.
                    appendLine("xdg-open ${shellQuoted(deb.invariantSeparatorsPath)}")
                    return@buildString
                }
                appendLine("ok=0")
                appendLine("pkexec apt-get install -y ${shellQuoted(deb.invariantSeparatorsPath)} && ok=1")
                appendLine("rm -f ${shellQuoted(deb.invariantSeparatorsPath)}")
                if (relaunch) {
                    val run = shellQuoted(launcher.invariantSeparatorsPath) + args.joinToString("") { " " + shellQuoted(it) }
                    appendLine("if [ \$ok = 1 ]; then unset $FAILED_INSTALL; else export $FAILED_INSTALL=${shellQuoted(version)}; fi")
                    appendLine("nohup $run >/dev/null 2>&1 &")
                }
            }

        /** Whether [command] is a program on PATH. */
        fun onPath(command: String, path: String? = System.getenv("PATH")): Boolean =
            path.orEmpty().split(File.pathSeparatorChar).any { it.isNotEmpty() && File(it, command).canExecute() }
    }
}
