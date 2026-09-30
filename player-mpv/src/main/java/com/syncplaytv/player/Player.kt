package com.syncplaytv.player

import android.view.SurfaceHolder
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface Player {
    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<List<Track>>
    val events: SharedFlow<PlayerEvent>

    /** Attach to the SurfaceView the video should render into. */
    val surfaceCallback: SurfaceHolder.Callback

    /**
     * Load [url]. [mediaTitle] is shown in mpv's OSD; [startPaused] keeps the file paused once loaded;
     * [startPosition] (seconds) starts playback there.
     */
    fun load(url: String, mediaTitle: String? = null, startPaused: Boolean = true, startPosition: Double = 0.0)
    fun stop()

    fun setPaused(paused: Boolean)
    fun seek(position: Double)
    fun seekRelative(offset: Double)
    fun setSpeed(speed: Double)

    /** null disables the track. */
    fun selectTrack(type: TrackType, id: Int?)

    /** Short text shown by mpv's on-screen display. */
    fun showText(text: String, durationMs: Int = 3000)
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
)

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
}
