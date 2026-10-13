package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.sync.episodeFromFile
import com.yarmiplaytv.ui.shared.malInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** An episode of a MyAnimeList entry that's in the library: on a media server ([item]) or a file here ([localUri]). */
data class AvailableEpisode(
    /** The episode's number within the MyAnimeList entry (not the file's own numbering). */
    val number: Int,
    val title: String,
    val item: MediaItem? = null,
    val localUri: String? = null,
)

/**
 * Which episodes of a MyAnimeList entry the library has, for playing them from the MyAnimeList screen: the media
 * servers and this device's files are searched by the entry's titles, and each episode found counts only when
 * MyAnimeList matching (the one that keeps the list up to date) surely puts it in that entry. Answers are kept
 * for [ttlMillis].
 */
class MalEpisodes(
    private val mal: MalService,
    private val mediaSource: StateFlow<MediaSource?>,
    private val localFiles: StateFlow<List<LocalFile>>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60_000L,
) {
    private val known = ConcurrentHashMap<Int, Pair<Long, List<AvailableEpisode>>>()
    /** A long list asks for many entries at once: a couple at a time spares the servers and MyAnimeList. */
    private val gate = Semaphore(2)

    /** [anime]'s episodes in the library, by number; one per number. */
    suspend fun of(anime: MalAnime, fresh: Boolean = false): List<AvailableEpisode> = gate.withPermit {
        if (!fresh) known[anime.id]?.takeIf { now() - it.first < ttlMillis }?.let { return@withPermit it.second }
        val titles = listOfNotNull(anime.title, anime.alternativeTitles?.en) + anime.alternativeTitles?.synonyms.orEmpty()
        val names = titles.map(::simple).filter { it.length >= 2 }.distinct()
        val found = mutableListOf<AvailableEpisode>()
        for (item in serverEpisodes(titles.distinct().take(MAX_SEARCHES))) {
            val info = item.malInfo() ?: continue
            val match = identify(info) ?: continue
            if (match.anime.id == anime.id) found += AvailableEpisode(match.episode, item.displayTitle, item = item)
        }
        for (file in localFiles.value) {
            val info = episodeFromFile(file.name, file.directory) ?: continue
            if (names.none { name -> simple(info.seriesName).let { it.contains(name) || name.contains(it) } }) continue
            val match = identify(info) ?: continue
            if (match.anime.id == anime.id) found += AvailableEpisode(match.episode, file.name, localUri = file.uri)
        }
        val episodes = found.groupBy { it.number }.map { (_, same) -> same.first() }.sortedBy { it.number }
        known[anime.id] = now() to episodes
        episodes
    }

    private suspend fun identify(info: EpisodeInfo) = try {
        mal.identify(info)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Episodes of the shows the servers find for [titles]: a show's seasons are opened down to their episodes. */
    private suspend fun serverEpisodes(titles: List<String>): List<MediaItem> {
        val source = mediaSource.value ?: return emptyList()
        val shows = linkedMapOf<String, MediaItem>()
        val episodes = linkedMapOf<String, MediaItem>()
        for (title in titles) {
            val results = try {
                source.search(title, limit = 20)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Couldn't search for $title: ${e.message}")
                continue
            }
            for (r in results) when (r.type) {
                MediaItemType.SERIES, MediaItemType.SEASON -> shows.putIfAbsent(r.uniqueKey, r)
                MediaItemType.EPISODE -> episodes.putIfAbsent(r.uniqueKey, r)
                else -> Unit
            }
        }
        for (show in shows.values.take(MAX_SHOWS)) {
            for (child in children(source, show)) {
                if (child.type == MediaItemType.EPISODE) episodes.putIfAbsent(child.uniqueKey, child)
                else if (child.type == MediaItemType.SEASON) children(source, child).filter { it.type == MediaItemType.EPISODE }.forEach { episodes.putIfAbsent(it.uniqueKey, it) }
            }
        }
        return episodes.values.toList()
    }

    private suspend fun children(source: MediaSource, parent: MediaItem): List<MediaItem> = try {
        source.children(parent)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** A title with only its letters and digits, for "is this file that show" before asking MyAnimeList. */
    private fun simple(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val TAG = "MalEpisodes"
        const val MAX_SEARCHES = 3
        const val MAX_SHOWS = 4
    }
}
