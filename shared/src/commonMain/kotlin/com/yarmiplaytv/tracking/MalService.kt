package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.mal.MalAccount
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.media.mal.MalCatalog
import com.yarmiplaytv.media.mal.MalClient
import com.yarmiplaytv.media.mal.MalListStatus
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.media.mal.MalMapping
import com.yarmiplaytv.media.mal.MalMatch
import com.yarmiplaytv.media.mal.MalMatcher
import com.yarmiplaytv.media.mal.MalQueued
import com.yarmiplaytv.media.mal.MalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** What the MyAnimeList panel shows for an episode. */
sealed interface MalState {
    /** This build has no Client ID. */
    data object NotConfigured : MalState
    data object SignedOut : MalState
    /** Nothing playing, or not an episode of a show. */
    data object Idle : MalState
    data class Ignored(val info: EpisodeInfo) : MalState
    data class Matching(val info: EpisodeInfo) : MalState
    /** [entry] is the matched anime with your list status as it is now. */
    data class Matched(val info: EpisodeInfo, val match: MalMatch, val entry: MalAnime) : MalState
    /** Not sure which entry this is: the user picks from [candidates]. */
    data class Unsure(val info: EpisodeInfo, val candidates: List<MalAnime>) : MalState
    data class Failed(val info: EpisodeInfo, val message: String) : MalState
}

/**
 * A MyAnimeList message for the player: [text] with, when set, an Undo, a "pick the entry" or a "yes, update"
 * action. TV shows the text and leaves the actions to the MyAnimeList panel.
 */
data class MalNotice(val text: String, val undo: (() -> Unit)? = null, val pick: Boolean = false, val confirm: (() -> Unit)? = null, val at: Long = System.nanoTime()) {
    /** A question stays up long enough to answer; anything else is a brief note in the corner. */
    val question: Boolean get() = confirm != null || pick
    val showMillis: Long get() = if (question) 30_000 else 2_000
}

/**
 * MyAnimeList for the app: the account, which entry the playing episode is, and the list updates (automatic when
 * an episode has been watched, with Undo; or by hand from the panel). Nothing is stored anywhere but this device,
 * and signing out deletes it all.
 */
