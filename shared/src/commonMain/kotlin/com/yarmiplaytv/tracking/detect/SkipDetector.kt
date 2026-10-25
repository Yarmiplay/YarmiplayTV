package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.sync.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds an episode's opening and ending by listening: the start (and the end) of the episode is compared with the
 * same part of the episodes before and after it in its season, and the stretch of sound they share, the opening
 * song (the ending song), is where it is. Two other episodes have to agree on it (one will do in a season of two).
 * What is learned is kept in the [cache], so the next episode usually needs only its own sound read, or none.
 *
 * Files on this device are always read; a server's episodes only on an unmetered network, unless the Playback
 * setting allows any. One file is read at a time.
 */
class SkipDetector(
    private val decoder: AudioDecoder,
    private val cache: FingerprintCache,
    private val siblings: SiblingFinder,
    private val settings: StateFlow<AppSettings>,
    private val unmetered: () -> Boolean = { DevicePlatform.onUnmeteredNetwork },
) {
    private val reading = Semaphore(1)
    /** One lock per fingerprint being made, so two asks for the same one read the file once. */
    private val making = ConcurrentHashMap<String, Mutex>()
    /** Fingerprints that couldn't be made (no such sound, an unknown codec), not tried again this session. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Where the playing episode's parts of [kinds] are, as far as listening can tell; [duration] is its length. */
    suspend fun detect(np: NowPlaying, duration: Double, kinds: Set<SkipSegment.Kind>): List<SkipSegment> {
        if (kinds.isEmpty() || duration <= 0) return emptyList()
        val season = siblings.of(np) ?: return emptyList()
        val me = EpisodeFile(season.number, np.url, np.fileName, np.sizeBytes, duration, remote = !SeekFrames.canPreview(np.url))
        return kinds.sortedBy { it.ordinal }.mapNotNull { kind -> find(me, season.others, kind) }
    }

    private suspend fun find(me: EpisodeFile, others: List<EpisodeFile>, kind: SkipSegment.Kind): SkipSegment? {
        val known = cache.observations(me.key, kind)
        val heard = ArrayList<List<Span>>()
        for (other in others.take(MAX_PARTNERS)) {
            val spans = known[other.key] ?: compare(me, other, kind) ?: continue
            heard += spans
            agreed(heard)?.let { return SkipSegment(kind, it.start, it.end) }
        }
        // A season of two has only the one other episode to go by.
        if (others.size == 1) heard.singleOrNull()?.firstOrNull()?.let { return SkipSegment(kind, it.start, it.end) }
        return null
    }

    /** The longest stretch two other episodes both share with this one, give or take [AGREE_SECONDS]. */
    private fun agreed(heard: List<List<Span>>): Span? {
        var best: Span? = null
        for (i in heard.indices) for (j in i + 1 until heard.size) {
            for (a in heard[i]) for (b in heard[j]) {
                if (abs(a.start - b.start) > AGREE_SECONDS || abs(a.end - b.end) > AGREE_SECONDS) continue
                val span = Span((a.start + b.start) / 2, (a.end + b.end) / 2)
                if (best == null || span.end - span.start > best.end - best.start) best = span
            }
        }
        return best
    }

    /** What [me] and [other] share in their [kind] part, also remembered for [other]; null when either can't be read. */
    private suspend fun compare(me: EpisodeFile, other: EpisodeFile, kind: SkipSegment.Kind): List<Span>? {
        val mine = fingerprint(me, kind) ?: return null
        val theirs = fingerprint(other, kind) ?: return null
        val (min, max) = if (kind == SkipSegment.Kind.OPENING) OPENING_SECONDS else ENDING_SECONDS
        val runs = withContext(Dispatchers.Default) {
            FingerprintMatcher.runs(mine.points, theirs.points, (min / Fingerprinter.HOP_SECONDS).roundToInt(), (max / Fingerprinter.HOP_SECONDS).roundToInt())
        }
        fun span(fp: FingerprintCache.Stored, start: Int, end: Int) =
            Span(round(fp.start + FingerprintMatcher.startSeconds(start)), round(fp.start + FingerprintMatcher.endSeconds(end)))
        val forMe = runs.map { span(mine, it.aStart, it.aEnd) }
        val forThem = runs.map { span(theirs, it.bStart, it.bEnd) }
        cache.observe(me.key, other.key, kind, forMe)
        cache.observe(other.key, me.key, kind, forThem)
        return forMe
    }

    private suspend fun fingerprint(episode: EpisodeFile, kind: SkipSegment.Kind): FingerprintCache.Stored? {
        val id = "${episode.key}/$kind"
        if (id in failed) return null
        cache.fingerprint(episode.key, kind)?.let { return it }
        if (episode.remote && !settings.value.playback.detectOnAnyNetwork && !unmetered()) return null
        val lock = making.computeIfAbsent(id) { Mutex() }
        return try {
            lock.withLock { cache.fingerprint(episode.key, kind) ?: make(episode, kind, id) }
        } finally {
            making.remove(id, lock)
        }
    }

    private suspend fun make(episode: EpisodeFile, kind: SkipSegment.Kind, id: String): FingerprintCache.Stored? = reading.withPermit {
        val job = currentCoroutineContext()[Job]
        val made = withContext(Dispatchers.IO) {
            // Interrupt the blocking decode thread when the job is cancelled (CI was flaking on
            // "file that changes while listening" when only polling isActive).
            val thread = Thread.currentThread()
            val cancelHandle = job?.invokeOnCompletion { thread.interrupt() }
            try {
                val duration = episode.duration ?: decoder.duration(episode.url) ?: return@withContext null
                val (from, seconds) = window(duration, kind)
                val fingerprinter = Fingerprinter()
                val start = decoder.decode(
                    episode.url, from, seconds, languages(),
                    sink = { pcm, n -> fingerprinter.consume(pcm, n) },
                    cancelled = { job?.isActive == false || thread.isInterrupted },
                ) ?: return@withContext null
                currentCoroutineContext().ensureActive()
                fingerprinter.finish().takeIf { it.size >= MIN_POINTS }?.let { FingerprintCache.Stored(start, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Can't listen to ${episode.fileName}", e)
                null
            } finally {
                cancelHandle?.dispose()
                Thread.interrupted() // clear interrupt for the pooled IO thread
            }
        }
        if (made == null) failed += id else cache.saveFingerprint(episode.key, kind, made)
        made
    }

    private fun languages(): List<String> =
        settings.value.playback.audioLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val TAG = "SkipDetector"
        /** Other episodes compared at most, for each part. */
        private const val MAX_PARTNERS = 4
        /** Two episodes agree on where the opening is when its start and end are this close. */
        const val AGREE_SECONDS = 3.0
        /** Shortest and longest opening, and ending. */
        private val OPENING_SECONDS = 15.0 to 120.0
        private val ENDING_SECONDS = 15.0 to 450.0
        /** A fingerprint shorter than an opening can be says nothing. */
        private val MIN_POINTS = (15 / Fingerprinter.HOP_SECONDS).toInt()

        /** The part of an episode of [duration] seconds listened to for [kind]: from, and how long. */
        fun window(duration: Double, kind: SkipSegment.Kind): Pair<Double, Double> = when (kind) {
            SkipSegment.Kind.OPENING -> 0.0 to minOf(duration * 0.25, 600.0)
            SkipSegment.Kind.ENDING -> minOf(duration * 0.25, 450.0).let { (duration - it) to it }
        }

        private fun round(seconds: Double): Double = (seconds * 100).roundToInt() / 100.0
    }
}
