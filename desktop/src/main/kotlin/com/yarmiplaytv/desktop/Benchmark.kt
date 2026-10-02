package com.yarmiplaytv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.management.OperatingSystemMXBean
import com.yarmiplaytv.player.desktop.LatencyCompensator
import com.yarmiplaytv.player.desktop.LibMpv
import com.yarmiplaytv.player.desktop.MpvCore
import com.yarmiplaytv.player.desktop.MpvEvent
import com.yarmiplaytv.player.desktop.VideoRenderer
import com.yarmiplaytv.player.desktop.VideoSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * `--benchmark <file> [--seconds N] [--fullscreen] [--render auto|gl|sw] [--hwdec X] [--resize] [--seek] [--overlays] [--focus-changes] [--compose-fullscreen] [--out report.txt]`
 *
 * Plays the file and checks the targets: no dropped or delayed frames, every rendered frame drawn, A/V sync
 * within 20 ms. Exits with 0 when they're met, 1 otherwise.
 */
internal class BenchmarkOptions(args: List<String>) {
    val file: String = args.getOrNull(args.indexOf("--benchmark") + 1) ?: error("--benchmark needs a file")
    val seconds: Int = value(args, "--seconds")?.toInt() ?: 120
    val fullscreen = "--fullscreen" in args
    val render: String = value(args, "--render") ?: "auto"
    val hwdec: String = value(args, "--hwdec") ?: "auto"
    val resize = "--resize" in args
    val seek = "--seek" in args
    val overlays = "--overlays" in args
    /** Moves focus to another window and back every few seconds, as switching to a chat would. */
    val focusChanges = "--focus-changes" in args
    /** Compose's own fullscreen placement instead of [Fullscreen], for comparison. */
    val composeFullscreen = "--compose-fullscreen" in args
    val out: String? = value(args, "--out")
    val noCompensation = "--no-latency-compensation" in args

    private fun value(args: List<String>, name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
}

private const val WARMUP_MS = 3_000L
private val SEEK_TARGETS = listOf(20, 60, 5, 90, 40)

private var focusThief: javax.swing.JFrame? = null

/** Activates a small window of ours ([away]) or the benchmark window again. */
private fun switchFocus(window: java.awt.Window, away: Boolean) {
    if (away) {
        val thief = focusThief ?: javax.swing.JFrame("SyncplayTV benchmark: focus elsewhere").apply {
            setSize(360, 120)
            setLocation(40, 40)
            focusThief = this
        }
        thief.isVisible = true
        thief.toFront()
        thief.requestFocus()
    } else {
        focusThief?.isVisible = false
        window.toFront()
        window.requestFocus()
    }
}

internal fun runBenchmark(options: BenchmarkOptions) {
    val core = MpvCore(mapOf("hwdec" to options.hwdec, "keep-open" to "no"), logLevel = "warn")
    val loaded = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()
    core.startEvents { event ->
        when (event) {
            MpvEvent.FileLoaded -> loaded.complete(Unit)
            is MpvEvent.EndFile -> ended.complete(Unit)
            is MpvEvent.Log -> System.err.println("mpv [${event.prefix}] ${event.level}: ${event.text}")
            else -> Unit
        }
    }
    val renderer = VideoRenderer.create(core.lib, core.handle, options.render) { System.err.println("GPU renderer unavailable, using software: ${it.message}") }
    var exitCode = 1
    val minimized = AtomicInteger()

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 720.dp))
        val fullscreen = remember { Fullscreen(windowState) }
        var overlay by remember { mutableStateOf("Loading…") }
        var panel by remember { mutableStateOf(false) }
        var awtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        val compensator = remember {
            LatencyCompensator(core, renderer) { awtWindow?.let(::refreshRateOf) ?: displayRefreshRate() }
        }
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "SyncplayTV benchmark") {
            LaunchedEffect(Unit) {
                awtWindow = window
                if (!options.composeFullscreen) fullscreen.attach(window)
                if (options.fullscreen) fullscreen.set(true)
                window.addWindowStateListener { if (it.newState and java.awt.Frame.ICONIFIED != 0) minimized.incrementAndGet() }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoSurface(renderer, Modifier.fillMaxSize())
                Text(
                    overlay,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0x99000000)).padding(8.dp),
                )
                AnimatedVisibility(
                    panel,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    Column(
                        Modifier.fillMaxHeight().width(360.dp).background(Color(0xCC101010)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(12) { i ->
                            Text("Playlist entry ${i + 1}", color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0x33FFFFFF)).padding(10.dp))
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            core.command("loadfile", File(options.file).absolutePath)
            loaded.await()
            if (!options.noCompensation) compensator.start(this)
            delay(WARMUP_MS)
            val report = withContext(Dispatchers.Default) {
                measure(
                    core, renderer, options,
                    onProgress = { overlay = it },
                    resizeTo = { w, h -> windowState.size = DpSize(w.dp, h.dp) },
                    togglePanel = { panel = !panel },
                    switchFocus = { away -> awtWindow?.let { switchFocus(it, away) } },
                    minimized = minimized,
                    ended = ended,
                )
            }
            println(report.text)
            options.out?.let { File(it).writeText(report.text) }
            exitCode = if (report.passed) 0 else 1
            exitApplication()
        }
    }
    renderer.close()
    core.close()
    exitProcess(exitCode)
}

