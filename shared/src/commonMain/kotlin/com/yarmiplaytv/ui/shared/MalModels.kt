package com.yarmiplaytv.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.media.mal.MalClient
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.tracking.MalService
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.tracking.AvailableEpisode
import com.yarmiplaytv.tracking.MalCallbackServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The privacy policy, shown before signing in to MyAnimeList. */
const val PRIVACY_POLICY_URL = "https://tv.yarmiplay.com/privacy/"

/** What signing in to MyAnimeList shares and keeps, shown before the sign-in page opens. */
const val MAL_PRIVACY_NOTE =
    "YarmiplayTV updates your MyAnimeList list as you watch, and lets you rate shows and change their status. " +
        "You sign in on MyAnimeList's own page; YarmiplayTV never sees your password. Kept on this device only: " +
        "the sign-in tokens, your user name and picture, the shows you matched, and updates waiting to be sent. " +
        "Your list updates go only to MyAnimeList, and signing out deletes all of it."

/** Plain-text attribution for MyAnimeList content (no logo, no claim of endorsement). */
const val MAL_ATTRIBUTION = "Data from MyAnimeList"

/**
 * Signing in to MyAnimeList with OAuth: in the browser with a loopback callback on desktop, or in a web view that
 * catches the redirect on Android ([onRedirect]). The password is only ever typed on MyAnimeList's page.
 */
class MalLoginModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var verifier = ""
    private var state = ""
    private var job: Job? = null

    val configured: Boolean get() = container.mal.client.configured

    /** A new sign-in: MyAnimeList's page to open. */
    fun begin(): String {
        verifier = MalClient.newVerifier()
        state = MalClient.newState()
        error = null
        return container.mal.client.authorizeUrl(state, verifier)
    }

    /** Opens MyAnimeList's page with [open] (the browser) and waits for it to come back to this device. */
    fun signInWithBrowser(open: (String) -> Unit, onSignedIn: () -> Unit) {
        job?.cancel()
        val url = begin()
        status = "Sign in on MyAnimeList's page in your browser, then come back here."
        val expected = state
        job = scope.launch {
            try {
                val code = MalCallbackServer().await(expected)
                finish(code, onSignedIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = null
                error = e.message ?: "Sign-in failed"
            }
        }
        runCatching { open(url) }.onFailure {
            job?.cancel()
            status = null
            error = "Couldn't open a browser"
        }
    }

    /** Android web view: [url] is where the page is going. True (handled here) when it's the sign-in's redirect. */
    fun onRedirect(url: String, onSignedIn: () -> Unit): Boolean {
        if (!url.startsWith(MalClient.REDIRECT_URI)) return false
        val query = MalCallbackServer.parseQuery(url.substringAfter('?', ""))
        when {
            query["state"] != state -> error = "This sign-in is out of date; start again"
            query["error"] != null -> error = "Sign-in was cancelled"
            query["code"].isNullOrEmpty() -> error = "MyAnimeList didn't send a sign-in code"
            else -> scope.launch { finish(query.getValue("code")!!, onSignedIn) }
        }
        return true
    }

    private suspend fun finish(code: String, onSignedIn: () -> Unit) {
        busy = true
        status = "Signing in…"
        try {
            container.mal.signIn(code, verifier)
            status = null
            onSignedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = null
            error = e.message ?: "Sign-in failed"
        } finally {
            busy = false
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        status = null
    }
}

@Composable
fun rememberMalLoginModel(container: AppContainer): MalLoginModel {
    val scope = rememberCoroutineScope()
    val model = remember { MalLoginModel(container, scope) }
    DisposableEffect(model) { onDispose { model.cancel() } }
    return model
}

/**
 * A library item as [EpisodeInfo], to look it up on MyAnimeList from the library: an episode as it is, and a show
 * or season as its first episode (the entry is what matters). Its `seriesKey` is the one playback uses.
 */
fun MediaItem.malInfo(): EpisodeInfo? = when (type) {
    MediaItemType.EPISODE -> seriesName?.let { EpisodeInfo(it, parentIndexNumber, indexNumber ?: 1, year, seriesKey = "$sourceKey/${seriesId ?: it}") }
    MediaItemType.SEASON -> seriesName?.let { EpisodeInfo(it, indexNumber, 1, year, seriesKey = "$sourceKey/${seriesId ?: it}") }
    MediaItemType.SERIES -> EpisodeInfo(name, 1, 1, year, seriesKey = "$sourceKey/$id")
    else -> null
}

/** Plays [episode]: for everyone when in a room (as the library's Play does), else here. */
fun playEpisode(container: AppContainer, episode: AvailableEpisode) {
    val inRoom = container.sync.room.value.status == ConnectionStatus.CONNECTED
    episode.item?.let { if (inRoom) container.playlist.playInRoom(it) else container.playlist.playHere(it) }
        ?: episode.localUri?.let { container.playlist.playLocal(it, inRoom) }
}

/** Adds [episode] to the room's shared playlist. */
fun addEpisodeToPlaylist(container: AppContainer, episode: AvailableEpisode) {
    episode.item?.let(container.playlist::addToRoomPlaylist) ?: episode.localUri?.let(container.playlist::addLocalToRoomPlaylist)
}

/** The episode to watch next: the one after those watched (the first when rewatching from the start), if it's here. */
fun nextEpisode(episodes: List<AvailableEpisode>, watched: Int): AvailableEpisode? = episodes.firstOrNull { it.number == watched + 1 }

/**
 * Searching MyAnimeList for any show from its screens, to add it to your list or change its status, score and
 * episodes watched right there. [changes] counts the changes made, for lists shown beside it to read again.
 */
class MalSearchModel(private val mal: MalService, private val signedIn: () -> Boolean, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    /** The shows found, each with your list entry; null before the first search. */
    var results by mutableStateOf<List<MalAnime>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var changes by mutableStateOf(0)
        private set
    private var job: Job? = null

    /** Changing the list needs an account; searching doesn't. */
    val canChange: Boolean get() = signedIn()

    fun search() {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            error = "Type at least $MIN_QUERY letters"
            return
        }
        job?.cancel()
        error = null
        busy = true
        val mine = scope.launch {
            try {
                results = mal.findShows(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't search MyAnimeList"
            }
        }
        job = mine
        mine.invokeOnCompletion { if (job === mine) busy = false }
    }

    /** Changes [anime]'s list entry (adding it when it isn't on the list) and shows it as it is after. */
    fun update(anime: MalAnime, update: MalListUpdate) {
        scope.launch {
            val after = mal.update(anime, update) ?: return@launch
            replace(anime.id) { it.copy(myListStatus = after) }
            changes++
        }
    }

    /** One more episode watched, with the start and finish dates and status that go with it. */
    fun watchedOneMore(anime: MalAnime) {
        val list = anime.myListStatus
        val next = (list?.watchedEpisodes ?: 0) + 1
        update(anime, MalService.progressUpdate(list, next, anime.numEpisodes, java.time.LocalDate.now().toString()) ?: MalListUpdate(watchedEpisodes = next))
    }

    fun remove(anime: MalAnime) {
        scope.launch {
            if (!mal.remove(anime)) return@launch
            replace(anime.id) { it.copy(myListStatus = null) }
            changes++
        }
    }

    private fun replace(id: Int, change: (MalAnime) -> MalAnime) {
        results = results?.map { if (it.id == id) change(it) else it }
    }

    companion object {
        /** MyAnimeList searches need at least this many characters. */
        const val MIN_QUERY = 3
    }
}

@Composable
fun rememberMalSearchModel(container: AppContainer): MalSearchModel {
    val scope = rememberCoroutineScope()
    return remember { MalSearchModel(container.mal, { container.settings.value.mal != null }, scope) }
}

/** "Watching · 3 of 12 watched · 8/10", or that it isn't on the list. */
fun MalAnime.listSummary(): String {
    val list = myListStatus ?: return "Not on your list"
    val of = if (numEpisodes > 0) " of $numEpisodes" else ""
    return listOfNotNull(list.statusValue?.label, "${list.watchedEpisodes}$of watched", list.score.takeIf { it > 0 }?.let { "$it/10" }).joinToString(" · ")
}

/** "TV · 2023 · 28 episodes". */
fun MalAnime.details(): String =
    listOfNotNull(mediaType?.uppercase(), year?.toString(), numEpisodes.takeIf { it > 0 }?.let { "$it episodes" }).joinToString(" · ")
