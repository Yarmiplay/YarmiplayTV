package com.yarmiplaytv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class MpvOptions(
    /** mpv --hwdec value; "no" forces software decoding. The list falls back left to right. */
    val hwdec: String = "mediacodec,mediacodec-copy",
    val audioLanguages: String = "",
    val subtitleLanguages: String = "eng,en",
    val cacheMegabytes: Int = 128,
)

/**
 * libmpv-backed [Player]. One instance per process; libmpv calls are thread-safe and the
 * property caches below are updated both from mpv's event thread and immediately when a
 * command is issued, so reads always reflect the last requested state.
 */
class MpvPlayer(context: Context, private val options: MpvOptions = MpvOptions()) : SurfacePlayer, MPVLib.EventObserver, MPVLib.LogObserver {
    private val appContext = context.applicationContext
    private val mpv: MPVLib = MPVLib.create(appContext) ?: error("libmpv failed to initialise")

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()
    private val _cover = MutableStateFlow<ByteArray?>(null)
    override val coverArt: StateFlow<ByteArray?> = _cover.asStateFlow()
    @Volatile private var coverGeneration = 0
    private var coverFor: String? = null
    private val visualizer = AudioVisualizerController()
    private val signals = PlaybackSignals()
    private val vizLock = Any()
    private var latestTracks: List<Track> = emptyList()
    /** False from load/stop until FILE_LOADED, so a stale track list cannot install the spectrum on the next file. */
    @Volatile private var acceptVisualizer = false
    /** Bytes of the loaded libavfilter, or null when the file cannot be read. */
    private val filterLibrary: ByteArray? by lazy { readFilterLibrary() }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cachedPosition = 0.0
    @Volatile private var cachedPositionAt = 0L
    @Volatile private var cachedPaused = true
    @Volatile private var cachedSpeed = 1.0
    @Volatile private var userSeekedFlag = false
    /** Set by [markUserSeek] so the next demuxer jump counts as this user's seek. */
    @Volatile private var acceptJump = false
    @Volatile private var undoPosition: Double? = null
    @Volatile private var audioDelay = 0.0
    @Volatile private var lastSeekAt = 0L
    @Volatile private var lastSeekTarget = 0.0
    @Volatile private var surfaceAttached = false
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    /** Until when a [showText] message is on the video. */
    @Volatile private var osdUntil = 0L
    @Volatile private var currentUrl: String? = null
    /**
     * Each loadfile yields exactly one START_FILE, so counting both tells whether the file mpv is opening is
     * the latest load. Querying "path" on START_FILE instead races: a bad URI has already ended by then.
     */
    @Volatile private var loadsIssued = 0
    @Volatile private var latestLoad = 0
    private var filesStarted = 0
    private var openingLatest = false
    @Volatile private var openError: String? = null
    @Volatile private var pendingStartPaused = true

