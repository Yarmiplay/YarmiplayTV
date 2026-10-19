package com.yarmiplaytv.player

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A media player as the app and the sync engine see it; each platform attaches video output its own way. */
interface Player {
    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<List<Track>>
    val events: SharedFlow<PlayerEvent>

    /**
     * Load [url]. [mediaTitle] is shown in mpv's OSD; [startPaused] keeps the file paused once loaded;
     * [startPosition] (seconds) starts playback there.
     */
    fun load(url: String, mediaTitle: String? = null, startPaused: Boolean = true, startPosition: Double = 0.0)
    fun stop()

    fun setPaused(paused: Boolean)
    fun seek(position: Double)
    /** Seek triggered by Syncplay room synchronization rather than the local user or skip button. */
    fun seekFromSync(position: Double) = seek(position)
    fun seekRelative(offset: Double)
    fun setSpeed(speed: Double)

    /** null disables the track. */
    fun selectTrack(type: TrackType, id: Int?)

    /**
     * mpv's `sub-scale`: 1 is the normal size. Players that don't draw subtitles leave this unchanged.
     * The value last passed here is what [subtitleScale] reports.
     */
    fun setSubtitleScale(scale: Double) {}

    /** The scale last applied with [setSubtitleScale], or [SubtitleScale.DEFAULT] when none has been. */
    fun subtitleScale(): Double = SubtitleScale.DEFAULT

    /** Short text shown by mpv's on-screen display. */
    fun showText(text: String, durationMs: Int = 3000)

    /**
     * Writes the current video frame, with its subtitles unless [subtitles] is false, to [path] (the extension picks
     * the image format) and returns whether it did. Blocks until the file is written, so call it off the main thread.
     */
    fun screenshot(path: String, subtitles: Boolean = true): Boolean = false

    // Fast reads for the sync engine: they reflect commands immediately, before mpv confirms them.
    val isFileLoaded: Boolean
    val isPaused: Boolean
    val duration: Double
    /** Whether the current position reflects an intentional user/skip seek rather than an uninitiated demuxer jump. */
    val userSeeked: Boolean get() = true

    /** Position extrapolated from the last update, so polling between mpv updates stays smooth. */
    fun currentPosition(): Double
}

data class PlaybackState(
    val url: String? = null,
    val fileLoaded: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val seeking: Boolean = false,
    val eofReached: Boolean = false,
    val speed: Double = 1.0,
    val cacheSeconds: Double = 0.0,
    val videoCodec: String? = null,
    val hwdec: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Width over height as the video is shown (pixel aspect and rotation applied); 0 until known. */
    val displayAspect: Double = 0.0,
    /** The file's chapters, in order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
)

/** A chapter of the playing file: its [title] (when it has one) and where it starts, in seconds. */
data class Chapter(val title: String?, val time: Double)

enum class TrackType(val mpvName: String, val property: String) {
    VIDEO("video", "vid"),
    AUDIO("audio", "aid"),
    SUBTITLE("sub", "sid"),
}

data class Track(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val language: String?,
    val codec: String?,
    val selected: Boolean,
    val isDefault: Boolean,
    val isExternal: Boolean,
) {
    val label: String
        get() = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            language?.takeIf { it.isNotBlank() }?.uppercase(),
            codec?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { "Track $id" }
}

sealed interface PlayerEvent {
    data class FileLoaded(val url: String, val duration: Double) : PlayerEvent
    data class EndFile(val url: String?, val error: String?) : PlayerEvent
    data class Log(val prefix: String, val level: Int, val text: String) : PlayerEvent
    /** The demuxer jumped [from] -> [to] on its own without a seek command (e.g. across a hole in a partial file). */
    data class UninitiatedJump(val url: String?, val from: Double, val to: Double) : PlayerEvent
}
