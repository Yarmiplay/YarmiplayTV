package com.yarmiplaytv.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * Branded install / silent-update window for the Windows `.exe` setup.
 * Matches [LaunchUpdateWindow]: dark card, logo, title, status, accent progress — always shown,
 * including for `--silent` / `/S` (never a fully invisible install).
 */
class WindowsSetupUi {
    private var frame: JFrame? = null
    private val status = JLabel(" ")
    private val bar = SetupProgressBar()

    fun show(version: String, titleText: String = "Installing YarmiplayTV") = SwingUtilities.invokeLater {
        status.apply {
            foreground = Palette.dim
            font = uiFont(Font.PLAIN, 13f)
            text = "Version $version · Starting…"
        }
        val title = JLabel(titleText).apply {
            foreground = Palette.text
            font = uiFont(Font.BOLD, 16f)
        }
        val hint = JLabel("YarmiplayTV opens when it's done").apply {
            foreground = Palette.faint
            font = uiFont(Font.PLAIN, 12f)
        }
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(Box.createVerticalStrut(4))
            add(status)
        }
        val header = JPanel(BorderLayout(16, 0)).apply {
            isOpaque = false
            add(LaunchUpdateWindow.logoComponent(52), BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
        val card = Card().apply {
            layout = BorderLayout(0, 18)
            border = EmptyBorder(22, 24, 18, 22)
            add(header, BorderLayout.NORTH)
            add(bar, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }
        frame = JFrame("YarmiplayTV").apply {
            isUndecorated = true
            if (translucencySupported()) background = Color(0, 0, 0, 0)
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            iconImages = LaunchUpdateWindow.windowIcons()
            contentPane = card
            val drag = object : java.awt.event.MouseAdapter() {
                private var from: java.awt.Point? = null
                override fun mousePressed(e: java.awt.event.MouseEvent) { from = e.point }
                override fun mouseDragged(e: java.awt.event.MouseEvent) {
                    val start = from ?: return
                    setLocation(x + e.x - start.x, y + e.y - start.y)
                }
            }
            card.addMouseListener(drag)
            card.addMouseMotionListener(drag)
            isResizable = false
            pack()
            setSize(maxOf(width, 440), height)
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
        bar.start()
    }

    fun progress(fraction: Float?, detail: String? = null) = SwingUtilities.invokeLater {
        if (fraction != null) bar.target = fraction.coerceIn(0f, 1f)
        val pct = fraction?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "Working…"
        status.text = detail ?: pct
    }

    fun close() = SwingUtilities.invokeLater {
        bar.stop()
        frame?.dispose()
        frame = null
    }

    private object Palette {
        val card = Color(0x16, 0x1A, 0x21)
        val outline = Color(0x2A, 0x31, 0x40)
        val text = Color(0xE6, 0xE9, 0xEF)
        val dim = Color(0x9A, 0xA4, 0xB2)
        val faint = Color(0x6B, 0x75, 0x84)
        val accent = Color(0x3D, 0xA5, 0xF4)
        val track = Color(0x24, 0x2B, 0x38)
    }

    private class Card : JPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = 22.0
            g2.color = Palette.card
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc))
            g2.color = Palette.outline
            g2.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc))
        }
    }

    private class SetupProgressBar : JPanel() {
        var target = 0f
        private var shown = 0f
        private var timer: javax.swing.Timer? = null

        init {
            preferredSize = Dimension(100, 8)
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
        }

        fun start() {
            timer = javax.swing.Timer(16) {
                shown += (target - shown) * 0.12f
                repaint()
            }.also { it.start() }
        }

        fun stop() {
            timer?.stop()
            timer = null
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height.toDouble()
            val w = width.toDouble()
            g2.color = Palette.track
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))
            g2.color = Palette.accent
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w * shown.coerceIn(0f, 1f), h, h, h))
        }
    }

    private fun uiFont(style: Int, size: Float): Font {
        val family = listOf("Segoe UI", "SF Pro Text", "Helvetica Neue", Font.SANS_SERIF)
            .firstOrNull { it in GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames }
            ?: Font.SANS_SERIF
        return Font(family, style, 1).deriveFont(size)
    }
}

/**
 * Parses Windows setup argv for the branded installer / updater.
 * Recognises `--silent`, `/S`, `--relaunch`, and optional `--dir`.
 */
object WindowsSetupArgs {
    fun isSilent(args: Array<String>): Boolean =
        args.any { it.equals("--silent", true) || it.equals("/S", true) || it.equals("/silent", true) }

    /** No option page: `/quiet` is what 2.7.1–2.7.3 pass, `--silent` is the branded installer's own flag. */
    fun unattended(args: Array<String>): Boolean =
        isSilent(args) || args.any { it.equals("/quiet", true) || it.equals("/qn", true) }

    fun relaunch(args: Array<String>): Boolean =
        args.any { it.equals("--relaunch", true) }

    fun isUninstall(args: Array<String>): Boolean =
        args.any { it.equals("--uninstall", true) }

    private val KNOWN_FLAGS = setOf(
        "--setup", "--silent", "/s", "/silent", "/quiet", "/qn", "--relaunch", "--uninstall", "--dir",
    )

    private fun isFlagToken(token: String): Boolean {
        val t = token.trim()
        if (KNOWN_FLAGS.any { it.equals(t, ignoreCase = true) }) return true
        if (t.startsWith("--") && !t.contains('\\') && !t.contains('/')) return true
        return false
    }

    /**
     * Extracts the target install directory from `--dir <path>` or `--dir=<path>`.
     * Also reconstructs paths that were split at spaces by unquoted PowerShell `Start-Process -ArgumentList`
     * in older updaters (e.g. `["--dir", "C:\\Users\\Dor", "Farhi\\AppData\\Local\\YarmiplayTV"]`).
     */
    fun installDir(args: Array<String>): String? {
        for (i in args.indices) {
            val arg = args[i]
            if (arg.equals("--dir", ignoreCase = true)) {
                val parts = mutableListOf<String>()
                var j = i + 1
                while (j < args.size && !isFlagToken(args[j])) {
                    parts += args[j]
                    j++
                }
                val joined = parts.joinToString(" ").trim().trim('"').trim()
                return joined.ifEmpty { null }
            }
            if (arg.startsWith("--dir=", ignoreCase = true)) {
                val parts = mutableListOf(arg.substring("--dir=".length))
                var j = i + 1
                while (j < args.size && !isFlagToken(args[j])) {
                    parts += args[j]
                    j++
                }
                val joined = parts.joinToString(" ").trim().trim('"').trim()
                return joined.ifEmpty { null }
            }
        }
        return null
    }
}

private fun translucencySupported(): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
}.getOrDefault(false)
