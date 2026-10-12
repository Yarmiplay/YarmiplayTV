package com.yarmiplaytv.tracking

import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tells [MalService] what's playing and when it has been watched: [WATCHED_FRACTION] of the way through (or to
 * the end), once per file, like [com.yarmiplaytv.sync.PlaybackReporter]. It only watches the player.
 */
class MalTracker(scope: CoroutineScope, player: Player, nowPlaying: StateFlow<NowPlaying?>, private val service: MalService) {
    private var url: String? = null
    private var reported = false

    init {
        scope.launch {
            combine(nowPlaying, player.state, ::Pair).collect { (np, state) ->
                if (np?.url != url) {
                    url = np?.url
                    reported = false
                    service.playing(np?.episode)
                }
                val info = np?.episode ?: return@collect
                if (reported || state.url != np.url || !state.fileLoaded) return@collect
                val duration = state.duration.takeIf { it > 0 } ?: np.durationHint
                if (duration > 0 && (state.eofReached || state.position >= duration * WATCHED_FRACTION)) {
                    reported = true
                    service.watched(info)
                }
            }
        }
    }

    companion object {
        const val WATCHED_FRACTION = 0.9
    }
}
