package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File

/**
 * Updates the macOS app from the download page's disk image: once the app has exited, the image is mounted, its
 * YarmiplayTV.app copied next to the running bundle and swapped in, and the image detached. When the bundle's
 * folder isn't writable (an administrator installed it in /Applications), macOS asks for an administrator's
 * password for that step.
 */
internal class MacUpdater(
    /** The running app's launcher, YarmiplayTV.app/Contents/MacOS/YarmiplayTV. */
    private val launcher: File,
    private val args: List<String>,
) : UpdateInstaller {
    private var dmg: File? = null
    private var version: String? = null

    override suspend fun download(update: Update, progress: (Float) -> Unit) {
        dmg = downloadVerified(update, File(System.getProperty("java.io.tmpdir"), "YarmiplayTV-${update.version}.dmg"), progress)
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val dmg = dmg ?: return
        val tmp = System.getProperty("java.io.tmpdir")
        val swap = File(tmp, "yarmiplaytv-swap.sh")
        swap.writeText(swapScript())
        runDetachedShell(script(dmg, launcher, swap, args, version.orEmpty(), ProcessHandle.current().pid(), relaunch), "yarmiplaytv-update.sh")
    }

    companion object {
        /** Replaces the bundle `$2` with the bundle `$1`, keeping the old one until the new one is in place. */
        fun swapScript(): String = """
            |set -e
            |rm -rf "${'$'}2.new"
            |ditto "${'$'}1" "${'$'}2.new"
            |rm -rf "${'$'}2.old"
            |mv "${'$'}2" "${'$'}2.old"
            |mv "${'$'}2.new" "${'$'}2" || { mv "${'$'}2.old" "${'$'}2"; exit 1; }
            |rm -rf "${'$'}2.old"
            |
        """.trimMargin()

        fun script(dmg: File, launcher: File, swap: File, args: List<String>, version: String, pid: Long, relaunch: Boolean): String {
            val bundle = requireNotNull(bundleOf(launcher)) { "not in an .app bundle: $launcher" }.invariantSeparatorsPath
            val app = shellQuoted(bundle)
            val swapPath = swap.invariantSeparatorsPath
            val swapCommand = "/bin/sh ${shellQuoted(swapPath)} \"\$src\" $app"
            // The same command as root, through AppleScript; the mounted bundle's path comes in the environment.
            fun asString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val appleScript = "set src to system attribute \"YARMIPLAYTV_SRC\"\n" +
                "do shell script \"/bin/sh \" & quoted form of ${asString(swapPath)} & \" \" & quoted form of src & \" \" & " +
                "quoted form of ${asString(bundle)} with administrator privileges"
            val run = shellQuoted(launcher.invariantSeparatorsPath)
            val quotedArgs = args.joinToString("") { " " + shellQuoted(it) }
            return buildString {
                appendLine("while kill -0 $pid 2>/dev/null; do sleep 0.5; done")
                appendLine("ok=0")
                appendLine("mnt=\$(mktemp -d \"\${TMPDIR:-/tmp}/YarmiplayTV-update.XXXXXX\")")
                appendLine("if hdiutil attach -nobrowse -readonly -noautoopen -mountpoint \"\$mnt\" ${shellQuoted(dmg.invariantSeparatorsPath)}; then")
                appendLine("  src=\$(find \"\$mnt\" -maxdepth 1 -name '*.app' | head -n 1)")
                appendLine("  if [ -n \"\$src\" ]; then")
                appendLine("    if [ -w \"\$(dirname $app)\" ] && [ -w $app ]; then")
                appendLine("      $swapCommand && ok=1")
                appendLine("    else")
                appendLine("      YARMIPLAYTV_SRC=\"\$src\" osascript -e ${shellQuoted(appleScript)} && ok=1")
                appendLine("    fi")
                appendLine("  fi")
                appendLine("  hdiutil detach \"\$mnt\" -quiet || hdiutil detach \"\$mnt\" -force -quiet")
                appendLine("fi")
                appendLine("rmdir \"\$mnt\" 2>/dev/null")
                appendLine("rm -f ${shellQuoted(dmg.invariantSeparatorsPath)} ${shellQuoted(swapPath)}")
                if (relaunch) {
                    appendLine("if [ \$ok = 1 ]; then")
                    appendLine("  open -n $app${if (args.isEmpty()) "" else " --args$quotedArgs"}")
                    appendLine("else")
                    // open doesn't pass the environment on, so the notice needs the launcher itself.
                    appendLine("  $FAILED_INSTALL=${shellQuoted(version)} nohup $run$quotedArgs >/dev/null 2>&1 &")
                    appendLine("fi")
                }
            }
        }

        /** The .app bundle around jpackage's launcher (YarmiplayTV.app/Contents/MacOS/YarmiplayTV), or null. */
        fun bundleOf(launcher: File): File? {
            val macos = launcher.parentFile ?: return null
            val contents = macos.parentFile ?: return null
            val bundle = contents.parentFile ?: return null
            return bundle.takeIf { macos.name == "MacOS" && contents.name == "Contents" && it.name.endsWith(".app") }
        }
    }
}
