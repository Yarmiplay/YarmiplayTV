package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.media.aniskip.AniSkipClient
import com.yarmiplaytv.media.aniskip.SkipSegment
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.RoomSkip
import com.yarmiplaytv.syncplay.RoomState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** "Skip opening" on the player for a moment, as the opening starts and it isn't skipped by itself. */
data class SkipPrompt(val segment: SkipSegment) {
    val label: String get() = "Skip ${segment.kind.label}"
}

/**
 * Skips anime openings and endings, with times from AniSkip for the episode MyAnimeList says is playing: by itself
 * when the room (or, alone, the Playback setting) says so, else with a Skip button shown for [PROMPT_MILLIS] as the
 * part starts. Only a device that may seek does either (in a managed room, the operators); in a room the skip moves
 * everyone, like any seek. Each part is handled once per file, so seeking back into it plays it.
 */
class SkipService(
    private val scope: CoroutineScope,
    private val player: Player,
    private val nowPlaying: StateFlow<NowPlaying?>,
    private val mal: MalService,
    private val aniSkip: AniSkipClient,
    private val room: StateFlow<RoomState>,
    private val roomSkip: StateFlow<RoomSkip>,
    private val settings: StateFlow<AppSettings>,
    private val notify: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _segments = MutableStateFlow<List<SkipSegment>>(emptyList())
    /** The playing file's opening and ending, when known. */
    val segments: StateFlow<List<SkipSegment>> = _segments.asStateFlow()

    private val _prompt = MutableStateFlow<SkipPrompt?>(null)
    /** The Skip button to show now, if any. */
    val prompt: StateFlow<SkipPrompt?> = _prompt.asStateFlow()

    /** AniSkip's answers per episode and file length, also "nothing known"; kept in memory only. */
    private val known = ConcurrentHashMap<String, List<SkipSegment>>()
    private var url: String? = null
    private var lookup: Job? = null
    private var promptJob: Job? = null
    /** Parts handled in this file (skipped, offered or passed), by start time. */
    private val handled = mutableSetOf<Double>()
    /** Automatic skips tried and when: the room can swallow a seek just after a file starts. */
    private val tries = mutableMapOf<Double, Pair<Int, Long>>()

    init {
        scope.launch {
            nowPlaying.collect { np -> if (np?.url != url) newFile(np) }
        }
        scope.launch {
            player.state.collect(::onState)
        }
    }

    /** Skips the part the Skip button offers. */
    fun skip() {
        val p = _prompt.value ?: return
        dismiss()
        jumpPast(p.segment)
    }

    private fun dismiss() {
        promptJob?.cancel()
        _prompt.value = null
    }

    private fun newFile(np: NowPlaying?) {
        url = np?.url
        lookup?.cancel()
        dismiss()
        handled.clear()
        tries.clear()
        _segments.value = emptyList()
        val info = np?.episode ?: return
        val fileUrl = np.url
        lookup = scope.launch {
            try {
                val loaded = player.state.first { it.url == fileUrl && it.fileLoaded && it.duration > 0 }
                val match = mal.identify(info) ?: return@launch
                val key = "${match.anime.id}/${match.episode}/${loaded.duration.roundToInt()}"
                val segments = known[key] ?: aniSkip.skipTimes(match.anime.id, match.episode, loaded.duration).also { known[key] = it }
                if (url == fileUrl) _segments.value = segments
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "No skip times for ${info.seriesName} ${info.episode}: ${e.message}")
            }
        }
    }

    private fun onState(state: PlaybackState) {
        val segments = _segments.value
        if (segments.isEmpty() || state.url != url || !state.fileLoaded || state.paused || state.seeking) return
        val position = state.position
        _prompt.value?.let { p -> if (position < p.segment.start || position >= p.segment.end) dismiss() }
        val canSeek = !room.value.followsOperators
        for (segment in segments) {
            if (segment.start in handled) continue
            val inside = position >= segment.start && position < segment.end - 1
            if (!inside) continue
            val auto = if (roomSkip.value.inRoom) roomSkip.value.on else settings.value.playback.autoSkipAlone
            when {
                !canSeek -> handled += segment.start
                auto -> autoSkip(segment, position)
                position >= segment.start + PROMPT_SECONDS -> handled += segment.start
                else -> {
                    handled += segment.start
                    if (settings.value.playback.showSkipButton) offer(segment)
                }
            }
        }
    }

    private fun autoSkip(segment: SkipSegment, position: Double) {
        // Only from its start: seeking into the middle of an opening means wanting to see it.
        if (position >= segment.start + AUTO_WINDOW_SECONDS) {
            handled += segment.start
            return
        }
        val (count, at) = tries[segment.start] ?: (0 to 0L)
        if (count > 0 && now() - at < RETRY_MILLIS) return
        if (count >= MAX_TRIES) {
            handled += segment.start
            return
        }
        tries[segment.start] = (count + 1) to now()
        jumpPast(segment)
        if (count == 0) notify("Skipped the ${segment.kind.label}")
    }

    private fun offer(segment: SkipSegment) {
        promptJob?.cancel()
        _prompt.value = SkipPrompt(segment)
        promptJob = scope.launch {
            delay(PROMPT_MILLIS)
            if (_prompt.value?.segment == segment) _prompt.value = null
        }
    }

    /** To the end of [segment]; an ending that runs to the end of the file goes to just before it, so the file ends as usual. */
    private fun jumpPast(segment: SkipSegment) {
        val duration = player.state.value.duration
        val target = if (duration > 0 && segment.end >= duration - END_SLACK_SECONDS) duration - 0.5 else segment.end
        player.seek(target)
    }

    companion object {
        private const val TAG = "SkipService"
        const val PROMPT_MILLIS = 3_000L
        private const val PROMPT_SECONDS = PROMPT_MILLIS / 1000.0
        /** An automatic skip happens while playback is within this many seconds of the part's start. */
        private const val AUTO_WINDOW_SECONDS = 10.0
        private const val RETRY_MILLIS = 1_500L
        private const val MAX_TRIES = 4
        private const val END_SLACK_SECONDS = 2.0
    }
}
