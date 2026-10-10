package com.yarmiplaytv.screenshot

import com.yarmiplaytv.Logger
import com.yarmiplaytv.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Where a platform keeps screenshots. */
interface ScreenshotStore {
    /** Where screenshots go while no folder is picked in the settings, as the settings show it. */
    val defaultFolder: String

    /** A file for the player to write the screenshot [fileName] to, before [save] puts it in place. */
    fun scratchFile(fileName: String): File

    /**
     * Moves [file] into [folder] (a folder URI from the settings, or empty for [defaultFolder]) and returns that
     * place, for the message. Throws when the folder can't be written to.
     */
    fun save(file: File, folder: String): String
}

/** Desktop: screenshots go straight into a folder; a name already taken gets " (2)", " (3)", … */
class FolderScreenshotStore(private val scratchDir: File, private val defaultDir: () -> File) : ScreenshotStore {
    override val defaultFolder: String get() = defaultDir().path

    override fun scratchFile(fileName: String): File = File(scratchDir.apply { mkdirs() }, fileName).apply { delete() }

    override fun save(file: File, folder: String): String {
        val dir = if (folder.isEmpty()) defaultDir() else folderOf(folder)
        dir.mkdirs()
        Files.move(file.toPath(), uniqueIn(dir, file.name).toPath())
        return dir.path
    }

    private fun folderOf(uri: String): File = (if (uri.startsWith("file:")) Paths.get(URI(uri)) else Paths.get(uri)).toFile()

    private fun uniqueIn(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) fileName else "$base ($n).$extension") }
            .first { !it.exists() }
    }
}

/** Takes screenshots of the playing video, one at a time, and says on the video where each went. */
class Screenshots(
    private val scope: CoroutineScope,
    private val player: Player,
    private val store: ScreenshotStore,
    /** The folder URI from the settings; empty for the store's default. */
    private val folder: () -> String,
    private val title: () -> String?,
) {
    // Only touched on the main thread.
    private var taking = false

    /** Where screenshots go while no folder is picked in the settings. */
    val defaultFolder: String get() = store.defaultFolder

    fun take() {
        if (taking || !player.isFileLoaded) return
        taking = true
        val name = fileName(title(), LocalDateTime.now())
        val target = folder()
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val file = store.scratchFile(name)
                    try {
                        if (player.screenshot(file.path)) "Screenshot saved to ${store.save(file, target)}" else "Couldn't take a screenshot"
                    } finally {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Couldn't save the screenshot", e)
                "Couldn't save the screenshot" + (e.message?.let { ": $it" } ?: "")
            } finally {
                taking = false
            }
            player.showText(message)
        }
    }

    companion object {
        private const val TAG = "Screenshots"
        private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
        private val unsafe = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")

        /** "<title> 2026-10-10 05-36-12.jpg", without the characters file systems refuse. */
        fun fileName(title: String?, at: LocalDateTime): String {
            val base = title.orEmpty().replace(unsafe, " ").replace(Regex("\\s+"), " ").take(80).trim(' ', '.')
            return "${base.ifEmpty { "YarmiplayTV" }} ${stamp.format(at)}.jpg"
        }
    }
}
