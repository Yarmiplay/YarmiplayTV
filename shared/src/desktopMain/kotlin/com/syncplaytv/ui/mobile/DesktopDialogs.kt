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

    fun pickFolder(): Path? {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(owner, "Add a media folder", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory ?: return null
                val file = dialog.file ?: return null
                return File(dir, file).toPath()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = "Add a media folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    private const val VIDEO_PATTERN = "*.mkv;*.mp4;*.m4v;*.avi;*.mov;*.webm;*.wmv;*.flv;*.ts;*.m2ts;*.mts;*.mpg;*.mpeg;*.ogv;*.3gp;*.vob"
}
