package com.yarmiplaytv.media.anilist

import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.mal.MalMapping
import com.yarmiplaytv.media.mal.MalMatcher

/** Data source for [AniListMatcher]: [AniListClient] behind a cache in the app, a fake in tests. */
interface AniListCatalog {
    suspend fun search(query: String): List<AniListAnime>
    suspend fun anime(id: Int): AniListAnime
    suspend fun watching(): List<AniListAnime>
    suspend fun completed(): List<AniListAnime> = emptyList()
}

/**
 * Matches a library/file [EpisodeInfo] to the exact AniList season/part entry and episode number
 * by walking `PREQUEL`/`SEQUEL` relations and grouping split-cour parts just like [MalMatcher].
 */
class AniListMatcher(private val catalog: AniListCatalog) {

    suspend fun match(info: EpisodeInfo, saved: MalMapping? = null): AniListMatch? {
        val e = info.episode?.takeIf { it > 0 } ?: return null
        if (saved != null) fromMapping(saved, e)?.let { return it }

        val (results, scored) = search(info)
        val (best, score) = scored.firstOrNull() ?: return null
        val chain = franchise(best)
        val others = results.filter { r -> chain.none { it.id == r.id } }
        val candidates = chain + others

        val readings = readings(chain, info.season, e)
        val single = readings.singleOrNull()
        val pick = when {
            readings.isEmpty() -> return AniListMatch(best, e, sure = false, candidates)
            single != null && single.known -> single
            else -> fromList(readings) ?: return readings.first().let { AniListMatch(it.anime, it.episode, sure = false, candidates) }
        }
        val sure = score >= FAIR_TITLE || onList(chain)
        return AniListMatch(pick.anime, pick.episode, sure, candidates)
    }

    private data class Reading(val anime: AniListAnime, val episode: Int, val known: Boolean)

    private fun readings(chain: List<AniListAnime>, season: Int?, e: Int): List<Reading> {
        val last = chain.last().id
        val absolute = countThrough(chain, e, last)
        if (season == null || season <= 1) return listOfNotNull(absolute)
        val group = groups(chain).getOrNull(season - 1) ?: return emptyList()
        val bySeason = countThrough(group, e, last)
        val absoluteInSeason = absolute?.takeIf { r -> group.any { it.id == r.anime.id } }
        return listOfNotNull(bySeason, absoluteInSeason).distinctBy { it.anime.id to it.episode }
    }

    private suspend fun fromMapping(saved: MalMapping, e: Int): AniListMatch? {
        val start = catalog.anime(saved.malId)
        val local = e - saved.offset
        if (local >= 1) {
            val forward = listOf(start) + sequels(start)
            return countThrough(forward, local, forward.last().id)?.let { r -> AniListMatch(r.anime, r.episode, r.known, forward) }
        }
        val chain = franchise(start)
        val index = chain.indexOfFirst { it.id == start.id }
        if (index < 0 || chain.take(index).any { it.episodes == 0 }) return null
        val absolute = chain.take(index).sumOf { it.episodes } + local
        return countThrough(chain, absolute, chain.last().id)?.let { r -> AniListMatch(r.anime, r.episode, r.known, chain) }
    }

    private suspend fun fromList(readings: List<Reading>): Reading? {
        val watching = runCatching { catalog.watching() }.getOrDefault(emptyList())
        return readings.firstOrNull { r ->
            watching.any { a -> a.id == r.anime.id && (a.listEntry?.progress ?: -1) == r.episode - 1 }
        }
    }

    private suspend fun onList(chain: List<AniListAnime>): Boolean {
        val ids = chain.mapTo(HashSet()) { it.id }
        val listed = runCatching { catalog.watching() + catalog.completed() }.getOrDefault(emptyList())
        return listed.any { it.id in ids }
    }

