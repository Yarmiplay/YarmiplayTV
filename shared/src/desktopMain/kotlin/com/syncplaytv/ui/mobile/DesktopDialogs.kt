package com.syncplaytv.ui.mobile

import com.syncplaytv.local.LocalMatcher
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

/** Native open dialogs. They block until closed, like the system pickers on Android. */
object DesktopDialogs {
    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val owner: Frame?
        get() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame

    fun pickVideo(): Path? {
        val dialog = FileDialog(owner, "Open a video", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> LocalMatcher.isVideo(name, null) }
        // Windows ignores the filename filter and only understands a pattern in the file name box.
        if (isWindows) dialog.file = VIDEO_PATTERN
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(dir, file).toPath()
    }

    fun pickVideos(): List<Path> {
        val dialog = FileDialog(owner, "Add videos to the playlist", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> LocalMatcher.isVideo(name, null) }
        if (isWindows) dialog.file = VIDEO_PATTERN
        dialog.isVisible = true
        return dialog.files.orEmpty().map { it.toPath() }
    }

    fun openPlaylist(): Path? {
        val dialog = FileDialog(owner, "Load a playlist", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".txt", ignoreCase = true) }
        if (isWindows) dialog.file = "*.txt"
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(dir, file).toPath()
    }

    fun savePlaylist(suggestedName: String): Path? {
        val dialog = FileDialog(owner, "Save the playlist", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(dir, if (file.contains('.')) file else "$file.txt").toPath()
    }

    fun pickFolder(title: String = "Add a media folder"): Path? {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(owner, title, FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory ?: return null
                val file = dialog.file ?: return null
                return File(dir, file).toPath()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    /** The videos directly inside [folder], or [folder] itself when it's a video, sorted by name like a file browser. */
    fun videosIn(folder: Path): List<Path> {
        val file = folder.toFile()
        if (file.isFile) return if (LocalMatcher.isVideo(file.name, null)) listOf(folder) else emptyList()
        return file.listFiles().orEmpty()
            .filter { it.isFile && LocalMatcher.isVideo(it.name, null) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it.toPath() }
    }

    private const val VIDEO_PATTERN = "*.mkv;*.mp4;*.m4v;*.avi;*.mov;*.webm;*.wmv;*.flv;*.ts;*.m2ts;*.mts;*.mpg;*.mpeg;*.ogv;*.3gp;*.vob"
}
