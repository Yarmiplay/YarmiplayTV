package com.yarmiplaytv.player.desktop

import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.player.PlayerEvent
import com.yarmiplaytv.player.Track
import com.yarmiplaytv.player.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.URI
import java.nio.file.Paths

data class DesktopMpvOptions(
    /** mpv --hwdec value; "no" forces software decoding. */
    val hwdec: String = "auto",
    val audioLanguages: String = "",
    val subtitleLanguages: String = "eng,en",
    val cacheMegabytes: Int = 128,
    /** "auto" (GPU, falling back to software), "gl" or "sw". */
    val render: String = System.getProperty("yarmiplaytv.render") ?: "auto",
    val logLevel: String = "warn",
)

/**
 * libmpv-backed [Player] for the desktop app. Video goes through the render API into [renderer], which the UI
 * draws with [VideoSurface]. Like the Android player, the caches below are updated both from mpv's event
 * thread and immediately when a command is issued, so reads always reflect the last requested state.
 */
class DesktopMpvPlayer(
    options: DesktopMpvOptions = DesktopMpvOptions(),
    refreshRateHz: () -> Double = ::defaultRefreshRate,
) : Player, AutoCloseable {
    private val core = MpvCore(
        buildMap {
            put("hwdec", options.hwdec)
            put("tls-verify", "no")
            put("cache", "yes")
            put("demuxer-max-bytes", "${options.cacheMegabytes}MiB")
            put("demuxer-max-back-bytes", "${options.cacheMegabytes / 2}MiB")
            put("save-position-on-quit", "no")
            put("osd-duration", "2500")
            put("sub-scale-with-window", "yes")
            put("user-agent", "YarmiplayTV")
            put("audio-client-name", "YarmiplayTV")
            if (options.audioLanguages.isNotBlank()) put("alang", options.audioLanguages)
            if (options.subtitleLanguages.isNotBlank()) put("slang", options.subtitleLanguages)
        },
        logLevel = options.logLevel,
    )

    val renderer: VideoRenderer = VideoRenderer.create(core.lib, core.handle, options.render) {
        System.err.println("mpv: GPU renderer unavailable, using software: ${it.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val compensator = LatencyCompensator(core, renderer, refreshRateHz)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()
    private val _volume = MutableStateFlow(100.0)
    /** mpv volume in percent (0-130). */
    val volume: StateFlow<Double> = _volume.asStateFlow()

    @Volatile private var cachedPosition = 0.0
    @Volatile private var cachedPositionAt = 0L
    @Volatile private var cachedPaused = true
    @Volatile private var cachedSpeed = 1.0
    @Volatile private var currentUrl: String? = null
    /** See MpvPlayer: counting loadfiles and START_FILEs tells whether the file mpv is opening is the latest load. */
    @Volatile private var loadsIssued = 0
    @Volatile private var latestLoad = 0
    private var filesStarted = 0
    private var openingLatest = false
    @Volatile private var openError: String? = null

    init {
        listOf(
            "time-pos" to LibMpv.FORMAT_DOUBLE,
            "duration" to LibMpv.FORMAT_DOUBLE,
            "pause" to LibMpv.FORMAT_FLAG,
            "paused-for-cache" to LibMpv.FORMAT_FLAG,
            "seeking" to LibMpv.FORMAT_FLAG,
            "eof-reached" to LibMpv.FORMAT_FLAG,
            "speed" to LibMpv.FORMAT_DOUBLE,
            "volume" to LibMpv.FORMAT_DOUBLE,
            "demuxer-cache-duration" to LibMpv.FORMAT_DOUBLE,
            "track-list" to LibMpv.FORMAT_NONE,
            "video-codec" to LibMpv.FORMAT_STRING,
            "hwdec-current" to LibMpv.FORMAT_STRING,
            "width" to LibMpv.FORMAT_INT64,
            "height" to LibMpv.FORMAT_INT64,
        ).forEach { (name, format) -> core.observe(name, format) }
        core.startEvents(::onEvent)
        compensator.start(scope)
    }

    // --- Commands ----------------------------------------------------------------

    override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) {
        currentUrl = url
        cachedPaused = startPaused
        cachedPosition = startPosition
        cachedPositionAt = System.nanoTime()
        _state.update { PlaybackState(url = url, paused = startPaused, position = startPosition) }
        _tracks.value = emptyList()
        core.setFlag("pause", startPaused)
        // Per-file options as properties rather than loadfile's option argument, whose position changed in mpv 0.38.
        core.setString("force-media-title", mediaTitle ?: "")
        core.setString("start", if (startPosition > 0) startPosition.toString() else "none")
        openError = null
        val target = mpvTarget(url)
        latestLoad = ++loadsIssued
        core.command("loadfile", target, "replace")
    }

    /** file:// URIs become plain paths; a missing local file is reported as such instead of mpv's generic error. */
    private fun mpvTarget(url: String): String {
        if (!url.startsWith("file:")) return url
        val path = runCatching { Paths.get(URI(url)).toString() }.getOrElse { return url }
        if (!File(path).isFile) openError = "File not found"
        return path
    }

    override fun stop() {
        currentUrl = null
        latestLoad = -1
        core.command("stop")
        _state.value = PlaybackState()
        _tracks.value = emptyList()
    }

    override fun setPaused(paused: Boolean) {
        snapshotPosition()
        cachedPaused = paused
        _state.update { it.copy(paused = paused) }
        core.setFlag("pause", paused)
    }

    override fun seek(position: Double) {
        val target = position.coerceAtLeast(0.0)
        cachedPosition = target
        cachedPositionAt = System.nanoTime()
        _state.update { it.copy(position = target, seeking = true) }
        core.command("seek", target.toString(), "absolute+exact")
    }

    override fun seekRelative(offset: Double) {
        val dur = _state.value.duration
        val target = (currentPosition() + offset).let { if (dur > 0) it.coerceAtMost(dur - 1) else it }
        seek(target)
    }

    override fun setSpeed(speed: Double) {
        snapshotPosition()
        cachedSpeed = speed
        core.setDouble("speed", speed)
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        core.setString(type.property, id?.toString() ?: "no")
        loadTracks()
    }

    override fun showText(text: String, durationMs: Int) {
        core.command("show-text", text, durationMs.toString())
    }

    fun setVolume(percent: Double) {
        val value = percent.coerceIn(0.0, MAX_VOLUME)
        _volume.value = value
        core.setDouble("volume", value)
    }

    fun changeVolume(delta: Double) = setVolume(_volume.value + delta)

    /** The user's audio delay in seconds, on top of the automatic display-latency compensation. */
    fun setAudioDelay(seconds: Double) {
        compensator.userAudioDelay = seconds
    }

    // --- Fast reads for the sync engine -------------------------------------------

    override val isFileLoaded: Boolean get() = _state.value.fileLoaded
    override val isPaused: Boolean get() = cachedPaused
    override val duration: Double get() = _state.value.duration

    override fun currentPosition(): Double {
        val base = cachedPosition
        val s = _state.value
        if (cachedPaused || s.buffering || s.seeking || !s.fileLoaded) return base
        val elapsed = (System.nanoTime() - cachedPositionAt) / 1e9
        return base + elapsed.coerceIn(0.0, 1.0) * cachedSpeed
    }

    private fun snapshotPosition() {
        cachedPosition = currentPosition()
        cachedPositionAt = System.nanoTime()
    }

    // --- mpv events ------------------------------------------------------------------

    private fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.PropertyChange -> onProperty(event.name, event.value)
            MpvEvent.StartFile -> openingLatest = ++filesStarted == latestLoad
            MpvEvent.FileLoaded -> {
                openingLatest = false
                val duration = core.getDouble("duration") ?: 0.0
                val position = core.getDouble("time-pos") ?: cachedPosition
                cachedPosition = position
                cachedPositionAt = System.nanoTime()
                _state.update { it.copy(fileLoaded = true, duration = duration, position = position, eofReached = false) }
                loadTracks()
                currentUrl?.let { _events.tryEmit(PlayerEvent.FileLoaded(it, duration)) }
            }
            is MpvEvent.EndFile -> {
                _state.update { it.copy(fileLoaded = false) }
                val failedToOpen = openingLatest && filesStarted == latestLoad
                openingLatest = false
                if (failedToOpen) {
                    val reason = openError
                        ?: event.error.takeIf { it < 0 }?.let { "Couldn't open the file (${core.lib.mpv_error_string(it)})" }
                        ?: "Couldn't open the file"
                    _events.tryEmit(PlayerEvent.EndFile(currentUrl, reason))
                }
            }
            MpvEvent.PlaybackRestart -> {
                if (_state.value.seeking) cachedPositionAt = System.nanoTime()
                _state.update { it.copy(seeking = false) }
            }
            is MpvEvent.Log -> onLog(event)
            else -> Unit
        }
    }

    private fun onProperty(name: String, value: Any?) {
        when (name) {
            "track-list" -> loadTracks()
            "width" -> (value as? Long)?.let { v -> _state.update { it.copy(videoWidth = v.toInt()) } }
            "height" -> (value as? Long)?.let { v -> _state.update { it.copy(videoHeight = v.toInt()) } }
            "time-pos" -> (value as? Double)?.let { v ->
                cachedPosition = v
                cachedPositionAt = System.nanoTime()
                _state.update { it.copy(position = v) }
            }
            "duration" -> (value as? Double)?.let { v -> _state.update { it.copy(duration = v) } }
            "speed" -> (value as? Double)?.let { v ->
                cachedSpeed = v
                _state.update { it.copy(speed = v) }
            }
            "volume" -> (value as? Double)?.let { _volume.value = it }
            "demuxer-cache-duration" -> (value as? Double)?.let { v -> _state.update { it.copy(cacheSeconds = v) } }
            "pause" -> (value as? Boolean)?.let { v ->
                if (v != cachedPaused) snapshotPosition()
                cachedPaused = v
                _state.update { it.copy(paused = v) }
            }
            "paused-for-cache" -> (value as? Boolean)?.let { v ->
                snapshotPosition()
                _state.update { it.copy(buffering = v) }
            }
            "seeking" -> (value as? Boolean)?.let { v ->
                if (!v) cachedPositionAt = System.nanoTime()
                _state.update { it.copy(seeking = v) }
            }
            "eof-reached" -> (value as? Boolean)?.let { v -> _state.update { it.copy(eofReached = v) } }
            "video-codec" -> _state.update { it.copy(videoCodec = value as? String) }
            "hwdec-current" -> _state.update { it.copy(hwdec = value as? String) }
        }
    }

    private fun onLog(event: MpvEvent.Log) {
        val level = logLevel(event.level)
        if (level > LOG_WARN) return
        System.err.println("mpv [${event.prefix}] ${event.level}: ${event.text}")
        _events.tryEmit(PlayerEvent.Log(event.prefix, level, event.text))
        if (event.prefix == "cplayer" && level <= LOG_ERROR) _events.tryEmit(PlayerEvent.EndFile(currentUrl, event.text))
    }

    private fun loadTracks() {
        val count = core.getLong("track-list/count")?.toInt() ?: 0
        _tracks.value = (0 until count).mapNotNull { i ->
            val type = when (core.getString("track-list/$i/type")) {
                "video" -> TrackType.VIDEO
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                else -> return@mapNotNull null
            }
            Track(
                id = core.getLong("track-list/$i/id")?.toInt() ?: return@mapNotNull null,
                type = type,
                title = core.getString("track-list/$i/title"),
                language = core.getString("track-list/$i/lang"),
                codec = core.getString("track-list/$i/codec"),
                selected = core.getFlag("track-list/$i/selected") ?: false,
                isDefault = core.getFlag("track-list/$i/default") ?: false,
                isExternal = core.getFlag("track-list/$i/external") ?: false,
            )
        }
    }

    override fun close() {
        scope.cancel()
        compensator.stop()
        renderer.close()
        core.close()
    }

    companion object {
        const val MAX_VOLUME = 130.0

        // Same numbering as libmpv's mpv_log_level (and the Android player's PlayerEvent.Log levels).
        private const val LOG_ERROR = 20
        private const val LOG_WARN = 30

        private fun logLevel(name: String): Int = when (name) {
            "fatal" -> 10
            "error" -> LOG_ERROR
            "warn" -> LOG_WARN
            "info" -> 40
            "v" -> 50
            "debug" -> 60
            else -> 70
        }

        private fun defaultRefreshRate(): Double = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
        }.getOrNull()?.takeIf { it > 0 }?.toDouble() ?: 60.0
    }
}
