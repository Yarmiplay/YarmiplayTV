package com.yarmiplaytv.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowBoundsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsFloatingBounds() {
        val file = File(tmp.root, "window.properties")
        WindowBounds.save(file, WindowState(size = DpSize(1400.dp, 900.dp), position = WindowPosition(100.dp, 50.dp)))
        assertEquals(WindowBounds(DpSize(1400.dp, 900.dp), WindowPosition(100.dp, 50.dp), maximized = false), WindowBounds.load(file))
    }

    @Test
    fun maximizedKeepsThePreviousFloatingSize() {
        val file = File(tmp.root, "window.properties")
        WindowBounds.save(file, WindowState(size = DpSize(1000.dp, 700.dp), position = WindowPosition(10.dp, 20.dp)))
        WindowBounds.save(file, WindowState(placement = WindowPlacement.Maximized, size = DpSize(2560.dp, 1400.dp)))
        val loaded = WindowBounds.load(file)
        assertEquals(DpSize(1000.dp, 700.dp), loaded.size)
        assertEquals(WindowPlacement.Maximized, loaded.placement)
    }

    @Test
    fun missingOrTinyValuesFallBack() {
        assertEquals(WindowBounds(), WindowBounds.load(File(tmp.root, "none.properties")))
        val file = File(tmp.root, "small.properties").apply { writeText("width=100\nheight=50\n") }
        val loaded = WindowBounds.load(file)
        assertEquals(DpSize(WindowBounds.MIN_WIDTH.dp, WindowBounds.MIN_HEIGHT.dp), loaded.size)
        assertEquals(WindowPosition.PlatformDefault, loaded.position)
    }
}
