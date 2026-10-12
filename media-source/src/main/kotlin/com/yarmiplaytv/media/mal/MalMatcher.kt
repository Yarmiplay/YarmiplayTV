package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.EpisodeInfo

/** What [MalMatcher] reads from MyAnimeList; [MalClient] behind a cache in the app, a fake in tests. */
interface MalCatalog {
    suspend fun search(query: String): List<MalAnime>
    suspend fun anime(id: Int): MalAnime
    /** What the user is watching now, to tell which of two readings fits. */
    suspend fun watching(): List<Pair<MalAnime, MalListStatus>>
}

/**
 * A show matched by hand (or earlier): episode `E` of [seriesKey]'s season is episode `E - offset` of anime [malId],
 * continuing into its sequels when it runs past its last episode.
 */
@kotlinx.serialization.Serializable
data class MalMapping(val malId: Int, val offset: Int = 0)

/** Which anime entry and which of its episodes something is. [sure] matches are applied without asking. */
data class MalMatch(
    val anime: MalAnime,
    val episode: Int,
    val sure: Boolean,
    /** Other entries it might be, for the picker: the franchise's seasons first, then other search results. */
    val candidates: List<MalAnime> = emptyList(),
)

/**
 * Finds the MyAnimeList entry and episode for a library episode. MyAnimeList has an entry per season (and per cour),
 * while libraries number episodes per season or from the start of the show ("absolute"). The franchise's series
 * entries are found by following prequels back to the first and sequels forward (movies, OVAs and specials are
 * skipped); then season `S` episode `E` is the `S`th entry's episode `E`, and an absolute episode is counted
 * through the entries' lengths. When both readings fit, or a length is unknown, the user's list decides, or the
 * match is unsure and the user picks.
 */
class MalMatcher(private val catalog: MalCatalog) {

    suspend fun match(info: EpisodeInfo, saved: MalMapping? = null): MalMatch? {
        val e = info.episode?.takeIf { it > 0 } ?: return null
        if (saved != null) {
            val start = catalog.anime(saved.malId)
            val chain = listOf(start) + sequels(start)
            return countThrough(chain, e - saved.offset)?.let { (anime, ep, known) -> MalMatch(anime, ep, known, chain) }
        }
        info.malId?.let { id ->
            val anime = catalog.anime(id)
            if (anime.numEpisodes == 0 || e <= anime.numEpisodes) return MalMatch(anime, e, sure = true, listOf(anime))
            val chain = listOf(anime) + sequels(anime)
            countThrough(chain, e)?.let { (a, ep, known) -> return MalMatch(a, ep, known, chain) }
        }

        val results = catalog.search(searchTitle(info.seriesName))
        val scored = results.map { it to titleScore(info.seriesName, it, info.year) }.sortedByDescending { it.second }
        val (best, score) = scored.firstOrNull() ?: return null
        val chain = franchise(best)
        val others = results.filter { r -> chain.none { it.id == r.id } }
        val candidates = chain + others
        val strong = score >= STRONG_TITLE

        val season = info.season
        val bySeason = season?.takeIf { it >= 2 }?.let { s -> chain.getOrNull(s - 1)?.takeIf { it.fits(e) }?.let { Reading(it, e, true) } }
        val absolute = countThrough(chain, e)?.let { (a, ep, known) -> Reading(a, ep, known) }
            ?.takeIf { season == null || season <= 1 || it.anime.id == chain.getOrNull(season - 1)?.id }
        val readings = listOfNotNull(bySeason, absolute).distinctBy { it.anime.id to it.episode }

        val pick = when {
            readings.isEmpty() -> return MalMatch(best, e, sure = false, candidates)
            readings.size == 1 && readings[0].known -> readings[0]
            else -> fromList(readings) ?: return readings.first().let { MalMatch(it.anime, it.episode, sure = false, candidates) }
        }
        return MalMatch(pick.anime, pick.episode, sure = strong, candidates)
    }

