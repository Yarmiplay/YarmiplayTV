package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.anilist.AniListAccount
import com.yarmiplaytv.media.anilist.AniListAnime
import com.yarmiplaytv.media.anilist.AniListCatalog
import com.yarmiplaytv.media.anilist.AniListClient
import com.yarmiplaytv.media.anilist.AniListDate
import com.yarmiplaytv.media.anilist.AniListEntryStatus
import com.yarmiplaytv.media.anilist.AniListMatch
import com.yarmiplaytv.media.anilist.AniListMatcher
import com.yarmiplaytv.media.anilist.AniListQueued
import com.yarmiplaytv.media.anilist.AniListStatus
import com.yarmiplaytv.media.anilist.AniListTokens
import com.yarmiplaytv.media.anilist.AniListUpdate
import com.yarmiplaytv.media.mal.MalMapping
import com.yarmiplaytv.player.Player
import com.yarmiplaytv.sync.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** What the AniList panel shows for the currently playing episode. */
sealed interface AniListState {
    data object NotConfigured : AniListState
    data object SignedOut : AniListState
    data object Idle : AniListState
    data class Ignored(val info: EpisodeInfo) : AniListState
    data class Matching(val info: EpisodeInfo) : AniListState
    data class Matched(val info: EpisodeInfo, val match: AniListMatch, val entry: AniListAnime) : AniListState
    data class Unsure(val info: EpisodeInfo, val candidates: List<AniListAnime>) : AniListState
    data class Failed(val info: EpisodeInfo, val message: String) : AniListState
}

/**
 * AniList for YarmiplayTV: account, episode matching, 90% auto-updates (with Undo or Ask-first),
 * manual entry matching, ignored shows, and offline retry queue. Runs independently of MyAnimeList
 * so users can use MAL, AniList, or both at the same time.
 */
