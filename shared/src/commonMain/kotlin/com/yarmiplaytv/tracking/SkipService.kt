package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.media.aniskip.AniSkipClient
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.RoomSkip
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.tracking.detect.SkipDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** "Skip opening" on the player for a moment, as the opening starts and it isn't skipped by itself. */
data class SkipPrompt(val segment: SkipSegment) {
    val label: String get() = "Skip ${segment.kind.label}"
}

/**
 * Skips anime openings and endings: by itself when the room (or, alone, the Playback setting) says so, else with a
 * Skip button shown for [PROMPT_MILLIS] as the part starts. Where they are comes, for each part, from the first of:
 * the file's own chapters ("Opening", "ED"…), the media server's markers (Jellyfin's media segments, Plex's intro and
 * credits markers), AniSkip's times for the episode MyAnimeList says is playing, and, when none of those knows a part
 * and the Playback setting allows it, listening for it ([SkipDetector]: the sound the episodes of a season share).
 * Only a device that may seek does either (in a managed room, the operators); in a room the skip moves everyone,
 * like any seek. Each part is handled once per file, so seeking back into it plays it.
 */
class SkipService(
    private val scope: CoroutineScope,
    private val player: Player,
    private val nowPlaying: StateFlow<NowPlaying?>,
    private val mal: MalService,
    private val aniSkip: AniSkipClient,
    private val servers: StateFlow<List<MediaSource>>,
    private val room: StateFlow<RoomState>,
    private val roomSkip: StateFlow<RoomSkip>,
    private val settings: StateFlow<AppSettings>,
    private val notify: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** Listens for the parts no other source knows; null leaves that out. */
    private val detector: SkipDetector? = null,
) {
    private val _segments = MutableStateFlow<List<SkipSegment>>(emptyList())
    /** The playing file's opening and ending, when known (also for the seek bar's marks). */
    val segments: StateFlow<List<SkipSegment>> = _segments.asStateFlow()

    private val _prompt = MutableStateFlow<SkipPrompt?>(null)
    /** The Skip button to show now, if any. */
    val prompt: StateFlow<SkipPrompt?> = _prompt.asStateFlow()

    /** AniSkip's answers per episode and file length, also "nothing known"; kept in memory only. */
    private val known = ConcurrentHashMap<String, List<SkipSegment>>()
    private var url: String? = null
    private var lookup: Job? = null
    private var serverLookup: Job? = null
    private var fromChapters: List<SkipSegment> = emptyList()
    private var chaptersOf: List<Chapter>? = null
    private var fromServer: List<SkipSegment> = emptyList()
    private var fromAniSkip: List<SkipSegment> = emptyList()
    private var fromDetected: List<SkipSegment> = emptyList()
    private var detectJob: Job? = null
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
        if (detector != null) scope.launch {
            // Turned off, listening stops at once; turned on, it starts for the playing file.
            settings.map { it.playback.detectSkips }.distinctUntilChanged().collect { on ->
                if (on) url?.let(::detect) else {
                    detectJob?.cancel()
                    detectJob = null
                }
            }
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
        serverLookup?.cancel()
        detectJob?.cancel()
        dismiss()
        handled.clear()
        tries.clear()
        fromChapters = emptyList()
        chaptersOf = null
        fromServer = emptyList()
        fromAniSkip = emptyList()
        fromDetected = emptyList()
        _segments.value = emptyList()
        val fileUrl = np?.url ?: return
        // The player may have told of the file (and its chapters) before the playlist did.
        takeChapters(player.state.value)
        if (np.copies.isNotEmpty()) {
            serverLookup = scope.launch {
                for (copy in np.copies) {
                    val source = servers.value.firstOrNull { it.key == copy.sourceKey } ?: continue
                    val marked = try {
                        source.skipSegments(copy.itemId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "No markers from ${source.displayName}: ${e.message}")
                        continue
                    }
                    if (marked.isEmpty()) continue
                    if (url == fileUrl) {
                        fromServer = marked
                        merge()
                    }
                    break
                }
            }
        }
        // Without the episode's number, neither AniSkip nor listening can tell which episode this is.
        val info = np.episode ?: return
        lookup = scope.launch {
            try {
                val loaded = player.state.first { it.url == fileUrl && it.fileLoaded && it.duration > 0 }
                val match = mal.identify(info) ?: return@launch
                val key = "${match.anime.id}/${match.episode}/${loaded.duration.roundToInt()}"
                val segments = known[key] ?: aniSkip.skipTimes(match.anime.id, match.episode, loaded.duration).also { known[key] = it }
                if (url == fileUrl) {
                    fromAniSkip = segments
                    merge()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "No skip times for ${info.seriesName} ${info.episode}: ${e.message}")
            }
        }
        detect(fileUrl)
    }

    /**
     * Listens for the parts of [fileUrl] no other source knows, once those have answered and the file is loaded; only
     * on a device that may seek, and only while the Playback setting is on.
     */
    private fun detect(fileUrl: String) {
        val detector = detector ?: return
        if (!settings.value.playback.detectSkips || nowPlaying.value?.episode?.episode == null) return
        detectJob?.cancel()
        val waitFor = listOfNotNull(serverLookup, lookup)
        detectJob = scope.launch {
            try {
                waitFor.forEach { it.join() }
                val loaded = player.state.first { it.url == fileUrl && it.fileLoaded && it.duration > 0 }
                // The file's chapters come a moment after it loads.
                delay(CHAPTERS_SETTLE_MILLIS)
                if (room.value.followsOperators) return@launch
                val missing = SkipSegment.Kind.entries.filter { kind ->
                    listOf(fromChapters, fromServer, fromAniSkip).none { source -> source.any { it.kind == kind } }
                }.toSet()
                if (missing.isEmpty()) return@launch
                // Read now: the server copies of a file on this device can be found after it starts.
                val np = nowPlaying.value?.takeIf { it.url == fileUrl } ?: return@launch
                val found = detector.detect(np, loaded.duration, missing)
                if (url == fileUrl) {
                    fromDetected = found.filter { it.kind in missing }
                    merge()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Couldn't listen for the opening and ending: ${e.message}")
            }
        }
    }

    /** For each part, the first source that knows it: the file's chapters, the server's markers, AniSkip, listening. */
    private fun merge() {
        _segments.value = SkipSegment.Kind.entries.mapNotNull { kind ->
            listOf(fromChapters, fromServer, fromAniSkip, fromDetected).firstNotNullOfOrNull { source -> source.firstOrNull { it.kind == kind } }
        }.sortedBy { it.start }
    }

    /** The file's chapters, when they are this file's and changed (they come after it loads). */
    private fun takeChapters(state: PlaybackState) {
        if (state.url != url || !state.fileLoaded || state.chapters == chaptersOf) return
        chaptersOf = state.chapters
        fromChapters = ChapterSkips.from(state.chapters, state.duration)
        merge()
    }

    private fun onState(state: PlaybackState) {
        takeChapters(state)
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
        private const val CHAPTERS_SETTLE_MILLIS = 2_000L
    }
}
