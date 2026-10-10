package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileSystemView

/** What [updateBeforeLaunch] decided. */
internal sealed interface LaunchUpdate {
    /** Nothing to install now: open the app. */
    data object None : LaunchUpdate

    /** Downloaded and checked: the app exits so the installer can run, and the installer starts it again. */
    data class Install(val update: Update) : LaunchUpdate

    /** Skipped, or the download failed ([error]): open the app, with the notice. */
    data class Postponed(val update: Update, val error: Throwable? = null) : LaunchUpdate
}

/** Shows the download before the window opens. Called from any thread; [show]'s skip may be called from any. */
internal interface LaunchUpdateUi {
    fun show(update: Update, skip: () -> Unit)
    fun progress(fraction: Float)
    fun close()
}

/**
 * With Automatic updates on, downloads a newer version before the app opens, so the installer can replace the
 * app before it loads anything. [failedInstall] is set when the installer of that run failed and the app was
 * started again: then this doesn't try again, which would loop.
 */
internal suspend fun updateBeforeLaunch(
    settings: AppSettings,
    installer: UpdateInstaller?,
    failedInstall: String?,
    ui: LaunchUpdateUi,
    check: suspend () -> Update?,
): LaunchUpdate {
    if (!settings.checkForUpdates || !settings.installUpdatesOnLaunch || installer == null || failedInstall != null) {
        return LaunchUpdate.None
    }
    val update = check() ?: return LaunchUpdate.None
    // Not a child of the caller: Skip opens the app right away, even while a read is still blocked.
    val download = CoroutineScope(Dispatchers.IO + SupervisorJob()).async { installer.download(update, ui::progress) }
    val skipped = CompletableDeferred<Unit>()
    ui.show(update) { skipped.complete(Unit) }
    val finished = runCatching {
        select {
            download.onAwait { true }
            skipped.onAwait { false }
        }
    }
    download.cancel()
    ui.close()
    return finished.fold(
        onSuccess = { if (it) LaunchUpdate.Install(update) else LaunchUpdate.Postponed(update) },
        onFailure = { LaunchUpdate.Postponed(update, it) },
    )
}

/** A small window with the download's progress and Skip, shown instead of the app while it updates. */
internal class LaunchUpdateWindow(private val launcher: File?) : LaunchUpdateUi {
    private var frame: JFrame? = null
    private val bar = JProgressBar(0, 100)

    override fun show(update: Update, skip: () -> Unit) = SwingUtilities.invokeLater {
        val text = Color(0xE6, 0xE9, 0xEF)
        val background = Color(0x0E, 0x11, 0x16)
        bar.foreground = Color(0x3D, 0xA5, 0xF4)
        bar.background = Color(0x1B, 0x2A, 0x41)
        bar.border = EmptyBorder(0, 0, 0, 0)
        bar.preferredSize = Dimension(320, 6)
        val content = JPanel(BorderLayout(0, 14)).apply {
            this.background = background
            border = EmptyBorder(18, 20, 14, 20)
            add(JLabel("Updating YarmiplayTV to ${update.version}…").apply { foreground = text }, BorderLayout.NORTH)
            add(bar, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(JButton("Skip").apply { addActionListener { skip() } })
                },
                BorderLayout.SOUTH,
            )
        }
        frame = JFrame("YarmiplayTV").apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = skip()
            })
            launcher?.let { exe ->
                (FileSystemView.getFileSystemView().getSystemIcon(exe, 32, 32) as? ImageIcon)?.image?.let { iconImage = it }
            }
            contentPane = content
            isResizable = false
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }

    override fun progress(fraction: Float) = SwingUtilities.invokeLater { bar.value = (fraction * 100).toInt() }

    override fun close() = SwingUtilities.invokeLater { frame?.dispose() }
}
