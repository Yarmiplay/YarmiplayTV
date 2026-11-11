package com.yarmiplaytv.player

/**
 * The spectrum drawn while a file has audio and no real video track.
 *
 * mpv builds it with a libavfilter graph. The graph's `[ao]` pad is the untouched split of the
 * decoded audio, so the samples that reach the speakers are the same ones playback already used.
 * Voice chat never goes through this graph. Video files never have the property set at all.
 */
object AudioOnly {
    /** True when [tracks] include audio and every video track is a still image (cover art). */
    fun isAudioOnly(tracks: List<Track>): Boolean =
        tracks.any { it.type == TrackType.AUDIO } && tracks.none { it.type == TrackType.VIDEO && !it.image }

    /** The audio track the spectrum should follow: the selected one, else the first. */
    fun audioId(tracks: List<Track>): Int? {
        if (!isAudioOnly(tracks)) return null
        return tracks.firstOrNull { it.type == TrackType.AUDIO && it.selected }?.id
            ?: tracks.firstOrNull { it.type == TrackType.AUDIO }?.id
    }
}

/** libavfilter graphs. [bars] takes the theme colour; [spectrum] is the older filter, in blue. */
object AudioVisualizerGraph {
    /** The app's night-blue accent, used until the screen says which theme is showing. */
    const val DEFAULT_ACCENT: Int = 0x3DA5F4

    /**
     * Spectrum bars in [rgb] (`0xRRGGBB`). `[ao]` is `asplit`'s first pad, with no filter of its own,
     * so playback audio is not resampled or delayed by the picture.
     */
    fun bars(audioTrackId: Int, rgb: Int = DEFAULT_ACCENT): String {
        val color = "%06X".format(rgb and 0xFFFFFF)
        return "[aid$audioTrackId]asplit=2[ao][vis];" +
            "[vis]showfreqs=s=960x540:mode=bar:cmode=combined:colors=0x$color:" +
            "fscale=log:ascale=cbrt:win_size=2048:win_func=hann:overlap=0:rate=30:averaging=1[vo]"
    }

    /** Heatmap spectrum, used when [bars] isn't in this libmpv build. */
    fun spectrum(audioTrackId: Int): String =
        "[aid$audioTrackId]asplit=2[ao][vis];" +
            "[vis]showspectrum=s=960x540:mode=combined:color=cool:scale=cbrt:fscale=log:" +
            "slide=replace:win_func=hann:overlap=0:fps=30:legend=0[vo]"
}

/** What to assign to mpv's `lavfi-complex`. [Leave] means don't touch the property. */
sealed interface VisualizerCommand {
    data object Leave : VisualizerCommand
    data class Set(val graph: String) : VisualizerCommand
}

/**
 * Decides when the spectrum graph is installed. It never asks for a change while a real video
 * track is playing, and it asks for the property to be cleared only after this session set it.
 */
class AudioVisualizerController {
    var accent: Int = AudioVisualizerGraph.DEFAULT_ACCENT
        private set

    private var applied: String? = null
    private var aid: Int? = null
    /** 0: themed bars, 1: heatmap fallback, 2: this file's filters were rejected. */
    private var mode = 0

    /** A new file. Clears a graph this session installed; leaves mpv alone otherwise. */
    fun onNewFile(): VisualizerCommand {
        mode = 0
        aid = null
        return clear()
    }

    /**
     * [tracks] just changed. [canRender] is false when nothing is consuming frames (the Android
     * surface is gone): the graph is cleared so an unconsumed picture can't hold the audio back.
     */
    fun onTracks(tracks: List<Track>, canRender: Boolean): VisualizerCommand {
        val id = AudioOnly.audioId(tracks)
        if (id == null || !canRender) {
            if (id == null) mode = 0
            aid = null
            return clear()
        }
        if (mode == 2) return clear()
        aid = id
        val graph = if (mode == 0) AudioVisualizerGraph.bars(id, accent) else AudioVisualizerGraph.spectrum(id)
        if (graph == applied) return VisualizerCommand.Leave
        applied = graph
        return VisualizerCommand.Set(graph)
    }

    /** The theme colour changed. Rebuilt bars keep the same audio split; a fallback graph has no colour. */
    fun onColor(rgb: Int): VisualizerCommand {
        val color = rgb and 0xFFFFFF
        if (color == accent) return VisualizerCommand.Leave
        accent = color
        val id = aid
        if (applied == null || mode != 0 || id == null) return VisualizerCommand.Leave
        val graph = AudioVisualizerGraph.bars(id, accent)
        if (graph == applied) return VisualizerCommand.Leave
        applied = graph
        return VisualizerCommand.Set(graph)
    }

    /**
     * The graph just assigned was rejected. The first rejection tries the heatmap; the second gives
     * up for this file and clears anything that was set.
     */
    fun rejected(): VisualizerCommand {
        applied = null
        if (mode >= 1) {
            mode = 2
            aid = null
            return VisualizerCommand.Set("")
        }
        mode = 1
        val id = aid ?: return VisualizerCommand.Set("")
        val graph = AudioVisualizerGraph.spectrum(id)
        applied = graph
        return VisualizerCommand.Set(graph)
    }

    /** Drops the remembered graph. True when mpv had been given one and the caller must clear it. */
    fun disarm(): Boolean {
        val had = applied != null
        applied = null
        aid = null
        return had
    }