class MalService(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val settings: StateFlow<AppSettings>,
    val client: MalClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val catalog = MalCache(client, now)
    private val matcher = MalMatcher(catalog)

    private val _state = MutableStateFlow<MalState>(if (client.configured) MalState.Idle else MalState.NotConfigured)
    val state: StateFlow<MalState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<MalNotice?>(null)
    val notice: StateFlow<MalNotice?> = _notice.asStateFlow()

    /** The last automatic update, until the next one: what Undo puts back. */
    private var lastUndo: (() -> Unit)? = null
        set(value) {
            field = value
            _canUndo.value = value != null
        }
    private val _canUndo = MutableStateFlow(false)
    /** Whether the last automatic update can still be undone. */
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private var playing: EpisodeInfo? = null
    private var matchJob: Job? = null
    /** Watched before the match was known (or while unsure): applied once it is. */
    private var watchedPending: EpisodeInfo? = null
    private val queueLock = Mutex()
    private var retryJob: Job? = null
    private var backoffMinutes = 1L

    val signedIn: Boolean get() = client.tokens != null

    init {
        client.tokens = settings.value.mal?.tokens
        client.onTokens = { tokens ->
            scope.launch {
                val account = settings.value.mal
                if (tokens == null) signOut(expired = true)
                else if (account != null) store.saveMalAccount(account.copy(tokens = tokens))
            }
        }
        if (signedIn && settings.value.malQueue.isNotEmpty()) scheduleRetry(0)
    }

    // --- Account -----------------------------------------------------------------------------

    /** Finishes signing in with the code MyAnimeList's page sent back. */
    suspend fun signIn(code: String, verifier: String) {
        val tokens = client.exchange(code, verifier)
        val me = client.me()
        store.saveMalAccount(MalAccount(tokens, me.name, me.picture))
        Logger.i(TAG, "Signed in to MyAnimeList")
        playing?.let { rematch(it) }
        if (settings.value.malQueue.isNotEmpty()) scheduleRetry(0)
    }

    /** Signs out and deletes everything MyAnimeList on this device: the account, matches, queue and cached content. */
    fun signOut(expired: Boolean = false) {
        client.tokens = null
        catalog.clear()
        retryJob?.cancel()
        lastUndo = null
        watchedPending = null
        matchJob?.cancel()
        _state.value = if (client.configured) MalState.SignedOut else MalState.NotConfigured
        _notice.value = if (expired) MalNotice("Signed out of MyAnimeList; sign in again in Settings") else null
        scope.launch { store.clearMal() }
    }

    // --- Playing ---------------------------------------------------------------------------------

    /** The episode now playing (null for none, or something that isn't an episode). */
    fun playing(info: EpisodeInfo?) {
        if (info == playing) return
        playing = info
        watchedPending = null
        rematch(info)
    }

    /** Looks the playing episode up again (after an error, or a change made elsewhere). */
    fun refresh() = rematch(playing)

    private fun rematch(info: EpisodeInfo?) {
        matchJob?.cancel()
        _state.value = when {
            !client.configured -> MalState.NotConfigured
            !signedIn -> MalState.SignedOut
            info == null -> MalState.Idle
            info.seriesKey in settings.value.malIgnored -> MalState.Ignored(info)
            else -> MalState.Matching(info)
        }
        if (_state.value !is MalState.Matching || info == null) return
        matchJob = scope.launch { _state.value = lookup(info) }
    }

    /** What [info] is on MyAnimeList (for the playing episode, or one picked in the library). */
    suspend fun lookup(info: EpisodeInfo): MalState = try {
        when {
            !client.configured -> MalState.NotConfigured
            !signedIn -> MalState.SignedOut
            info.seriesKey in settings.value.malIgnored -> MalState.Ignored(info)
            else -> {
                val match = matcher.match(info, settings.value.malMappings[mappingKey(info)])
                when {
                    match == null -> MalState.Idle
                    match.sure -> MalState.Matched(info, match, catalog.anime(match.anime.id))
                    else -> MalState.Unsure(info, (listOf(match.anime) + match.candidates).distinctBy { it.id })
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MalState.Failed(info, e.message ?: "MyAnimeList lookup failed")
    }

    /** The playing episode has been watched (90% through). */
    fun watched(info: EpisodeInfo) {
        if (!client.configured || !signedIn || !settings.value.malAutoUpdate || info.seriesKey in settings.value.malIgnored) return
        scope.launch {
            matchJob?.join()
            when (val s = _state.value) {
                is MalState.Matched -> if (s.info == info) {
                    if (settings.value.malAskFirst) {
                        _notice.value = MalNotice(
                            "Mark episode ${s.match.episode} of ${s.entry.title} watched on MyAnimeList?",
                            confirm = { scope.launch { record(s.entry, s.match.episode, automatic = true) } },
                        )
                    } else {
                        record(s.entry, s.match.episode, automatic = true)
                    }
                }
                is MalState.Unsure -> if (s.info == info) {
                    watchedPending = info
                    _notice.value = MalNotice("Which MyAnimeList entry is ${info.seriesName}? Pick it in the MyAnimeList panel", pick = true)
                }
                else -> Unit
            }
        }
    }

    // --- Choosing the entry ----------------------------------------------------------------------

    /** The user says [info] is episode [episode] of [anime]: remembered for the show, and applied if it was watched. */
    fun choose(info: EpisodeInfo, anime: MalAnime, episode: Int) {
        val key = mappingKey(info)
        val mapping = MalMapping(anime.id, offset = (info.episode ?: episode) - episode)
        scope.launch {
            store.saveMalMapping(key, mapping)
            val full = runCatching { catalog.anime(anime.id) }.getOrDefault(anime)
            if (info == playing) _state.value = MalState.Matched(info, MalMatch(full, episode, sure = true), full)
            if (watchedPending == info) {
                watchedPending = null
                record(full, episode, automatic = true)
            }
        }
    }

    suspend fun search(query: String): List<MalAnime> = runCatching { client.search(query, limit = 15) }.getOrDefault(emptyList())

    /** Your MyAnimeList entries with [status] (for the MyAnimeList screen); [fresh] reads them again. */
    suspend fun myList(status: MalStatus, fresh: Boolean = false): List<Pair<MalAnime, MalListStatus>> = catalog.list(status, fresh)

    /** An anime's details (cached for at most 24 hours), e.g. a matched show's title. */
    suspend fun anime(id: Int): MalAnime = catalog.anime(id)

    /** Stop (or start again) tracking [seriesKey]. */
    fun ignore(seriesKey: String, ignored: Boolean, name: String = "") {
        scope.launch {
            val set = settings.value.malIgnored.let { if (ignored) it + seriesKey else it - seriesKey }
            val names = settings.value.malIgnoredNames.let { if (ignored && name.isNotBlank()) it + (seriesKey to name) else it }
            store.saveMalIgnored(set, names)
            playing?.let { if (it.seriesKey == seriesKey) rematchAfterSave(it, ignored) }
        }
    }

    private fun rematchAfterSave(info: EpisodeInfo, ignored: Boolean) {
        if (ignored) {
            matchJob?.cancel()
            _state.value = MalState.Ignored(info)
        } else {
            _state.value = MalState.Matching(info)
            matchJob = scope.launch { _state.value = lookup(info) }
        }
    }

    // --- List updates ----------------------------------------------------------------------------

    /**
     * Episode [episode] of [anime] has been watched: progress only goes up; the first episode starts it (watching,
     * with today's start date), the last completes it (with a finish date), and watching a completed show again
     * marks it as a rewatch.
     */
    suspend fun record(anime: MalAnime, episode: Int, automatic: Boolean) {
        // The list may have changed elsewhere since the match (another device, the website): read it again.
        val current = runCatching { client.anime(anime.id) }.getOrNull()?.also(catalog::put) ?: anime
        val before = current.myListStatus
        val update = progressUpdate(before, episode, current.numEpisodes, today()) ?: return
        send(current, update, before, automatic)
    }

    /** Changes [anime]'s list entry now and returns it as it is after (for lists that show it), or null if it failed. */
    suspend fun update(anime: MalAnime, update: MalListUpdate): MalListStatus? {
        send(anime, update, anime.myListStatus, automatic = false)
        return catalog.anime(anime.id).myListStatus
    }

    /** Changes [anime]'s list entry by hand (status, score, episodes, rewatching). */
    fun edit(anime: MalAnime, update: MalListUpdate) {
        scope.launch { send(anime, update, anime.myListStatus, automatic = false) }
    }

    private suspend fun send(anime: MalAnime, update: MalListUpdate, before: MalListStatus?, automatic: Boolean) {
        try {
            val after = client.updateStatus(anime.id, update)
            val updated = anime.copy(myListStatus = after)
            catalog.put(updated)
            refreshState(updated)
            if (automatic) {
                val undo = before?.let { MalListUpdate.restoring(it) }
                lastUndo = {
                    scope.launch {
                        if (undo != null) send(updated, undo, after, automatic = false) else runCatching { client.deleteStatus(anime.id) }
                        lastUndo = null
                        _notice.value = MalNotice("MAL: undone")
                        if (undo == null) refreshState(updated.copy(myListStatus = null))
                    }
                }
                val of = if (anime.numEpisodes > 0) "/${anime.numEpisodes}" else ""
                val completed = after.statusValue == MalStatus.COMPLETED && before?.statusValue != MalStatus.COMPLETED
                _notice.value = MalNotice("MAL: ${anime.title} · " + if (completed) "completed" else "ep ${after.watchedEpisodes}$of", undo = lastUndo)
            }
            if (settings.value.malQueue.isNotEmpty()) scheduleRetry(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401 && !signedIn) return
            if (e.retryLater) {
                enqueue(MalQueued(anime.id, update))
                _notice.value = MalNotice("MyAnimeList can't be reached; the update will be sent later")
            } else {
                _notice.value = MalNotice("MyAnimeList didn't take the update: ${e.message}")
            }
        }
    }

    fun undo() {
        lastUndo?.invoke()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun refreshState(updated: MalAnime) {
        val s = _state.value
        if (s is MalState.Matched && s.entry.id == updated.id) _state.value = s.copy(entry = updated)
    }

    // --- Offline queue ---------------------------------------------------------------------------

    private suspend fun enqueue(item: MalQueued) = queueLock.withLock {
        val queue = settings.value.malQueue.filterNot { it.animeId == item.animeId && it.update.watchedEpisodes != null && item.update.watchedEpisodes != null } + item
        store.saveMalQueue(queue)
        scheduleRetry(backoffMinutes)
    }

    private fun scheduleRetry(minutes: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(minutes * 60_000)
            flushQueue()
        }
    }

    /** Sends the queued updates in order; stops at the first that can't go yet and backs off (1, 2, 4 … 30 minutes). */
    suspend fun flushQueue() {
        if (!signedIn) return
        queueLock.withLock {
            var queue = settings.value.malQueue
            while (queue.isNotEmpty()) {
                val next = queue.first()
                try {
                    client.updateStatus(next.animeId, next.update)
                    catalog.forget(next.animeId)
                } catch (e: MediaSourceException) {
                    if (e.retryLater) {
                        backoffMinutes = (backoffMinutes * 2).coerceAtMost(30)
                        scheduleRetry(backoffMinutes)
                        return
                    }
                    Logger.w(TAG, "Dropping a queued MyAnimeList update: ${e.message}")
                }
                queue = queue.drop(1)
                store.saveMalQueue(queue)
            }
            backoffMinutes = 1
        }
    }

    companion object {
        private const val TAG = "MalService"

        /** Saved matches are per show and season: seasons of a show can be different entries. */
        fun mappingKey(info: EpisodeInfo): String = "${info.seriesKey}|${info.season ?: 0}"

        /**
         * The update for having watched [episode] of an entry with [total] episodes (0 = unknown), from list status
         * [before]; null when there's nothing to change (an episode already counted).
         */
        fun progressUpdate(before: MalListStatus?, episode: Int, total: Int, today: String): MalListUpdate? {
            val status = before?.statusValue
            val watched = before?.watchedEpisodes ?: 0
            val rewatching = status == MalStatus.COMPLETED || before?.isRewatching == true
            if (rewatching) {
                val finished = total in 1..episode
                return when {
                    finished -> MalListUpdate(isRewatching = false, timesRewatched = (before?.timesRewatched ?: 0) + 1, watchedEpisodes = total)
                    before?.isRewatching != true -> MalListUpdate(isRewatching = true, watchedEpisodes = episode)
                    episode > watched -> MalListUpdate(watchedEpisodes = episode)
                    else -> null
                }
            }
            if (episode <= watched && status == MalStatus.WATCHING) return null
            val progress = maxOf(watched, episode)
            val complete = total in 1..progress
            return MalListUpdate(
                status = if (complete) MalStatus.COMPLETED else MalStatus.WATCHING,
                watchedEpisodes = progress,
                startDate = today.takeIf { before?.startDate == null },
                finishDate = today.takeIf { complete && before?.finishDate == null },
            )
        }
    }
}

/**
 * Anime details from MyAnimeList, kept in memory for at most [ttlMillis] (24 hours: the API agreement's limit for
 * keeping its content); never written to disk.
 */
internal class MalCache(private val client: MalClient, private val now: () -> Long, private val ttlMillis: Long = 24 * 60 * 60 * 1000L) : MalCatalog {
    private class Entry(val anime: MalAnime, val at: Long)
    private val entries = ConcurrentHashMap<Int, Entry>()
    private val searches = ConcurrentHashMap<String, Pair<Long, List<MalAnime>>>()
    /** The user's list by status; refreshed after 10 minutes or any change made here. */
    private val lists = ConcurrentHashMap<MalStatus, Pair<Long, List<Pair<MalAnime, MalListStatus>>>>()

    override suspend fun search(query: String): List<MalAnime> {
        val key = query.trim().lowercase()
        searches[key]?.takeIf { now() - it.first < ttlMillis }?.let { return it.second }
        return client.search(query).also { searches[key] = now() to it }
    }

    override suspend fun anime(id: Int): MalAnime {
        entries[id]?.takeIf { now() - it.at < ttlMillis }?.let { return it.anime }
        return client.anime(id).also { put(it) }
    }

    override suspend fun watching(): List<Pair<MalAnime, MalListStatus>> = list(MalStatus.WATCHING)

    override suspend fun completed(): List<Pair<MalAnime, MalListStatus>> = list(MalStatus.COMPLETED)

    /** Your list entries with [status], newest change first; [fresh] reads them again. */
    suspend fun list(status: MalStatus, fresh: Boolean = false): List<Pair<MalAnime, MalListStatus>> {
        if (!fresh) lists[status]?.takeIf { now() - it.first < 10 * 60_000 }?.let { return it.second }
        return client.myList(status).also { lists[status] = now() to it }
    }

    fun put(anime: MalAnime) {
        entries[anime.id] = Entry(anime, now())
        lists.clear()
    }

    fun forget(id: Int) {
        entries.remove(id)
        lists.clear()
    }

    fun clear() {
        entries.clear()
        searches.clear()
        lists.clear()
    }
}

/** No answer, MyAnimeList busy (429) or failing (5xx): worth sending again later. */
private val MediaSourceException.retryLater: Boolean get() = httpCode.let { it == null || it == 429 || it >= 500 }
