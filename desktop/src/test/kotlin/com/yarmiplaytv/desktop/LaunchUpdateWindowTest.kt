package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

class LaunchUpdateWindowTest {
    @Test
    fun theStatusSaysTheVersionAndHowFarTheDownloadIs() {
        assertEquals("Version 1.8.2 · Starting the download", LaunchUpdateWindow.statusText("1.8.2", null))
        assertEquals("Version 1.8.2 · Starting the download", LaunchUpdateWindow.statusText("1.8.2", 0f))
        assertEquals("Version 1.8.2 · 42%", LaunchUpdateWindow.statusText("1.8.2", 0.425f))
        assertEquals("Version 1.8.2 · 100%", LaunchUpdateWindow.statusText("1.8.2", 1f))
    }

    @Test
    fun theLogoIsDrawnAtEverySizeTheTaskbarAsksFor() {
        for (size in listOf(16, 32, 64, 128)) {
            val image = LaunchUpdateWindow.logoImage(size)
            assertEquals(size, image.width)
            // The blue triangle is there, the corner is see-through.
            val blue = image.getRGB(size * 40 / 108, size / 2)
            assertTrue("blue at $size px", (blue and 0xFF) > 0xC0 && (blue shr 24 and 0xFF) == 0xFF)
            assertEquals(0, image.getRGB(0, 0) ushr 24)
        }
    }

    @Test
    fun theWindowShowsAndSkipsWithEsc() {
        assumeFalse("needs a screen", GraphicsEnvironment.isHeadless())
        val skips = AtomicInteger()
        val ui = LaunchUpdateWindow()
        ui.show(Update("1.8.2", "https://tv.yarmiplay.com/", "https://tv.yarmiplay.com/YarmiplayTV.msi", null)) { skips.incrementAndGet() }
        ui.progress(0.42f)
        Thread.sleep(1_200)
        var frame = ui.window
        SwingUtilities.invokeAndWait { frame = ui.window }
        val window = frame!!
        System.getProperty("yarmiplaytv.screenshotDir")?.let { dir ->
            // Painted rather than captured from the screen: a test run may not own the screen.
            val image = java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            SwingUtilities.invokeAndWait { window.contentPane.paint(image.createGraphics()) }
            File(dir, "launch_update.png").also { it.parentFile.mkdirs() }.let { ImageIO.write(image, "png", it) }
        }
        assertTrue(window.isUndecorated)
        assertNotEquals(0, window.iconImages.size)
        SwingUtilities.invokeAndWait {
            window.rootPane.getActionForKeyStroke(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0))
                .actionPerformed(java.awt.event.ActionEvent(window, 0, "esc"))
        }
        assertEquals(1, skips.get())
        ui.close()
    }
}
