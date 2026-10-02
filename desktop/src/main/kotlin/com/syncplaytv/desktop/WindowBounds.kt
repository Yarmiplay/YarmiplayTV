package com.syncplaytv.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.io.File
import java.util.Properties

/** The main window's size, position and maximized state, kept next to the settings between runs. */
internal data class WindowBounds(
    val size: DpSize = DpSize(1280.dp, 800.dp),
    val position: WindowPosition = WindowPosition.PlatformDefault,
    val maximized: Boolean = false,
) {
    val placement: WindowPlacement get() = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating

    companion object {
        fun load(file: File): WindowBounds {
            val p = Properties()
            runCatching { file.inputStream().use(p::load) }.onFailure { return WindowBounds() }
            fun float(key: String) = p.getProperty(key)?.toFloatOrNull()
            val width = float("width") ?: return WindowBounds()
            val height = float("height") ?: return WindowBounds()
            val x = float("x")
            val y = float("y")
            return WindowBounds(
                size = DpSize(width.coerceAtLeast(MIN_WIDTH).dp, height.coerceAtLeast(MIN_HEIGHT).dp),
                position = if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault,
                maximized = p.getProperty("maximized") == "true",
            )
        }

        fun save(file: File, state: WindowState) {
            val p = Properties()
            if (state.placement != WindowPlacement.Floating) {
                // Keep the floating size from before it was maximized.
                runCatching { file.inputStream().use(p::load) }
            } else {
                p["width"] = state.size.width.value.toString()
                p["height"] = state.size.height.value.toString()
                val position = state.position
                if (position.isSpecified) {
                    p["x"] = position.x.value.toString()
                    p["y"] = position.y.value.toString()
                }
            }
            p["maximized"] = (state.placement == WindowPlacement.Maximized).toString()
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { p.store(it, "SyncplayTV window") }
            }
        }

        const val MIN_WIDTH = 800f
        const val MIN_HEIGHT = 520f
    }
}