    private data class Reading(val anime: MalAnime, val episode: Int, val known: Boolean)

    /** The reading the user's list already points at: the entry they're watching, at the episode before this one. */
    private suspend fun fromList(readings: List<Reading>): Reading? {
        val watching = runCatching { catalog.watching() }.getOrDefault(emptyList())
        return readings.firstOrNull { r -> watching.any { (a, s) -> a.id == r.anime.id && s.watchedEpisodes == r.episode - 1 } }
    }

    /**
     * Episode [absolute] counted through [chain]: the entry and its episode, and whether every length on the way was
     * known. An entry of unknown length (still airing) can only be the last one counted.
     */
    private fun countThrough(chain: List<MalAnime>, absolute: Int): Triple<MalAnime, Int, Boolean>? {
        if (absolute <= 0) return null
        var left = absolute
        for (anime in chain) {
            // Unknown length: nothing can be counted past it. Sure only for a show that is just this one entry.
            if (anime.numEpisodes == 0) return Triple(anime, left, chain.size == 1)
            if (left <= anime.numEpisodes) return Triple(anime, left, true)
            left -= anime.numEpisodes
        }
        return null
    }

    private fun MalAnime.fits(episode: Int) = numEpisodes == 0 || episode <= numEpisodes

    /** The franchise's series entries in order: prequels back to the first, then sequels forward. */
    private suspend fun franchise(anime: MalAnime): List<MalAnime> {
        var first = catalog.anime(anime.id)
        var steps = 0
        while (steps++ < MAX_WALK) {
            val prequel = first.relatedAnime.firstOrNull { it.relationType == "prequel" } ?: break
            first = catalog.anime(prequel.node.id)
        }
        val chain = listOf(first) + sequels(first)
        return chain.filter { it.isSeries }.ifEmpty { listOf(first) }
    }

    /** The sequels after [anime], in order (through movies and specials, which the caller can leave out). */
    private suspend fun sequels(anime: MalAnime): List<MalAnime> {
        val out = ArrayList<MalAnime>()
        val seen = hashSetOf(anime.id)
        var current = catalog.anime(anime.id)
        while (out.size < MAX_WALK) {
            val next = current.relatedAnime.firstOrNull { it.relationType == "sequel" && it.node.id !in seen } ?: break
            current = catalog.anime(next.node.id)
            seen += current.id
            if (current.isSeries) out += current
        }
        return out
    }

    companion object {
        /** How far the franchise walk goes, so a long franchise can't cost many requests. */
        const val MAX_WALK = 15
        const val STRONG_TITLE = 0.8

        private val seasonSuffix = Regex("""(?i)\s*(?:season\s*\d+|\d+(?:st|nd|rd|th)\s+season|s\d+|part\s*\d+)\s*$""")

        /** The show's name without a trailing "Season 2" or "2nd Season": the search finds the franchise. */
        fun searchTitle(name: String): String = seasonSuffix.replace(name, "").trim().ifEmpty { name.trim() }

        /** 0..1: how well [name] matches one of [anime]'s titles (word overlap), plus a little for the year. */
        fun titleScore(name: String, anime: MalAnime, year: Int? = null): Double {
            val want = words(searchTitle(name))
            if (want.isEmpty()) return 0.0
            val best = anime.titles.maxOfOrNull { t ->
                val have = words(t)
                if (have.isEmpty()) 0.0 else {
                    val common = want.intersect(have).size.toDouble()
                    val exact = if (normalize(t) == normalize(searchTitle(name))) 0.2 else 0.0
                    (2 * common / (want.size + have.size)) + exact
                }
            } ?: 0.0
            val yearBonus = if (year != null && anime.year != null && kotlin.math.abs(anime.year!! - year) <= 1) 0.05 else 0.0
            val typeBonus = if (anime.isSeries) 0.02 else 0.0
            return (best + yearBonus + typeBonus).coerceAtMost(1.0)
        }

        private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }.toSet()
    }
}