    private suspend fun search(info: EpisodeInfo): Pair<List<AniListAnime>, List<Pair<AniListAnime, Double>>> {
        val pooled = LinkedHashMap<Int, AniListAnime>()
        fun scored() = pooled.values.map { it to titleScore(info.seriesName, it, info.year) }.sortedByDescending { it.second }
        for ((i, query) in MalMatcher.searchQueries(info.seriesName).withIndex()) {
            if (i > 0 && (scored().firstOrNull()?.second ?: 0.0) >= STRONG_TITLE) break
            runCatching { catalog.search(query) }.getOrDefault(emptyList()).forEach { pooled.putIfAbsent(it.id, it) }
        }
        return pooled.values.toList() to scored()
    }

    private fun countThrough(chain: List<AniListAnime>, absolute: Int, lastId: Int): Reading? {
        if (absolute <= 0) return null
        var left = absolute
        for (anime in chain) {
            if (anime.episodes == 0) return Reading(anime, left, anime.id == lastId)
            if (left <= anime.episodes) return Reading(anime, left, true)
            left -= anime.episodes
        }
        return null
    }

    private suspend fun franchise(anime: AniListAnime): List<AniListAnime> {
        var first = catalog.anime(anime.id)
        val seen = hashSetOf(first.id)
        while (seen.size <= MAX_WALK) {
            first = related(first, "PREQUEL", seen) ?: break
            seen += first.id
        }
        val chain = listOf(first) + sequels(first)
        return chain.filter { it.isMainSeries }.ifEmpty { listOf(first) }
    }

    private suspend fun sequels(anime: AniListAnime): List<AniListAnime> {
        val out = ArrayList<AniListAnime>()
        val seen = hashSetOf(anime.id)
        var current = anime
        while (seen.size <= MAX_WALK) {
            current = related(current, "SEQUEL", seen) ?: break
            seen += current.id
            if (current.isMainSeries) out += current
        }
        return out
    }

    private suspend fun related(anime: AniListAnime, type: String, seen: Set<Int>): AniListAnime? {
        val targets = anime.relations
            .filter { it.relationType.equals(type, ignoreCase = true) && it.media.id !in seen }
            .map { it.media }
        if (targets.isEmpty()) return null
        val loaded = targets.map { runCatching { catalog.anime(it.id) }.getOrDefault(it) }
        return loaded.firstOrNull { it.isMainSeries } ?: loaded.firstOrNull()
    }

    private val AniListAnime.isMainSeries: Boolean
        get() = isSeries && (episodes == 0 || episodes > 1)

    companion object {
        private const val MAX_WALK = 12
        private const val FAIR_TITLE = 0.7
        private const val STRONG_TITLE = 0.9

        private val PART_PATTERN = Regex("""(?i)\b(?:part|cour)\s*[2-9]\b""")

        /** Groups split-cour parts ("Season 3 Part 2") with the season they continue. */
        fun groups(chain: List<AniListAnime>): List<List<AniListAnime>> {
            val out = ArrayList<MutableList<AniListAnime>>()
            for (anime in chain) {
                val isContinuation = out.isNotEmpty() && anime.titles.any { PART_PATTERN.containsMatchIn(it) }
                if (isContinuation) out.last() += anime else out += mutableListOf(anime)
            }
            return out
        }

        fun titleScore(query: String, anime: AniListAnime, year: Int?): Double {
            val q = normalize(query)
            if (q.isEmpty()) return 0.0
            val qWords = q.split(' ').filter { it.isNotEmpty() }.toSet()
            var best = 0.0
            for (raw in anime.titles) {
                val t = normalize(raw)
                if (t.isEmpty()) continue
                val s = when {
                    t == q -> 1.0
                    t.startsWith("$q ") || q.startsWith("$t ") -> 0.88
                    else -> {
                        val tWords = t.split(' ').filter { it.isNotEmpty() }.toSet()
                        val common = qWords.intersect(tWords).size
                        if (common == 0) 0.0
                        else (2.0 * common / (qWords.size + tWords.size)) * 0.85
                    }
                }
                if (s > best) best = s
            }
            if (year != null && anime.seasonYear == year && best >= 0.5) best = (best + 0.08).coerceAtMost(1.0)
            if (anime.isSeries && best >= 0.5) best = (best + 0.04).coerceAtMost(1.0)
            return best
        }

        private fun normalize(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
