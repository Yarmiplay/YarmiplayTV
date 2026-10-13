package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalFile
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.local.SeekFrames
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.episodeFromFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** An episode file to compare the sound of: episode [number] of its season. */
data class EpisodeFile(
    val number: Int,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Seconds, when already known. */
    val duration: Double?,
    /** Streamed from a server, so reading it downloads it. */
    val remote: Boolean,
) {
    val key: String get() = FingerprintCache.keyOf(fileName, sizeBytes, url)
}

/** The playing episode's [number] and the [others] of its season, nearest first (the next before the one before). */
data class Siblings(val number: Int, val others: List<EpisodeFile>)

/** Finds the other episodes of the playing episode's season. */
fun interface SiblingFinder {
    suspend fun of(np: NowPlaying): Siblings?
}

/**
 * The playing episode's season: for a file on this device, the files of the same show and season (named like it,
 * the same folder first); for a server's episode, or when this device has no others, the season on the server.
 * Nothing for files only another viewer has.
 */
class EpisodeSiblings(
    private val local: LocalLibrary,
    private val servers: StateFlow<List<MediaSource>>,
) : SiblingFinder {
    override suspend fun of(np: NowPlaying): Siblings? {
        if (SeekFrames.canPreview(np.url)) fromFiles(np)?.let { return it }
        return fromServers(np)
    }

    private suspend fun fromFiles(np: NowPlaying): Siblings? {
        val me = np.episode ?: return null
        val number = me.episode ?: return null
        val known = (local.files.value + local.opened.value).distinctBy { it.uri }
        val mine = known.firstOrNull { it.uri == np.url }
        val pool = if (mine != null && mine.folderUri.isNotEmpty()) known else known + withContext(Dispatchers.IO) { local.neighbours(np.url) }
        val same = pool.filter { it.uri != np.url }.mapNotNull { f ->
            val info = episodeFromFile(f.name, f.directory) ?: return@mapNotNull null
            if (info.seriesKey != me.seriesKey || info.season != me.season) return@mapNotNull null
            info.episode?.takeIf { it != number }?.let { it to f }
        }
        // The same folder first: another folder may hold another release of the show.
        val near = mine?.let { m -> same.filter { (_, f) -> f.folderUri == m.folderUri && f.directory == m.directory } }?.takeIf { it.isNotEmpty() } ?: same
        val others = near.distinctBy { it.first }.map { (n, f) -> f.toEpisode(n) }
        return others.takeIf { it.isNotEmpty() }?.let { Siblings(number, nearestFirst(number, it)) }
    }

    private suspend fun fromServers(np: NowPlaying): Siblings? {
        for (copy in np.copies) {
            val source = servers.value.firstOrNull { it.key == copy.sourceKey } ?: continue
            val season = try {
                source.seasonEpisodes(copy.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "No season from ${source.displayName}: ${e.message}")
                continue
            }
            val number = season.firstOrNull { it.itemId == copy.itemId }?.episode?.episode ?: np.episode?.episode ?: continue
            val others = season.filter { it.itemId != copy.itemId }.mapNotNull { p ->
                val n = p.episode?.episode?.takeIf { it != number } ?: return@mapNotNull null
                EpisodeFile(n, p.url, p.fileName, p.sizeBytes, p.durationSeconds.takeIf { it > 0 }, remote = true)
            }.distinctBy { it.number }
            if (others.isNotEmpty()) return Siblings(number, nearestFirst(number, others))
        }
        return null
    }

    private fun LocalFile.toEpisode(number: Int) = EpisodeFile(number, uri, name, sizeBytes, duration = null, remote = false)

    companion object {
        private const val TAG = "EpisodeSiblings"

        /** By distance from [number]; of two as near, the later one. */
        fun nearestFirst(number: Int, others: List<EpisodeFile>): List<EpisodeFile> =
            others.sortedWith(compareBy<EpisodeFile> { abs(it.number - number) }.thenByDescending { it.number })
    }
}