private class Report(val text: String, val passed: Boolean)

private suspend fun measure(
    core: MpvCore,
    renderer: VideoRenderer,
    options: BenchmarkOptions,
    onProgress: (String) -> Unit,
    resizeTo: (Int, Int) -> Unit,
    togglePanel: () -> Unit,
    switchFocus: (away: Boolean) -> Unit,
    minimized: AtomicInteger,
    ended: CompletableDeferred<Unit>,
): Report {
    val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    val video = "${core.getLong("width")}x${core.getLong("height")} @ %.3f fps, %s, hwdec=%s".format(
        core.getDouble("container-fps") ?: 0.0, core.getString("video-codec") ?: "?", core.getString("hwdec-current") ?: "no",
    )
    val fps = core.getDouble("container-fps") ?: 0.0
    val baseMinimized = minimized.get()
    fun counter(name: String) = core.getLong(name) ?: 0L
    val baseVoDrops = counter("frame-drop-count")
    val baseDecoderDrops = counter("decoder-frame-drop-count")
    val baseDelayed = counter("vo-delayed-frame-count")
    val frames = renderer.frames
    val basePublished = frames.published
    val basePresented = frames.presented
    val baseOverwritten = frames.overwritten
    renderer.stats.reset()

    val start = System.nanoTime()
    var maxAvsync = 0.0
    var sumAvsync = 0.0
    var avsyncSamples = 0
    var cpuSum = 0.0
    var cpuSamples = 0
    var lastResize = 0L
    var lastSeek = 0L
    var lastPanel = 0L
    var lastFocus = 0L
    var focusAway = false
    var seeks = 0
    var big = false
    os.processCpuLoad
    while (true) {
        delay(250)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (elapsedMs >= options.seconds * 1000L || ended.isCompleted) break
        core.getDouble("avsync")?.let {
            maxAvsync = maxOf(maxAvsync, abs(it))
            sumAvsync += abs(it)
            avsyncSamples++
        }
        os.processCpuLoad.takeIf { it >= 0 }?.let { cpuSum += it; cpuSamples++ }
        if (options.resize && elapsedMs - lastResize >= 2_000) {
            lastResize = elapsedMs
            big = !big
            withContext(Dispatchers.Main) { if (big) resizeTo(1920, 1080) else resizeTo(1280, 720) }
        }
        if (options.seek && elapsedMs - lastSeek >= 10_000) {
            lastSeek = elapsedMs
            // Absolute targets back and forth, so playback never reaches the end of the clip.
            core.command("seek", SEEK_TARGETS[seeks++ % SEEK_TARGETS.size].toString(), "absolute")
        }
        if (options.overlays && elapsedMs - lastPanel >= 1_500) {
            lastPanel = elapsedMs
            withContext(Dispatchers.Main) { togglePanel() }
        }
        if (options.focusChanges && elapsedMs - lastFocus >= 3_000) {
            lastFocus = elapsedMs
            focusAway = !focusAway
            withContext(Dispatchers.Main) { switchFocus(focusAway) }
        }
        val drops = (counter("frame-drop-count") - baseVoDrops) + (counter("decoder-frame-drop-count") - baseDecoderDrops)
        // Built off the UI thread: mpv property reads can block on mpv's core lock.
        val progress = "%s · %s · %s\n%.0fs  drops %d  delayed %d  skipped %d  avsync %+.1f ms (max %.1f)".format(
            renderer.kind, core.getString("hwdec-current") ?: "?", frames.lastSize,
            elapsedMs / 1000.0, drops, counter("vo-delayed-frame-count") - baseDelayed, frames.overwritten - baseOverwritten,
            (core.getDouble("avsync") ?: 0.0) * 1000, maxAvsync * 1000,
        )
        withContext(Dispatchers.Main) { onProgress(progress) }
    }
    val seconds = (System.nanoTime() - start) / 1e9
    val stats = renderer.stats
    val n = stats.frames.get().coerceAtLeast(1)
    val voDrops = counter("frame-drop-count") - baseVoDrops
    val decoderDrops = counter("decoder-frame-drop-count") - baseDecoderDrops
    val delayed = counter("vo-delayed-frame-count") - baseDelayed
    val published = frames.published - basePublished
    val presented = frames.presented - basePresented
    val overwritten = frames.overwritten - baseOverwritten
    val minimizedCount = minimized.get() - baseMinimized
    val expected = fps * seconds
    val size = frames.lastSize
    val latencyN = stats.latencySamples.get().coerceAtLeast(1)
    val dueToDrawnMs = stats.latencyTotal.get() / 1e6 / latencyN
    val refreshMs = 1000.0 / displayRefreshRate()
    // mpv only sees its own clock; the frame reaches the screen after the hand-off to the UI plus one refresh.
    val onScreenAvMs = maxAvsync * 1000 + dueToDrawnMs + refreshMs - audioDelayMs(core)
    val checks = listOf(
        "no frames dropped by mpv (vo $voDrops, decoder $decoderDrops)" to (voDrops + decoderDrops == 0L),
        "no delayed frames ($delayed)" to (delayed == 0L),
        "every rendered frame drawn (dropped $overwritten)" to (overwritten == 0L),
        "frames drawn ≈ video frames (%d of %.0f)".format(presented, expected) to (options.seek || presented >= expected * 0.99),
        "A/V sync on screen within 20 ms (%.1f ms)".format(onScreenAvMs) to (abs(onScreenAvMs) <= 20.0),
        "window never minimized ($minimizedCount)" to (minimizedCount == 0),
    )
    val text = buildString {
        appendLine("=== SyncplayTV render benchmark ===")
        appendLine("file:        ${File(options.file).name}")
        appendLine("video:       $video")
        val modes = listOfNotNull(
            if (options.fullscreen) (if (options.composeFullscreen) "fullscreen (Compose)" else "fullscreen") else "windowed",
            "resizing".takeIf { options.resize },
            "seeking".takeIf { options.seek },
            "overlays".takeIf { options.overlays },
            "focus changes".takeIf { options.focusChanges },
        )
        appendLine("renderer:    ${renderer.kind}, output $size, ${modes.joinToString()}")
        appendLine("measured:    %.1f s (after %d s warm-up)".format(seconds, WARMUP_MS / 1000))
        appendLine("frames:      rendered ${stats.frames.get()}, published $published, drawn $presented, expected %.0f".format(expected))
        appendLine("mpv render:  avg %.2f ms, max %.2f ms".format(stats.renderTotal.get() / 1e6 / n, stats.renderMax.get() / 1e6))
        appendLine("readback:    avg %.2f ms, max %.2f ms".format(stats.readbackTotal.get() / 1e6 / n, stats.readbackMax.get() / 1e6))
        appendLine("due → drawn: avg %.2f ms, max %.2f ms, %d frames > 16 ms late; display refresh %.2f ms".format(dueToDrawnMs, stats.latencyMax.get() / 1e6, stats.late.get(), refreshMs))
        appendLine("A/V (mpv):   avg %.1f ms, max %.1f ms; audio-delay %.1f ms".format(if (avsyncSamples > 0) sumAvsync / avsyncSamples * 1000 else 0.0, maxAvsync * 1000, audioDelayMs(core)))
        appendLine("A/V screen:  %.1f ms (mpv max + due → drawn + 1 refresh − audio-delay)".format(onScreenAvMs))
        appendLine("CPU:         %.1f%% of all cores (%d cores)".format(if (cpuSamples > 0) cpuSum / cpuSamples * 100 else 0.0, Runtime.getRuntime().availableProcessors()))
        checks.forEach { (label, ok) -> appendLine("${if (ok) "PASS" else "FAIL"}  $label") }
    }
    return Report(text, checks.all { it.second })
}

private fun audioDelayMs(core: MpvCore) = (core.getDouble("audio-delay") ?: 0.0) * 1000

private fun refreshRateOf(window: java.awt.Window): Double =
    window.graphicsConfiguration?.device?.displayMode?.refreshRate?.takeIf { it > 0 }?.toDouble() ?: displayRefreshRate()

private fun displayRefreshRate(): Double =
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
        .takeIf { it > 0 }?.toDouble() ?: 60.0

internal fun libmpvVersion(): String = runCatching {
    val v = LibMpv.load().mpv_client_api_version()
    "${v shr 16}.${v and 0xffff}"
}.getOrElse { "unavailable: ${it.message}" }