class AniListService(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val settings: StateFlow<AppSettings>,
    val client: AniListClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val catalog = AniListCache(client, { settings.value.anilist?.userId }, now)
    private val matcher = AniListMatcher(catalog)
    private val publicMatcher = AniListMatcher(AniListCache(client, { null }, now))

    private val _state = MutableStateFlow<AniListState>(if (client.configured) AniListState.Idle else AniListState.NotConfigured)
    val state: StateFlow<AniListState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<MalNotice?>(null)
    val notice: StateFlow<MalNotice?> = _notice.asStateFlow()

    private var lastUndo: (() -> Unit)? = null
        set(value) {
            field = value
            _canUndo.value = value != null
        }
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private var playing: EpisodeInfo? = null
    private var matchJob: Job? = null
    private var watchedPending: EpisodeInfo? = null
    private val queueLock = Mutex()
    private var retryJob: Job? = null
    private var backoffMinutes = 1L

    val signedIn: Boolean get() = client.tokens != null && settings.value.anilist != null

    init {
        client.tokens = settings.value.anilist?.tokens
        client.onExpired = { scope.launch { signOut(expired = true) } }
        if (signedIn && settings.value.anilistQueue.isNotEmpty()) scheduleRetry(0)
    }

    /** Completes sign-in from the Implicit Grant token received from AniList's OAuth page. */
    suspend fun signIn(tokens: AniListTokens) {
        client.tokens = tokens
        val account = try {
            client.viewer()
        } catch (e: Exception) {
            client.tokens = null
            throw e
        }
        store.saveAniListAccount(account)
        Logger.i(TAG, "Signed in to AniList as ${account.userName}")
        playing?.let { rematch(it) }
        if (settings.value.anilistQueue.isNotEmpty()) scheduleRetry(0)
    }

    /** Completes sign-in directly from a pasted token or OAuth redirect fragment. */
    suspend fun signInWithFragmentOrToken(input: String) {
        val trimmed = input.trim()
        val parsed = client.parseFragment(trimmed)
            ?: trimmed.takeIf { it.length >= 20 && !it.contains(' ') }?.let { AniListTokens(it, now() + 31_536_000_000L) }
            ?: throw MediaSourceException("Invalid AniList token")
        signIn(parsed)
    }

    fun signOut(expired: Boolean = false) {
        client.tokens = null
        catalog.clear()
        retryJob?.cancel()
        lastUndo = null
        watchedPending = null
        matchJob?.cancel()
        _state.value = if (client.configured) AniListState.SignedOut else AniListState.NotConfigured
        _notice.value = if (expired) MalNotice("Signed out of AniList; sign in again in Settings") else null
        scope.launch { store.clearAniList() }
    }

    fun playing(info: EpisodeInfo?) {
        if (info == playing) return
        playing = info
        watchedPending = null
        rematch(info)
    }

    fun refresh() = rematch(playing)

    private fun rematch(info: EpisodeInfo?) {
        matchJob?.cancel()
        _state.value = when {
            !client.configured -> AniListState.NotConfigured
            !signedIn -> AniListState.SignedOut
            info == null -> AniListState.Idle
            info.seriesKey in settings.value.anilistIgnored -> AniListState.Ignored(info)
            else -> AniListState.Matching(info)
        }
        if (_state.value !is AniListState.Matching || info == null) return
        matchJob = scope.launch { _state.value = lookup(info) }
    }

    suspend fun lookup(info: EpisodeInfo): AniListState = try {
        when {
            !client.configured -> AniListState.NotConfigured
            !signedIn -> AniListState.SignedOut
            info.seriesKey in settings.value.anilistIgnored -> AniListState.Ignored(info)
            else -> {
                val match = matcher.match(info, settings.value.anilistMappings[mappingKey(info)])
                when {
                    match == null -> AniListState.Idle
                    match.sure -> AniListState.Matched(info, match, catalog.anime(match.anime.id))
                    else -> AniListState.Unsure(info, (listOf(match.anime) + match.candidates).distinctBy { it.id })
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AniListState.Failed(info, e.message ?: "AniList lookup failed")
    }

    /** Public or signed-in identification (returns `AniListMatch`, whose `anime.idMal` can feed AniSkip!). */
    suspend fun identify(info: EpisodeInfo): AniListMatch? {
        if (info.seriesKey in settings.value.anilistIgnored) return null
        if ((_state.value as? AniListState.Matching)?.info == info) matchJob?.join()
        (_state.value as? AniListState.Matched)?.takeIf { it.info == info }?.let { return it.match }
        val match = (if (signedIn) matcher else publicMatcher).match(info, settings.value.anilistMappings[mappingKey(info)])
        return match?.takeIf { it.sure }
    }

    fun watched(info: EpisodeInfo) {
        if (!client.configured || !signedIn || !settings.value.anilistAutoUpdate || info.seriesKey in settings.value.anilistIgnored) return
        scope.launch {
            matchJob?.join()
            when (val s = _state.value) {
                is AniListState.Matched -> if (s.info == info) {
                    if (settings.value.anilistAskFirst) {
                        _notice.value = MalNotice(
                            "Mark episode ${s.match.episode} of ${s.entry.title} watched on AniList?",
                            confirm = { scope.launch { record(s.entry, s.match.episode, automatic = true) } },
                        )
                    } else {
                        record(s.entry, s.match.episode, automatic = true)
                    }
                }
                is AniListState.Unsure -> if (s.info == info) {
                    watchedPending = info
                    _notice.value = MalNotice("Which AniList entry is ${info.seriesName}? Pick it in the AniList panel", pick = true)
                }
                else -> Unit
            }
        }
    }

    fun choose(info: EpisodeInfo, anime: AniListAnime, episode: Int) {
        val key = mappingKey(info)
        val mapping = MalMapping(anime.id, offset = (info.episode ?: episode) - episode)
        scope.launch {
            store.saveAniListMapping(key, mapping)
            val full = runCatching { catalog.anime(anime.id) }.getOrDefault(anime)
            if (info == playing) _state.value = AniListState.Matched(info, AniListMatch(full, episode, sure = true), full)
            if (watchedPending == info) {
                watchedPending = null
                record(full, episode, automatic = true)
            }
        }
    }

    suspend fun findShows(query: String): List<AniListAnime> = client.search(query, limit = 20)

    suspend fun myList(status: AniListStatus): List<AniListAnime> {
        val uid = settings.value.anilist?.userId ?: return emptyList()
        return client.myList(uid, status)
    }

    suspend fun remove(anime: AniListAnime): Boolean {
        val entryId = anime.listEntry?.id?.takeIf { it > 0 } ?: return false
        return try {
            client.deleteEntry(entryId)
            catalog.invalidate(anime.id)
            updateMatched(anime.id, null)
            _notice.value = MalNotice("Removed ${anime.title} from AniList")
            true
        } catch (e: Exception) {
            _notice.value = MalNotice(e.message ?: "Couldn't remove ${anime.title} from AniList")
            false
        }
    }

    fun ignore(info: EpisodeInfo) {
        scope.launch {
            val next = settings.value.anilistIgnored + info.seriesKey
            val names = settings.value.anilistIgnoredNames + (info.seriesKey to info.seriesName)
            store.saveAniListIgnored(next, names)
            if (info == playing) _state.value = AniListState.Ignored(info)
        }
    }

    fun unignore(seriesKey: String) {
        scope.launch {
            val next = settings.value.anilistIgnored - seriesKey
            store.saveAniListIgnored(next, settings.value.anilistIgnoredNames - seriesKey)
            if (playing?.seriesKey == seriesKey) rematch(playing)
        }
    }

    fun undo() {
        lastUndo?.invoke()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    suspend fun update(anime: AniListAnime, update: AniListUpdate): AniListEntryStatus? = try {
        val saved = client.saveEntry(anime.id, update)
        catalog.invalidate(anime.id)
        updateMatched(anime.id, saved)
        saved
    } catch (e: Exception) {
        if (e.httpCode() == 401) throw e
        enqueue(AniListQueued(anime.id, update))
        _notice.value = MalNotice("AniList unreachable; queued update for ${anime.title}")
        null
    }

    private suspend fun record(anime: AniListAnime, episode: Int, automatic: Boolean) {
        val before = anime.listEntry
        val prevProgress = before?.progress ?: 0
        val isCompletedBefore = before?.statusValue == AniListStatus.COMPLETED
        val isRewatching = before?.statusValue == AniListStatus.REPEATING || isCompletedBefore
        val total = anime.episodes
        val nextProgress = if (isCompletedBefore) episode else maxOf(prevProgress, episode)
        if (!isCompletedBefore && prevProgress >= episode && before?.statusValue == AniListStatus.CURRENT) return
        val finished = total > 0 && nextProgress >= total
        val date = AniListDate.fromIso(today())
        val upd = AniListUpdate(
            status = when {
                finished -> AniListStatus.COMPLETED
                isRewatching -> AniListStatus.REPEATING
                else -> AniListStatus.CURRENT
            },
            progress = nextProgress,
            repeat = if (finished && isRewatching) (before?.repeat ?: 0) + 1 else null,
            startedAt = if (before == null || before.startedAt == null) date else null,
            completedAt = if (finished) date else null,
        )
        val saved = update(anime, upd) ?: return
        if (automatic) {
            val restoreAction: () -> Unit = {
                lastUndo = null
                scope.launch {
                    if (before != null) update(anime, AniListUpdate.restoring(before))
                    else if (saved.id > 0) remove(anime.copy(listEntry = saved))
                    _notice.value = MalNotice("Undid AniList update for ${anime.title}")
                }
            }
            lastUndo = restoreAction
            val verb = if (finished) "Completed" else "Ep $nextProgress of"
            _notice.value = MalNotice("AniList: $verb ${anime.title}", undo = restoreAction)
        }
    }

    private fun updateMatched(mediaId: Int, entry: AniListEntryStatus?) {
        val cur = _state.value as? AniListState.Matched ?: return
        if (cur.entry.id == mediaId) {
            _state.value = cur.copy(entry = cur.entry.copy(listEntry = entry))
        }
    }

    private suspend fun enqueue(item: AniListQueued) = queueLock.withLock {
        val next = settings.value.anilistQueue.filterNot { it.mediaId == item.mediaId } + item
        store.saveAniListQueue(next)
        scheduleRetry(1)
    }

    private fun scheduleRetry(minutes: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (minutes > 0) delay(minutes * 60_000L)
            flushQueue()
        }
    }

    suspend fun flushQueue() = queueLock.withLock {
        val pending = settings.value.anilistQueue
        if (pending.isEmpty() || !signedIn) return
        val remaining = ArrayList<AniListQueued>()
        for (item in pending) {
            val ok = runCatching { client.saveEntry(item.mediaId, item.update) }.isSuccess
            if (!ok) remaining += item
        }
        store.saveAniListQueue(remaining)
        if (remaining.isNotEmpty()) {
            backoffMinutes = (backoffMinutes * 2).coerceAtMost(30)
            scheduleRetry(backoffMinutes)
        } else {
            backoffMinutes = 1
        }
    }

    private fun Exception.httpCode(): Int? = (this as? MediaSourceException)?.httpCode

    companion object {
        private const val TAG = "AniListService"

        fun mappingKey(info: EpisodeInfo): String =
            if (info.season != null) "${info.seriesKey}#s${info.season}" else info.seriesKey
    }
}

private class AniListCache(
    private val client: AniListClient,
    private val userId: () -> Int?,
    private val now: () -> Long,
) : AniListCatalog {
    private val animeById = ConcurrentHashMap<Int, Pair<Long, AniListAnime>>()
    private var watchingCache: Pair<Long, List<AniListAnime>>? = null
    private var completedCache: Pair<Long, List<AniListAnime>>? = null

    override suspend fun search(query: String): List<AniListAnime> = client.search(query)

    override suspend fun anime(id: Int): AniListAnime {
        val cached = animeById[id]
        if (cached != null && now() - cached.first < TTL_MS) return cached.second
        return client.anime(id).also { animeById[id] = now() to it }
    }

    override suspend fun watching(): List<AniListAnime> {
        val uid = userId() ?: return emptyList()
        val cached = watchingCache
        if (cached != null && now() - cached.first < LIST_TTL_MS) return cached.second
        val list = client.myList(uid, AniListStatus.CURRENT) + client.myList(uid, AniListStatus.REPEATING)
        watchingCache = now() to list
        return list
    }

    override suspend fun completed(): List<AniListAnime> {
        val uid = userId() ?: return emptyList()
        val cached = completedCache
        if (cached != null && now() - cached.first < LIST_TTL_MS) return cached.second
        val list = client.myList(uid, AniListStatus.COMPLETED)
        completedCache = now() to list
        return list
    }

    fun invalidate(id: Int) {
        animeById.remove(id)
        watchingCache = null
        completedCache = null
    }

    fun clear() {
        animeById.clear()
        watchingCache = null
        completedCache = null
    }

    companion object {
        private const val TTL_MS = 10 * 60 * 1000L
        private const val LIST_TTL_MS = 2 * 60 * 1000L
    }
}

/** Watches playback and reports 90% watched episodes to [AniListService], mirroring [MalTracker]. */
class AniListTracker(
    scope: CoroutineScope,
    player: Player,
    nowPlaying: StateFlow<NowPlaying?>,
    private val service: AniListService,
) {
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
                if (duration > 0 && (state.eofReached || state.position >= duration * MalTracker.WATCHED_FRACTION)) {
                    reported = true
                    service.watched(info)
                }
            }
        }
    }
}
