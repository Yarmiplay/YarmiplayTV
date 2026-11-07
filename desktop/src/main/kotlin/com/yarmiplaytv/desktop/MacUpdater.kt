package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateFailedException
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

    override suspend fun download(update: Update, progress: (Float?) -> Unit) {
        dmg = downloadVerified(update, File(System.getProperty("java.io.tmpdir"), "YarmiplayTV-${update.version}.dmg"), progress)
        version = update.version
    }

    override fun installAfterExit(relaunch: Boolean) {
        val dmg = dmg ?: throw UpdateFailedException("launch", "the downloaded disk image is missing")
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
            val openArgs = if (args.isEmpty()) "" else " --args$quotedArgs"
            return buildString {
                appendLine("while kill -0 $pid 2>/dev/null; do sleep 0.5; done")
                appendLine("ok=0")
                appendLine("stage=unpack")
                appendLine("code=0")
                appendLine("detail=")
                appendLine("ver=${shellQuoted(version)}")
                appendLine("failfile=\"\$HOME/Library/Application Support/YarmiplayTV/update-failure.txt\"")
                appendLine("mnt=\$(mktemp -d \"\${TMPDIR:-/tmp}/YarmiplayTV-update.XXXXXX\")")
                appendLine("attach_log=\$(hdiutil attach -nobrowse -readonly -noautoopen -mountpoint \"\$mnt\" ${shellQuoted(dmg.invariantSeparatorsPath)} 2>&1)")
                appendLine("attach_code=\$?")
                appendLine("if [ \"\$attach_code\" -ne 0 ]; then")
                appendLine("  code=\$attach_code")
                appendLine("  detail=\$attach_log")
                appendLine("else")
                appendLine("  src=\$(find \"\$mnt\" -maxdepth 1 -name '*.app' | head -n 1)")
                appendLine("  if [ -z \"\$src\" ]; then")
                appendLine("    code=1")
                appendLine("    detail='the disk image has no application'")
                appendLine("  elif [ -w \"\$(dirname $app)\" ] && [ -w $app ]; then")
                appendLine("    stage=replace")
                appendLine("    swap_log=\$($swapCommand 2>&1)")
                appendLine("    swap_code=\$?")
                appendLine("    if [ \"\$swap_code\" -eq 0 ]; then ok=1; else code=\$swap_code; detail=\$swap_log; fi")
                appendLine("  else")
                appendLine("    stage=replace")
                appendLine("    admin_log=\$(YARMIPLAYTV_SRC=\"\$src\" osascript -e ${shellQuoted(appleScript)} 2>&1)")
                appendLine("    admin_code=\$?")
                appendLine("    if [ \"\$admin_code\" -eq 0 ]; then ok=1; else code=\$admin_code; detail=\$admin_log; fi")
                appendLine("  fi")
                appendLine("  hdiutil detach \"\$mnt\" -quiet || hdiutil detach \"\$mnt\" -force -quiet")
                appendLine("fi")
                appendLine("rmdir \"\$mnt\" 2>/dev/null")
                appendLine("rm -f ${shellQuoted(dmg.invariantSeparatorsPath)} ${shellQuoted(swapPath)}")
                append(shRecordFailure())
                appendLine()
                if (relaunch) {
                    appendLine("if [ \"\$ok\" = 1 ]; then")
                    appendLine("  unset $FAILED_INSTALL")
                    appendLine("  open_log=\$(open -n $app$openArgs 2>&1)")
                    appendLine("  open_code=\$?")
                    appendLine("  if [ \"\$open_code\" -ne 0 ]; then")
                    appendLine("    code=\$open_code")
                    appendLine("    stage=restart")
                    appendLine("    detail=\$open_log")
                    appendLine("    ok=0")
                    append(shRecordFailure().prependIndent("    "))
                    appendLine("    $FAILED_INSTALL=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\" nohup $run$quotedArgs >/dev/null 2>&1 &")
                    appendLine("  fi")
                    appendLine("else")
                    // open doesn't pass the environment on, so the notice needs the launcher itself.
                    appendLine("  $FAILED_INSTALL=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\" nohup $run$quotedArgs >/dev/null 2>&1 &")
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
