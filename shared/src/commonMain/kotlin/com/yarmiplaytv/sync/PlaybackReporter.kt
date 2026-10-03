package com.yarmiplaytv.sync

import com.yarmiplaytv.Logger
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.PlaybackReport
import com.yarmiplaytv.media.ReportState
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Tells every server that has the playing file (see [NowPlaying.copies]) how far playback got:
 * start, pause/resume, settled seeks, a heartbeat while playing and stop, and marks it watched
 * once 90% has been played. Never touches the player; a failing server only logs.
 */
class PlaybackReporter(
    private val scope: CoroutineScope,
    player: Player,
    nowPlaying: StateFlow<NowPlaying?>,
    private val servers: StateFlow<List<MediaSource>>,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) session = null
        }

    private class Session(val url: String, val id: String) {
        val started = HashSet<ServerCopy>()
        val watched = HashSet<ServerCopy>()
        var copies: List<ServerCopy> = emptyList()
        var paused = true
        var seeking = false
        var position = 0.0
        var duration = 0.0
    }

    private var session: Session? = null

    init {
        scope.launch { combine(nowPlaying, player.state, ::Pair).collect { (np, state) -> onChange(np, state) } }
        scope.launch {
            while (true) {
                delay(heartbeatMs)
                session?.takeIf { !it.paused }?.let { send(it, ReportState.PLAYING) }
            }
        }
    }

    private fun onChange(np: NowPlaying?, state: PlaybackState) {
        session?.let { s ->
            val playerMovedOn = state.url != s.url || !state.fileLoaded
            if (np?.url != s.url || playerMovedOn) {
                if (state.eofReached && state.url == s.url) s.position = s.duration
                stop(s)
            }
        }
        if (!enabled || np == null || np.copies.isEmpty() || !state.fileLoaded || state.url != np.url) return
        val s = session ?: Session(np.url, UUID.randomUUID().toString()).also {
            it.paused = state.paused
            session = it
        }
        s.copies = np.copies
        s.duration = state.duration.takeIf { it > 0 } ?: np.durationHint
        val wasSeeking = s.seeking
        s.seeking = state.seeking
        if (!state.seeking) s.position = state.position
        if (state.eofReached) s.position = s.duration

        if (s.copies.any { it !in s.started }) send(s, ReportState.STARTED, onlyNew = true)
        when {
            state.paused != s.paused -> {
                s.paused = state.paused
                send(s, if (s.paused) ReportState.PAUSED else ReportState.PLAYING)
            }
            wasSeeking && !state.seeking -> send(s, if (s.paused) ReportState.PAUSED else ReportState.PLAYING)
            needsWatched(s) -> send(s, if (s.paused) ReportState.PAUSED else ReportState.PLAYING)
        }
    }

    private fun needsWatched(s: Session) =
        s.duration > 0 && s.position >= s.duration * WATCHED_FRACTION && s.copies.any { it !in s.watched }

    private fun stop(s: Session) {
        session = null
        send(s, ReportState.STOPPED)
    }

    /** One report per copy; [onlyNew] sends STARTED just to copies that haven't had it. */
    private fun send(s: Session, state: ReportState, onlyNew: Boolean = false) {
        val markWatched = needsWatched(s)
        val targets = if (onlyNew) s.copies.filter { it !in s.started } else s.copies.filter { it in s.started || state == ReportState.STARTED }
        for (copy in targets) {
            val source = servers.value.firstOrNull { it.key == copy.sourceKey } ?: continue
            if (state == ReportState.STARTED) s.started += copy
            val watched = markWatched && copy !in s.watched
            if (watched) s.watched += copy
            val report = PlaybackReport(copy.itemId, s.id, state, s.position, s.duration, watched)
            scope.launch {
                runCatching { source.reportPlayback(report) }
                    .onFailure { Logger.w(TAG, "Couldn't report $state to ${source.displayName}: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackReporter"
        const val HEARTBEAT_MS = 10_000L
        const val WATCHED_FRACTION = 0.9
    }
}