    private fun clear(): VisualizerCommand {
        if (applied == null) return VisualizerCommand.Leave
        applied = null
        return VisualizerCommand.Set("")
    }
}

/**
 * Assigns [command] with [setGraph]. A non-empty graph that [setGraph] rejects is replaced by the fallback,
 * then cleared. Returns whether a spectrum is showing, or null when [command] asked for no change.
 * [setGraph] of an empty string clears whatever this session installed.
 */
fun AudioVisualizerController.commit(command: VisualizerCommand, setGraph: (String) -> Boolean): Boolean? = when (command) {
    VisualizerCommand.Leave -> null
    is VisualizerCommand.Set -> {
        val ok = runCatching { setGraph(command.graph) }.getOrDefault(false)
        if (command.graph.isEmpty() || ok) command.graph.isNotEmpty() && ok
        else commit(rejected(), setGraph)
    }
}

/**
 * [audioOnly] files drop the spectrum's pretend video size so picture-in-picture and the codec line
 * keep treating them as music. A real video keeps whatever the player already reported.
 */
fun PlaybackState.withAudioPresentation(
    tracks: List<Track>,
    title: String?,
    artist: String?,
    visualizer: Boolean = audioVisualizer,
): PlaybackState {
    if (!AudioOnly.isAudioOnly(tracks)) {
        return copy(audioOnly = false, audioVisualizer = false, mediaTitle = null, mediaArtist = null)
    }
    return copy(
        audioOnly = true,
        audioVisualizer = visualizer,
        mediaTitle = title?.takeIf { it.isNotBlank() },
        mediaArtist = artist?.takeIf { it.isNotBlank() },
        videoWidth = 0,
        videoHeight = 0,
        displayAspect = 0.0,
        videoCodec = null,
        hwdec = null,
    )
}

/** Title and artist as mpv reports them on `metadata/by-key/…`. */
object MediaTags {
    fun title(get: (String) -> String?): String? = first(get, "Title", "title", "TITLE")

    fun artist(get: (String) -> String?): String? = first(
        get,
        "Artist", "artist", "ARTIST",
        "Album_Artist", "album_artist", "ALBUMARTIST", "AlbumArtist",
    )

    private fun first(get: (String) -> String?, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get("metadata/by-key/$key")?.trim()?.takeIf { it.isNotEmpty() } }
}

/**
 * Idle spectrum motion for when the audio samples themselves aren't available, and the smoothing
 * used when they are. [approach] with a zero [dt] holds [current] still, which is the paused frame.
 */
object SpectrumMotion {
    const val BARS = 48

    fun idle(phase: Float, bars: Int = BARS): FloatArray = FloatArray(bars) { i ->
        val n = if (bars <= 1) 0.5f else i / (bars - 1f)
        val lobe = kotlin.math.sin(Math.PI * n).toFloat()
        val flick = 0.55f + 0.45f * kotlin.math.sin(phase * 1.6f + i * 0.45f)
        val slow = 0.75f + 0.25f * kotlin.math.sin(phase * 0.7f + n * 4f)
        (0.08f + 0.72f * lobe * flick * slow).coerceIn(0.04f, 1f)
    }

    /** Moves [current] toward [target]. A zero [dt] returns a copy of [current] unchanged. */
    fun approach(current: FloatArray, target: FloatArray, dt: Float, bars: Int = BARS): FloatArray {
        val out = FloatArray(bars)
        if (dt <= 0f) {
            for (i in 0 until bars) out[i] = current.getOrElse(i) { 0f }
            return out
        }
        for (i in 0 until bars) {
            val c = current.getOrElse(i) { 0f }
            val t = target.getOrElse(i) { 0f }
            val k = if (t > c) 14f else 4.5f
            val a = (1f - kotlin.math.exp(-k * dt)).coerceIn(0f, 1f)
            out[i] = (c + (t - c) * a).coerceIn(0f, 1f)
        }
        return out
    }
}

/**
 * Whether a libavfilter shared library actually contains the filters a graph names.
 * A missing filter must not be installed: mpv can stall playback on a graph it cannot build.
 * A null [library] means the file could not be read, so the caller tries the graph and checks
 * that mpv kept it.
 */
object LavfiProbe {
    fun contains(library: ByteArray, name: String): Boolean {
        if (name.isEmpty()) return false
        val needle = name.encodeToByteArray()
        val last = library.size - needle.size
        var i = 0
        while (i <= last) {
            var matched = true
            for (j in needle.indices) {
                if (library[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched && (i + needle.size == library.size || library[i + needle.size].toInt() == 0)) {
                val before = if (i == 0) 0 else library[i - 1].toInt() and 0xFF
                if (before == 0 || !isNameChar(before)) return true
            }
            i++
        }
        return false
    }

    /** [graph] is safe to assign when every filter it depends on is in [library]. */
    fun supports(library: ByteArray?, graph: String): Boolean {
        if (graph.isEmpty()) return true
        if (library == null) return true
        val needed = ArrayList<String>(4)
        needed.add("asplit")
        needed.add("aresample")
        if ("showfreqs" in graph) needed.add("showfreqs")
        if ("showspectrum" in graph) needed.add("showspectrum")
        return needed.all { contains(library, it) }
    }

    private fun isNameChar(byte: Int): Boolean =
        byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code || byte in '0'.code..'9'.code || byte == '_'.code
}