    init {
        val configDir = File(appContext.filesDir, "mpv").apply { mkdirs() }
        val fontsDir = prepareFonts(configDir)
        with(mpv) {
            setOptionString("config", "yes")
            setOptionString("config-dir", configDir.path)
            setOptionString("gpu-shader-cache-dir", appContext.cacheDir.path)
            setOptionString("icc-cache-dir", appContext.cacheDir.path)
            setOptionString("vo", "gpu")
            setOptionString("gpu-context", "android")
            setOptionString("opengl-es", "yes")
            setOptionString("hwdec", options.hwdec)
            setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOptionString("ao", "audiotrack,opensles")
            setOptionString("audio-set-media-role", "yes")
            setOptionString("tls-verify", "no")
            setOptionString("cache", "yes")
            setOptionString("demuxer-max-bytes", "${options.cacheMegabytes}MiB")
            setOptionString("demuxer-max-back-bytes", "${options.cacheMegabytes / 2}MiB")
            setOptionString("keep-open", "yes")
            setOptionString("input-default-bindings", "no")
            setOptionString("save-position-on-quit", "no")
            setOptionString("sub-fonts-dir", fontsDir.path)
            setOptionString("osd-fonts-dir", fontsDir.path)
            setOptionString("sub-font", "Roboto")
            setOptionString("osd-font", "Roboto")
            setOptionString("embeddedfonts", "yes")
            setOptionString("sub-scale-with-window", "yes")
            setOptionString("osd-duration", "2500")
            setOptionString("user-agent", "YarmiplayTV")
            if (options.audioLanguages.isNotBlank()) setOptionString("alang", options.audioLanguages)
            if (options.subtitleLanguages.isNotBlank()) setOptionString("slang", options.subtitleLanguages)
            init()
            setOptionString("force-window", "no")
            setOptionString("idle", "yes")
            addObserver(this@MpvPlayer)
            addLogObserver(this@MpvPlayer)
            observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("seeking", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("demuxer-cache-duration", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
            observeProperty("chapter-list", MpvFormat.MPV_FORMAT_NONE)
            observeProperty("video-codec", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("hwdec-current", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("width", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("height", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("video-params/aspect", MpvFormat.MPV_FORMAT_DOUBLE)
        }
    }

    /** libass needs at least one font file; copy a system font next to the config. */
    private fun prepareFonts(configDir: File): File {
        val dir = File(configDir, "fonts").apply { mkdirs() }
        val target = File(dir, "Roboto-Regular.ttf")
        if (!target.exists()) {
            val candidates = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/DroidSans.ttf")
            candidates.map(::File).firstOrNull { it.exists() }?.let { src -> runCatching { src.copyTo(target) } }
        }
        return dir
    }

    // --- Surface ---------------------------------------------------------------

    override val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            mpv.attachSurface(holder.surface)
            mpv.setOptionString("force-window", "yes")
            mpv.setPropertyString("vo", "gpu")
            mpv.setPropertyString("vid", "auto")
            surface = holder.surface
            surfaceAttached = true
            refreshVisualizer()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAttached = false
            surface = null
            // Drop the spectrum before the surface goes away, so its frames are not left unconsumed.
            refreshVisualizer()
            // Without a surface the MediaCodec decoder can't drain its output and mpv's core spins
            // in it holding the lock (the next main-thread mpv call then ANRs), so drop video until
            // a surface is back; audio keeps playing.
            mpv.setPropertyString("vid", "no")
            mpv.setPropertyString("vo", "null")
            mpv.setPropertyString("force-window", "no")
            mpv.detachSurface()
        }
    }

    // --- Commands ----------------------------------------------------------------

    override fun load(url: String, mediaTitle: String?, startPaused: Boolean, startPosition: Double) {
        val now = SystemClock.elapsedRealtime()
        currentUrl = url
        pendingStartPaused = startPaused
        cachedPaused = startPaused
        cachedPosition = startPosition
        cachedPositionAt = now
        userSeekedFlag = false
        lastSeekTarget = startPosition
        lastSeekAt = now
        signals.reset()
        resetPresentation()
        _state.update { PlaybackState(url = url, paused = startPaused, position = startPosition) }
        _tracks.value = emptyList()
        mpv.setPropertyBoolean("pause", startPaused)
        mpv.setPropertyString("vid", if (surfaceAttached) "auto" else "no")
        // Properties, not loadfile's option list: a comma in the title (or the option-argument
        // position, which moved in mpv 0.38) makes that list fail and the file never opens.
        mpv.setPropertyString("force-media-title", mediaTitle ?: "")
        mpv.setPropertyString("start", if (startPosition > 0) startPosition.toString() else "none")
        openError = null
        val target = mpvTarget(url)
        latestLoad = ++loadsIssued
        mpv.command(arrayOf("loadfile", target, "replace"))
    }

    /**
     * mpv can't open Android content:// URIs, so hand it a file descriptor instead; mpv closes it
     * when the file is unloaded (fdclose://). file:// URLs become plain paths.
     */
    private fun mpvTarget(url: String): String = when {
        url.startsWith("content://") -> runCatching {
            val pfd = appContext.contentResolver.openFileDescriptor(Uri.parse(url), "r") ?: error("no descriptor")
            "fdclose://${pfd.detachFd()}"
        }.getOrElse {
            Log.w(TAG, "Cannot open $url: ${it.message}")
            openError = if (it is SecurityException) "No permission to read this file" else it.message
            url
        }
        url.startsWith("file://") -> Uri.parse(url).path ?: url
        else -> StreamRoutes.route(url)
    }

    override fun stop() {
        currentUrl = null
        latestLoad = -1
        userSeekedFlag = false
        resetPresentation()
        mpv.command(arrayOf("stop"))
        _state.value = PlaybackState()
        _tracks.value = emptyList()
    }

    override fun setPaused(paused: Boolean) {
        snapshotPosition()
        cachedPaused = paused
        _state.update { it.copy(paused = paused) }
        mpv.setPropertyBoolean("pause", paused)
    }

    override fun seek(position: Double) = performSeek(position, userInitiated = true)

    override fun seekFromSync(position: Double) = performSeek(position, userInitiated = false)

    private fun performSeek(position: Double, userInitiated: Boolean) {
        val target = position.coerceAtLeast(0.0)
        val now = SystemClock.elapsedRealtime()
        if (userInitiated) undoPosition = currentPosition()
        userSeekedFlag = userInitiated
        lastSeekTarget = target
        lastSeekAt = now
        cachedPosition = target
        cachedPositionAt = now
        signals.position(now, seeking = true)
        _state.update { it.copy(position = target, seeking = true) }
        mpv.command(arrayOf("seek", target.toString(), "absolute+exact"))
    }

    override fun seekRelative(offset: Double) {
        val dur = _state.value.duration
        val target = (currentPosition() + offset).let { if (dur > 0) it.coerceAtMost(dur - 1) else it }
        seek(target)
    }

    override fun setSpeed(speed: Double) {
        snapshotPosition()
        cachedSpeed = speed
        mpv.setPropertyDouble("speed", speed)
    }

    override fun add(property: String, delta: Double) {
        mpv.command(arrayOf("add", property, delta.toString()))
    }

    override fun cycle(property: String, down: Boolean) {
        mpv.command(if (down) arrayOf("cycle", property, "down") else arrayOf("cycle", property))
    }

    override fun command(vararg args: String): Boolean {
        val rewritten = if (args.firstOrNull() == "sub-add" && args.size > 1) {
            arrayOf("sub-add", mpvTarget(args[1]), *args.drop(2).toTypedArray())
        } else {
            arrayOf(*args)
        }
        mpv.command(rewritten)
        return true
    }

    override fun markUserSeek() {
        userSeekedFlag = true
        acceptJump = true
        lastSeekAt = SystemClock.elapsedRealtime()
    }

    override fun seekUndoPosition(): Double? = undoPosition

    override fun setUserAudioDelay(seconds: Double) {
        audioDelay = seconds
        mpv.setPropertyDouble("audio-delay", seconds)
    }

    override fun userAudioDelay(): Double = audioDelay

    override fun propertyDouble(name: String): Double? = mpv.getPropertyDouble(name)

    override fun propertyString(name: String): String? = mpv.getPropertyString(name)

    override fun setAudioVisualizerColor(rgb: Int) {
        synchronized(vizLock) { commitVisualizer(visualizer.onColor(rgb)) }
    }

    @Volatile private var appliedSubtitleScale = SubtitleScale.DEFAULT

    override fun subtitleScale(): Double = appliedSubtitleScale

    override fun setSubtitleScale(scale: Double) {
        appliedSubtitleScale = scale
        mpv.setPropertyDouble("sub-scale", scale)
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        mpv.setPropertyString(type.property, id?.toString() ?: "no")
        loadTracks()
    }

    override fun showText(text: String, durationMs: Int) {
        osdUntil = SystemClock.elapsedRealtime() + durationMs
        mpv.command(arrayOf("show-text", text, durationMs.toString()))
    }

    /**
     * This libmpv has no image encoders, so the picture is copied from the surface mpv draws on: the video with
     * its subtitles, without the black bars beside it (subtitles can sit in the ones above and below). [subtitles]
     * can't leave them out here: they are part of the picture on the surface.
     */
    override fun screenshot(path: String, subtitles: Boolean): Boolean {
        val source = surface?.takeIf { it.isValid } ?: return false
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return false
        if (SystemClock.elapsedRealtime() < osdUntil) {
            showText("", 1)
            Thread.sleep(OSD_CLEAR_MS)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(source, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        if (!copied.await(2, TimeUnit.SECONDS) || result != PixelCopy.SUCCESS) return false
        val left = (mpv.getPropertyInt("osd-dimensions/ml") ?: 0).coerceIn(0, width / 2)
        val right = (mpv.getPropertyInt("osd-dimensions/mr") ?: 0).coerceIn(0, width / 2)
        val picture = if (left + right in 1 until width) Bitmap.createBitmap(bitmap, left, 0, width - left - right, height) else bitmap
        return FileOutputStream(path).use { picture.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    // --- Fast reads for the sync engine -------------------------------------------

    override val isFileLoaded: Boolean get() = _state.value.fileLoaded
    override val isPaused: Boolean get() = cachedPaused
    override val duration: Double get() = _state.value.duration
    override val userSeeked: Boolean get() = userSeekedFlag

    override fun currentPosition(): Double {
        val base = cachedPosition
        val s = _state.value
        if (cachedPaused || s.buffering || s.seeking || !s.fileLoaded) return base
        val elapsed = (SystemClock.elapsedRealtime() - cachedPositionAt) / 1000.0
        return base + elapsed.coerceIn(0.0, 1.0) * cachedSpeed
    }

    private fun snapshotPosition() {
        cachedPosition = currentPosition()
        cachedPositionAt = SystemClock.elapsedRealtime()
    }

    /**
     * mpv can report a property on the UI thread while Compose is in the middle of a frame. Writing the state then
     * asks for another layout inside that one. Posting the write lets the frame finish first. A report from mpv's
     * own thread is applied immediately; Compose picks that up on the next frame.
     */
    private fun publishState(transform: (PlaybackState) -> PlaybackState) {
        val write = Runnable { _state.update(transform) }
        if (Looper.myLooper() == Looper.getMainLooper()) mainHandler.post(write) else write.run()
    }

    private fun publishTracks(list: List<Track>) {
        val write = Runnable { _tracks.value = list }
        if (Looper.myLooper() == Looper.getMainLooper()) mainHandler.post(write) else write.run()
    }

    // --- mpv callbacks -------------------------------------------------------------

    override fun eventProperty(property: String) {
        if (property == "track-list") loadTracks()
        if (property == "chapter-list") loadChapters()
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "width" -> publishState { if (it.audioOnly) it else it.copy(videoWidth = value.toInt()) }
            "height" -> publishState { if (it.audioOnly) it else it.copy(videoHeight = value.toInt()) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> onTimePos(value)
            "duration" -> publishState { it.copy(duration = value) }
            "video-params/aspect" -> publishState { if (it.audioOnly) it else it.copy(displayAspect = value) }
            "speed" -> {
                cachedSpeed = value
                publishState { it.copy(speed = value) }
            }
            "demuxer-cache-duration" -> if (signals.cache(value, _state.value.buffering)) {
                publishState { it.copy(cacheSeconds = value) }
            }
        }
    }

    private fun onTimePos(value: Double) {
        val now = SystemClock.elapsedRealtime()
        if (_state.value.seeking && now - lastSeekAt < SEEK_SETTLE_MS && kotlin.math.abs(value - lastSeekTarget) > UNINITIATED_JUMP_THRESHOLD) {
            return
        }
        if (_state.value.fileLoaded && !openingLatest && !_state.value.seeking) {
            val elapsed = ((now - cachedPositionAt) / 1000.0).coerceAtLeast(0.0)
            val expectedMax = cachedPosition + (if (cachedPaused) 0.0 else elapsed * cachedSpeed)
            val nearRecentSeek = now - lastSeekAt < SEEK_SETTLE_MS && kotlin.math.abs(value - lastSeekTarget) <= UNINITIATED_JUMP_THRESHOLD
            val jump = value < cachedPosition - UNINITIATED_JUMP_THRESHOLD || value > expectedMax + UNINITIATED_JUMP_THRESHOLD
            if (acceptJump && jump) {
                acceptJump = false
                userSeekedFlag = true
                lastSeekTarget = value
                lastSeekAt = now
            } else if (!nearRecentSeek && jump) {
                val from = currentPosition()
                userSeekedFlag = false
                _events.tryEmit(PlayerEvent.UninitiatedJump(currentUrl, from, value))
            }
        }
        cachedPosition = value
        cachedPositionAt = now
        if (signals.position(now)) publishState { it.copy(position = value) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                if (value != cachedPaused) snapshotPosition()
                cachedPaused = value
                publishState { it.copy(paused = value) }
            }
            "paused-for-cache" -> {
                snapshotPosition()
                publishState { it.copy(buffering = value) }
            }
            "seeking" -> {
                if (!value) cachedPositionAt = SystemClock.elapsedRealtime()
                publishState { it.copy(seeking = value) }
            }
            "eof-reached" -> publishState { it.copy(eofReached = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "video-codec" -> publishState { if (it.audioOnly) it else it.copy(videoCodec = value) }
            "hwdec-current" -> publishState { if (it.audioOnly) it else it.copy(hwdec = value) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE -> openingLatest = ++filesStarted == latestLoad
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                openingLatest = false
                val now = SystemClock.elapsedRealtime()
                val duration = mpv.getPropertyDouble("duration") ?: 0.0
                val position = mpv.getPropertyDouble("time-pos") ?: cachedPosition
                cachedPosition = position
                cachedPositionAt = now
                lastSeekTarget = position
                lastSeekAt = now
                acceptVisualizer = true
                signals.reset()
                publishState { it.copy(fileLoaded = true, duration = duration, position = position, eofReached = false) }
                loadTracks()
                currentUrl?.let { _events.tryEmit(PlayerEvent.FileLoaded(it, duration)) }
            }
            MpvEvent.MPV_EVENT_END_FILE -> {
                publishState { it.copy(fileLoaded = false) }
                val failedToOpen = openingLatest && filesStarted == latestLoad
                openingLatest = false
                // Ended before loading and not replaced or stopped since: opening failed. mpv often only
                // logs the reason at verbose level (stream/ffmpeg), so the error-log path below misses it.
                if (failedToOpen) {
                    _events.tryEmit(PlayerEvent.EndFile(currentUrl, openError ?: "Couldn't open the file"))
                }
            }
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // The position was frozen while seeking; extrapolate from now, not from when the seek was issued.
                if (_state.value.seeking) cachedPositionAt = SystemClock.elapsedRealtime()
                publishState { it.copy(seeking = false) }
            }
        }
    }

    private var lastLogLine = ""
    private var lastLogAt = 0L

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = text.trimEnd()
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            val now = SystemClock.elapsedRealtime()
            if (line == lastLogLine && now - lastLogAt < 1_000L) return
            lastLogLine = line
            lastLogAt = now
            Log.w(TAG, "[$prefix] $line")
            _events.tryEmit(PlayerEvent.Log(prefix, level, line))
            if (prefix == "cplayer" && level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                _events.tryEmit(PlayerEvent.EndFile(currentUrl, line))
            }
            if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR && _state.value.audioVisualizer && lavfiRejected(line)) {
                giveUpOnVisualizer()
            }
        } else if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO) {
            Log.i(TAG, "[$prefix] $line")
        }
    }

    private fun loadChapters() {
        val count = mpv.getPropertyInt("chapter-list/count") ?: 0
        val chapters = (0 until count).mapNotNull { i ->
            Chapter(mpv.getPropertyString("chapter-list/$i/title"), mpv.getPropertyDouble("chapter-list/$i/time") ?: return@mapNotNull null)
        }
        publishState { it.copy(chapters = chapters) }
    }

    private fun loadTracks() {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val list = (0 until count).mapNotNull { i ->
            val type = when (mpv.getPropertyString("track-list/$i/type")) {
                "video" -> TrackType.VIDEO
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                else -> return@mapNotNull null
            }
            Track(
                id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null,
                type = type,
                title = mpv.getPropertyString("track-list/$i/title"),
                language = mpv.getPropertyString("track-list/$i/lang"),
                codec = mpv.getPropertyString("track-list/$i/codec"),
                selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false,
                isDefault = mpv.getPropertyBoolean("track-list/$i/default") ?: false,
                isExternal = mpv.getPropertyBoolean("track-list/$i/external") ?: false,
                image = (mpv.getPropertyBoolean("track-list/$i/albumart") ?: false) ||
                    (mpv.getPropertyBoolean("track-list/$i/image") ?: false),
            )
        }
        latestTracks = list
        publishTracks(list)
        val only = AudioOnly.isAudioOnly(list)
        val title = if (only) MediaTags.title { mpv.getPropertyString(it) } else null
        val artist = if (only) MediaTags.artist { mpv.getPropertyString(it) } else null
        publishState { it.withAudioPresentation(list, title, artist, visualizer = it.audioVisualizer && only) }
        refreshVisualizer()
        val url = currentUrl
        if (only && url != null && coverFor != url) scheduleCover(url) else if (!only) {
            coverGeneration++
            coverFor = null
            _cover.value = null
        }
    }

    /**
     * The same lavfi graph the desktop player uses. It is installed only when this process's
     * libavfilter contains the filters and mpv accepts the property. A rejected graph is cleared
     * immediately and the screen falls back to the idle bars, so playback is not left waiting on it.
     * Voice chat never goes through this player. Cover art is read beside playback, not through it.
     */
    private fun readFilterLibrary(): ByteArray? = runCatching {
        val file = File(appContext.applicationInfo.nativeLibraryDir, "libavfilter.so")
        if (!file.isFile || file.length() > 8_000_000L) null else file.readBytes()
    }.getOrNull()

    private fun commitVisualizer(command: VisualizerCommand) {
        val showing = visualizer.commit(command) { graph -> applyGraph(graph) }
        if (showing != null) publishState { it.copy(audioVisualizer = showing) }
    }

    private fun applyGraph(graph: String): Boolean {
        if (!LavfiProbe.supports(filterLibrary, graph)) return false
        val before = mpv.getPropertyString("lavfi-complex").orEmpty()
        if (before == graph) return true
        mpv.setPropertyString("lavfi-complex", graph)
        val after = mpv.getPropertyString("lavfi-complex").orEmpty()
        // A rejected set leaves the previous value. A value mpv accepts may be rewritten.
        val accepted = after == graph || (after != before && (graph.isEmpty() || after.isNotEmpty()))
        if (!accepted) {
            if (after.isNotEmpty()) mpv.setPropertyString("lavfi-complex", "")
            return false
        }
        return graph.isEmpty() || after.isNotEmpty()
    }

    private fun refreshVisualizer() {
        val tracks = if (acceptVisualizer) latestTracks else emptyList()
        synchronized(vizLock) { commitVisualizer(visualizer.onTracks(tracks, canRender = acceptVisualizer && surfaceAttached)) }
    }

    /** Drops a graph from the previous file before the next one opens, and forgets its cover. */
    private fun resetPresentation() {
        acceptVisualizer = false
        latestTracks = emptyList()
        coverGeneration++
        coverFor = null
        _cover.value = null
        synchronized(vizLock) { commitVisualizer(visualizer.onNewFile()) }
    }

    /** A graph mpv accepted and then could not build. The idle animation takes over for this file. */
    private fun lavfiRejected(text: String): Boolean =
        text.contains("No such filter", ignoreCase = true) ||
            text.contains("Error initializing", ignoreCase = true) ||
            text.contains("parsing failed", ignoreCase = true) ||
            text.contains("failed to configure", ignoreCase = true)

    private fun giveUpOnVisualizer() {
        synchronized(vizLock) {
            visualizer.giveUp()
            mpv.setPropertyString("lavfi-complex", "")
        }
        publishState { it.copy(audioVisualizer = false) }
    }

    private fun scheduleCover(url: String?) {
        val generation = ++coverGeneration
        coverFor = url
        _cover.value = null
        if (url == null) return
        thread(isDaemon = true, name = "cover-art") {
            val bytes = coverPrefix(url) ?: return@thread
            if (generation != coverGeneration) return@thread
            val tags = EmbeddedMedia.read(bytes)
            if (generation != coverGeneration) return@thread
            if (tags.picture != null) _cover.value = tags.picture
            if (tags.title == null && tags.artist == null) return@thread
            publishState { current ->
                if (!current.audioOnly || generation != coverGeneration) current
                else current.copy(
                    mediaTitle = current.mediaTitle ?: tags.title?.takeIf { it.isNotBlank() },
                    mediaArtist = current.mediaArtist ?: tags.artist?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun coverPrefix(url: String): ByteArray? {
        EmbeddedMedia.readPrefix(url)?.let { return it }
        if (!url.startsWith("content://")) return null
        return runCatching {
            appContext.contentResolver.openInputStream(Uri.parse(url))?.use {
                EmbeddedMedia.readPrefix(it, EmbeddedMedia.PREFIX_BYTES)
            }
        }.getOrNull()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mpv.removeObserver(this)
        mpv.removeLogObserver(this)
        mpv.destroy()
    }

    companion object {
        private const val TAG = "mpv"
        /** Long enough for mpv to draw a frame without the message it was showing. */
        private const val OSD_CLEAR_MS = 150L
        private const val SEEK_SETTLE_MS = 1_500L
        private const val UNINITIATED_JUMP_THRESHOLD = 1.0
    }
}
