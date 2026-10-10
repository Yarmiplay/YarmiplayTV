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

    @Volatile private var cachedPosition = 0.0
    @Volatile private var cachedPositionAt = 0L
    @Volatile private var cachedPaused = true
    @Volatile private var cachedSpeed = 1.0
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
            observeProperty("video-codec", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("hwdec-current", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("width", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("height", MpvFormat.MPV_FORMAT_INT64)
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
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAttached = false
            surface = null
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
        currentUrl = url
        pendingStartPaused = startPaused
        cachedPaused = startPaused
        cachedPosition = startPosition
        cachedPositionAt = SystemClock.elapsedRealtime()
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
        else -> url
    }

    override fun stop() {
        currentUrl = null
        latestLoad = -1
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

    override fun seek(position: Double) {
        val target = position.coerceAtLeast(0.0)
        cachedPosition = target
        cachedPositionAt = SystemClock.elapsedRealtime()
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
     * its subtitles, without the black bars beside it (subtitles can sit in the ones above and below).
     */
    override fun screenshot(path: String): Boolean {
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

    // --- mpv callbacks -------------------------------------------------------------

    override fun eventProperty(property: String) {
        if (property == "track-list") loadTracks()
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "width" -> _state.update { it.copy(videoWidth = value.toInt()) }
            "height" -> _state.update { it.copy(videoHeight = value.toInt()) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                cachedPosition = value
                cachedPositionAt = SystemClock.elapsedRealtime()
                _state.update { it.copy(position = value) }
            }
            "duration" -> _state.update { it.copy(duration = value) }
            "speed" -> {
                cachedSpeed = value
                _state.update { it.copy(speed = value) }
            }
            "demuxer-cache-duration" -> _state.update { it.copy(cacheSeconds = value) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                if (value != cachedPaused) snapshotPosition()
                cachedPaused = value
                _state.update { it.copy(paused = value) }
            }
            "paused-for-cache" -> {
                snapshotPosition()
                _state.update { it.copy(buffering = value) }
            }
            "seeking" -> {
                if (!value) cachedPositionAt = SystemClock.elapsedRealtime()
                _state.update { it.copy(seeking = value) }
            }
            "eof-reached" -> _state.update { it.copy(eofReached = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "video-codec" -> _state.update { it.copy(videoCodec = value) }
            "hwdec-current" -> _state.update { it.copy(hwdec = value) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE -> openingLatest = ++filesStarted == latestLoad
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                openingLatest = false
                val duration = mpv.getPropertyDouble("duration") ?: 0.0
                val position = mpv.getPropertyDouble("time-pos") ?: cachedPosition
                cachedPosition = position
                cachedPositionAt = SystemClock.elapsedRealtime()
                _state.update { it.copy(fileLoaded = true, duration = duration, position = position, eofReached = false) }
                loadTracks()
                currentUrl?.let { _events.tryEmit(PlayerEvent.FileLoaded(it, duration)) }
            }
            MpvEvent.MPV_EVENT_END_FILE -> {
                _state.update { it.copy(fileLoaded = false) }
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
                _state.update { it.copy(seeking = false) }
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = text.trimEnd()
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            Log.w(TAG, "[$prefix] $line")
            _events.tryEmit(PlayerEvent.Log(prefix, level, line))
            if (prefix == "cplayer" && level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                _events.tryEmit(PlayerEvent.EndFile(currentUrl, line))
            }
        } else if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO) {
            Log.i(TAG, "[$prefix] $line")
        }
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
            )
        }
        _tracks.value = list
    }

    fun destroy() {
        mpv.removeObserver(this)
        mpv.removeLogObserver(this)
        mpv.destroy()
    }

    companion object {
        private const val TAG = "mpv"
        /** Long enough for mpv to draw a frame without the message it was showing. */
        private const val OSD_CLEAR_MS = 150L
    }
}
