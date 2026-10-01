package com.syncplaytv.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadXmlImageVector
import com.syncplaytv.AppContainer
import com.syncplaytv.local.FileLocalLibrary
import com.syncplaytv.player.Player
import org.xml.sax.InputSource

actual fun Modifier.exposeTestTags(): Modifier = this

/**
 * Back handlers in composition order; the desktop window calls [dispatch] for Esc and the mouse back
 * button. Like Android's dispatcher, the most recently added enabled handler wins.
 */
object DesktopBack {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** Returns false when no handler is enabled. */
    fun dispatch(): Boolean {
        val entry = synchronized(entries) { entries.lastOrNull { it.enabled } } ?: return false
        entry.onBack()
        return true
    }

    internal fun add(entry: Entry) = synchronized(entries) { entries.add(entry) }
    internal fun remove(entry: Entry) = synchronized(entries) { entries.remove(entry) }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val entry = remember { DesktopBack.Entry(enabled) { current() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        DesktopBack.add(entry)
        onDispose { DesktopBack.remove(entry) }
    }
}

/** Set by the desktop app to the libmpv render view; until then the player area stays empty. */
object DesktopVideo {
    var surface: @Composable (Player, Modifier) -> Unit = { _, modifier -> Box(modifier) }
}

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier) = DesktopVideo.surface(player, modifier)

/** The desktop window decides about full screen itself (double-click, F). */
@Composable
actual fun FullscreenLandscape() = Unit

@Composable
actual fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember { { DesktopDialogs.pickVideo()?.let { current(FileLocalLibrary.uriOf(it)) } } }
}

@Composable
actual fun rememberFolderPicker(container: AppContainer): () -> Unit =
    remember(container) { { DesktopDialogs.pickFolder()?.let { container.local.addFolder(FileLocalLibrary.uriOf(it)) } } }

actual fun uriFileName(uri: String): String? =
    runCatching { FileLocalLibrary.pathOf(uri).fileName?.toString() }.getOrNull() ?: uri.substringAfterLast('/').ifEmpty { null }

@Composable
actual fun appLogoPainter(): Painter {
    val density = LocalDensity.current
    val vector = remember(density) {
        DesktopVideo::class.java.getResourceAsStream("/ic_logo.xml")!!.use { loadXmlImageVector(InputSource(it), density) }
    }
    return rememberVectorPainter(vector)
}
