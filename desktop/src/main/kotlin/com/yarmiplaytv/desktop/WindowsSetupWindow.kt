package com.yarmiplaytv.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * The one-page installer: logo, version, install folder, and the shortcuts and file types already set up.
 * Install runs the copy; Close leaves without changing anything.
 */
internal class WindowsSetupWindow {
    private var frame: JFrame? = null
    private val status = JLabel(" ")
    private val bar = Progress()
    private var working = false
    private var closeAction: () -> Unit = {}

    fun showInstall(
        version: String,
        initial: SetupChoices,
        upgrading: Boolean,
        onInstall: (SetupChoices) -> Unit,
        onClose: () -> Unit,
    ) {
        closeAction = onClose
        SwingUtilities.invokeLater {
            val dir = JTextField(initial.directory.path)
            styleField(dir)
            val desktop = tick("Desktop shortcut", initial.desktopShortcut)
            val start = tick("Start menu shortcut", initial.startMenuShortcut)
            val videos = tick("Open video files with YarmiplayTV", initial.videoFiles)
            val launch = tick("Launch YarmiplayTV when finished", initial.launchWhenFinished)
            val change = quietButton("Change") {
                val chooser = JFileChooser(File(dir.text).let { if (it.isDirectory) it else it.parentFile })
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.dialogTitle = "Install location"
                if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) dir.text = chooser.selectedFile.path
            }
            val folder = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(dir, BorderLayout.CENTER)
                add(change, BorderLayout.EAST)
            }
            val form = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(caption("Install location"))
                add(Box.createVerticalStrut(6))
                add(folder)
                add(Box.createVerticalStrut(12))
                add(desktop)
                add(Box.createVerticalStrut(4))
                add(start)
                add(Box.createVerticalStrut(4))
                add(videos)
                add(Box.createVerticalStrut(4))
                add(launch)
            }
            lateinit var install: JButton
            install = accentButton("Install") {
                if (working) return@accentButton
                val path = dir.text.trim()
                if (path.isEmpty()) {
                    status.text = "Choose a folder"
                    return@accentButton
                }
                working = true
                install.isEnabled = false
                showWork(form)
                onInstall(
                    SetupChoices(
                        directory = File(path),
                        desktopShortcut = desktop.isSelected,
                        startMenuShortcut = start.isSelected,
                        videoFiles = videos.isSelected,
                        launchWhenFinished = launch.isSelected,
                    ),
                )
            }
            val subtitle = if (upgrading) "Version $version · updates the installed copy" else "Version $version"
            open(
                title = "Install YarmiplayTV",
                subtitle = subtitle,
                body = form,
                primary = install,
                closeLabel = "Close",
            )
            status.text = if (upgrading) "These match the current installation" else " "
        }
    }

    fun showUninstall(onUninstall: () -> Unit, onClose: () -> Unit) {
        closeAction = onClose
        SwingUtilities.invokeLater {
            val note = JLabel("Settings in AppData are kept.").apply {
                foreground = Palette.dim
                font = uiFont(Font.PLAIN, 13f)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            val body = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(note)
            }
            lateinit var remove: JButton
            remove = accentButton("Uninstall") {
                if (working) return@accentButton
                working = true
                remove.isEnabled = false
                showWork(body)
                onUninstall()
            }
            open("Uninstall YarmiplayTV", "Version $APP_VERSION", body, remove, "Close")
        }
    }

    fun progress(fraction: Float, detail: String) = SwingUtilities.invokeLater {
        bar.target = fraction.coerceIn(0f, 1f)
        status.text = detail
    }

    fun failed(message: String, onClose: () -> Unit) = SwingUtilities.invokeLater {
        working = false
        closeAction = onClose
        status.text = message
        bar.target = 1f
    }

    fun succeeded(message: String, onClose: () -> Unit) = SwingUtilities.invokeLater {
        working = false
        closeAction = onClose
        status.text = message
        bar.target = 1f
    }

    fun close() = SwingUtilities.invokeLater {
        bar.stop()
        frame?.dispose()
        frame = null
    }

    private fun showWork(body: JComponent) {
        body.isVisible = false
        bar.isVisible = true
        status.text = "Starting…"
        bar.start()
        frame?.rootPane?.defaultButton = null
    }

    private fun open(title: String, subtitle: String, body: JComponent, primary: JButton, closeLabel: String) {
        status.apply {
            foreground = Palette.dim
            font = uiFont(Font.PLAIN, 13f)
            text = subtitle
        }
        val heading = JLabel(title).apply {
            foreground = Palette.text
            font = uiFont(Font.BOLD, 16f)
        }
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(heading)
            add(Box.createVerticalStrut(4))
            add(status)
        }
        val header = JPanel(BorderLayout(16, 0)).apply {
            isOpaque = false
            add(JLabel(javax.swing.ImageIcon(LaunchUpdateWindow.logoImage(52))), BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
        bar.isVisible = false
        val stack = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(body)
            add(bar)
        }
        val close = quietButton(closeLabel) {
            if (!working) closeAction()
        }
        val buttons = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(close, BorderLayout.WEST)
            add(primary, BorderLayout.EAST)
        }
        val card = Card().apply {
            layout = BorderLayout(0, 16)
            border = EmptyBorder(22, 24, 18, 22)
            add(header, BorderLayout.NORTH)
            add(stack, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        frame = JFrame("YarmiplayTV").apply {
            isUndecorated = true
            if (roundedWindow()) background = Color(0, 0, 0, 0)
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            iconImages = listOf(16, 32, 48, 64).map(LaunchUpdateWindow::logoImage)
            contentPane = card
            rootPane.registerKeyboardAction(
                { if (!working) closeAction() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )
            val drag = object : MouseAdapter() {
                private var from: Point? = null
                override fun mousePressed(e: MouseEvent) { from = e.point }
                override fun mouseDragged(e: MouseEvent) {
                    val start = from ?: return
                    setLocation(x + e.x - start.x, y + e.y - start.y)
                }
            }
            card.addMouseListener(drag)
            card.addMouseMotionListener(drag)
            isResizable = false
            pack()
            setSize(maxOf(width, 480), height)
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
    }

    private fun tick(label: String, selected: Boolean) = JCheckBox(label, selected).apply {
        isOpaque = false
        foreground = Palette.text
        font = uiFont(Font.PLAIN, 13f)
        alignmentX = JComponent.LEFT_ALIGNMENT
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun caption(label: String) = JLabel(label).apply {
        foreground = Palette.dim
        font = uiFont(Font.PLAIN, 12f)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    private fun styleField(field: JTextField) {
        field.font = uiFont(Font.PLAIN, 13f)
        field.foreground = Palette.text
        field.background = Palette.track
        field.caretColor = Palette.accent
        field.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.outline),
            EmptyBorder(6, 8, 6, 8),
        )
        field.alignmentX = JComponent.LEFT_ALIGNMENT
    }

    private fun quietButton(label: String, onClick: () -> Unit) = JButton(label).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        foreground = Palette.text
        font = uiFont(Font.PLAIN, 13f)
        border = EmptyBorder(8, 14, 8, 14)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
    }

    private fun accentButton(label: String, onClick: () -> Unit) = object : JButton(label) {
        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            foreground = Color.WHITE
            font = uiFont(Font.BOLD, 13f)
            border = EmptyBorder(8, 18, 8, 18)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { if (isEnabled) onClick() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isEnabled) Palette.accent else Palette.outline
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 12.0, 12.0))
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private object Palette {
        val outline = Color(0x2A, 0x31, 0x40)
        val text = Color(0xE6, 0xE9, 0xEF)
        val dim = Color(0x9A, 0xA4, 0xB2)
        val accent = Color(0x3D, 0xA5, 0xF4)
        val track = Color(0x24, 0x2B, 0x38)
        val card = Color(0x16, 0x1A, 0x21)
    }

    private class Card : JPanel() {
        init { isOpaque = false }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 22.0
                g2.color = Palette.card
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc))
                g2.color = Palette.outline
                g2.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc))
            } finally {
                g2.dispose()
            }
        }
    }

    private class Progress : JPanel() {
        @Volatile var target = 0f
        private var shown = 0f
        private var timer: javax.swing.Timer? = null

        init {
            isVisible = false
            isOpaque = false
            preferredSize = Dimension(420, 8)
            maximumSize = Dimension(Int.MAX_VALUE, 8)
            alignmentX = LEFT_ALIGNMENT
        }

        fun start() {
            if (timer == null) {
                timer = javax.swing.Timer(16) {
                    shown += (target - shown) * 0.18f
                    repaint()
                }.also { it.start() }
            }
        }

        fun stop() {
            timer?.stop()
            timer = null
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val h = height.toDouble()
                val w = width.toDouble()
                g2.color = Palette.track
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))
                g2.color = Palette.accent
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, w * shown.coerceIn(0f, 1f), h, h, h))
            } finally {
                g2.dispose()
            }
        }
    }

    private fun roundedWindow(): Boolean = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    }.getOrDefault(false)

    private fun uiFont(style: Int, size: Float): Font {
        val family = listOf("Segoe UI", "SF Pro Text", "Helvetica Neue", Font.SANS_SERIF)
            .firstOrNull { it in GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames }
            ?: Font.SANS_SERIF
        return Font(family, style, 1).deriveFont(size)
    }
}
