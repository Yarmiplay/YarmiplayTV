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
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

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

/**
 * The window shown instead of the app while it updates on launch: a small borderless card in the app's colours
 * with its logo, the version, how far the download is, and "Skip for now" (also Esc, or closing it from the
 * taskbar). It's plain Swing, painted by hand, so nothing of the app (Compose, libmpv) is loaded before the
 * installer replaces it. Drag it anywhere to move it.
 */
internal class LaunchUpdateWindow : LaunchUpdateUi {
    private var frame: JFrame? = null
    private val bar = ProgressBar()
    private val status = JLabel(" ")
    private var version = ""

    /** The window while it's showing (for a look at it in tests). */
    internal val window: JFrame? get() = frame

    override fun show(update: Update, skip: () -> Unit) = SwingUtilities.invokeLater {
        version = update.version
        val title = JLabel("Updating YarmiplayTV").apply {
            foreground = Palette.text
            font = uiFont(Font.BOLD, 16f)
        }
        status.apply {
            foreground = Palette.dim
            font = uiFont(Font.PLAIN, 13f)
            text = statusText(update.version, null)
        }
        val skipButton = FlatButton("Skip for now", skip)
        val hint = JLabel("YarmiplayTV opens again when it's done").apply {
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
            add(LogoIcon(52), BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hint, BorderLayout.WEST)
            add(skipButton, BorderLayout.EAST)
        }
        val card = Card().apply {
            layout = BorderLayout(0, 18)
            border = EmptyBorder(22, 24, 18, 22)
            add(header, BorderLayout.NORTH)
            add(bar, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
        frame = JFrame("YarmiplayTV").apply {
            isUndecorated = true
            // Rounded corners need a see-through window; where that isn't supported the card is simply square.
            if (translucencySupported()) background = Color(0, 0, 0, 0)
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = skip()
            })
            iconImages = listOf(16, 32, 48, 64, 128).map(::logoImage)
            contentPane = card
            rootPane.registerKeyboardAction({ skip() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
            rootPane.defaultButton = null
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
            setSize(maxOf(width, 440), height)
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
        bar.start()
    }

    override fun progress(fraction: Float) = SwingUtilities.invokeLater {
        bar.target = fraction.coerceIn(0f, 1f)
        status.text = statusText(version, fraction)
    }

    override fun close() = SwingUtilities.invokeLater {
        bar.stop()
        frame?.dispose()
    }

    companion object {
        /** "Version 1.8.2 · 42%", or "· Starting the download" before the first bytes arrive. */
        fun statusText(version: String, fraction: Float?): String {
            val f = fraction ?: 0f
            val progress = if (f <= 0f) "Starting the download" else "${(f * 100).toInt().coerceIn(0, 100)}%"
            return "Version $version · $progress"
        }

        /** The app's logo on its dark rounded square, [size] pixels wide. */
        fun logoImage(size: Int): BufferedImage {
            val stream = LaunchUpdateWindow::class.java.getResourceAsStream("/icon.png")
            if (stream != null) {
                return runCatching {
                    stream.use {
                        val master = javax.imageio.ImageIO.read(it)
                        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                        val g = image.createGraphics()
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.drawImage(master, 0, 0, size, size, null)
                        g.dispose()
                        image
                    }
                }.getOrNull() ?: fallbackLogoImage(size)
            }
            return fallbackLogoImage(size)
        }

        private fun fallbackLogoImage(size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            try {
                paintLogo(g, size)
            } finally {
                g.dispose()
            }
        }

        private fun paintLogo(g: Graphics2D, size: Int) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val s = size / 108.0
            g.color = Palette.logoBackground
            g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), size * 0.44, size * 0.44))
            fun triangle(x: Double, top: Double, bottom: Double, tip: Double) = Path2D.Double().apply {
                moveTo(x * s, top * s)
                lineTo(x * s, bottom * s)
                lineTo(tip * s, 54 * s)
                closePath()
            }
            g.color = Palette.accent
            g.fill(triangle(30.0, 28.0, 80.0, 70.0))
            g.color = Color(255, 255, 255, 0xCC)
            g.fill(triangle(46.0, 34.0, 74.0, 78.0))
        }

        private fun translucencySupported(): Boolean = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)

        /** Segoe UI on Windows, the system's sans-serif elsewhere. */
        private fun uiFont(style: Int, size: Float): Font {
            val family = listOf("Segoe UI", "SF Pro Text", "Helvetica Neue", "Cantarell", "Ubuntu")
                .firstOrNull { it in GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames }
                ?: Font.SANS_SERIF
            return Font(family, style, 1).deriveFont(size)
        }

        private fun Graphics.smooth(): Graphics2D = (this as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }
    }

    /** The app's colours (AppColors). */
    private object Palette {
        val card = Color(0x16, 0x1A, 0x21)
        val outline = Color(0x2A, 0x31, 0x40)
        val text = Color(0xE6, 0xE9, 0xEF)
        val dim = Color(0x9A, 0xA4, 0xB2)
        val faint = Color(0x6B, 0x75, 0x84)
        val accent = Color(0x3D, 0xA5, 0xF4)
        val track = Color(0x24, 0x2B, 0x38)
        val logoBackground = Color(0x0E, 0x11, 0x16)
        val buttonHover = Color(0x24, 0x2B, 0x38)
    }

    /** The rounded card everything sits on. */
    private class Card : JPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create().smooth()
            try {
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

    private class LogoIcon(private val size: Int) : JComponent() {
        init {
            preferredSize = Dimension(size, size)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                paintLogo(g2, size)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * A thin rounded bar that glides to the download's progress; before the first bytes, a short segment sweeps
     * across instead.
     */
    private class ProgressBar : JComponent() {
        @Volatile var target = 0f
        private var shown = 0f
        private var sweep = 0f
        private val timer = Timer(16) {
            shown += (target - shown) * 0.18f
            sweep = (sweep + 0.012f) % 1.4f
            repaint()
        }

        init {
            preferredSize = Dimension(380, 8)
        }

        fun start() = timer.start()
        fun stop() = timer.stop()

        override fun paintComponent(g: Graphics) {
            val g2 = g.create().smooth()
            try {
                val h = height.toDouble()
                val w = width.toDouble()
                g2.color = Palette.track
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))
                g2.color = Palette.accent
                if (target <= 0f) {
                    val segment = w * 0.28
                    val x = (sweep - 0.4) * w
                    val clip = g2.clip
                    g2.clip(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))
                    g2.fill(RoundRectangle2D.Double(x, 0.0, segment, h, h, h))
                    g2.clip = clip
                } else {
                    g2.fill(RoundRectangle2D.Double(0.0, 0.0, (w * shown).coerceAtLeast(h), h, h, h))
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** A quiet text button that lights up under the pointer. */
    private class FlatButton(label: String, onClick: () -> Unit) : JButton(label) {
        private var hover = false

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            foreground = Palette.text
            font = uiFont(Font.PLAIN, 13f)
            border = EmptyBorder(7, 14, 7, 14)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create().smooth()
            try {
                if (hover || model.isPressed) {
                    g2.color = Palette.buttonHover
                    g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 12.0, 12.0))
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
