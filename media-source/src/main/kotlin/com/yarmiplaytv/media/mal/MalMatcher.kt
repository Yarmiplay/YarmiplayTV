package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.EpisodeInfo

/** What [MalMatcher] reads from MyAnimeList; [MalClient] behind a cache in the app, a fake in tests. */
interface MalCatalog {
    suspend fun search(query: String): List<MalAnime>
    suspend fun anime(id: Int): MalAnime
    /** What the user is watching now, to tell which of two readings fits. */
    suspend fun watching(): List<Pair<MalAnime, MalListStatus>>
    /** What the user has completed: a franchise they know makes a weak title match trustworthy. */
    suspend fun completed(): List<Pair<MalAnime, MalListStatus>> = emptyList()
}

/**
 * A show matched by hand (or earlier): episode `E` of [seriesKey]'s season is episode `E - offset` of anime [malId].
 * It's a place in the franchise, so later episodes continue into its sequels and earlier ones go back to its prequels.
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
 * Finds the MyAnimeList entry and episode for a library episode. MyAnimeList has an entry per season, and often one
 * per part of a season ("Season 3 Part 2"), while libraries number episodes per season or from the start of the show
 * ("absolute"). The franchise's series entries are found in airing order by following prequels back to the first and
 * sequels forward (movies, OVAs, specials and short recaps are skipped). Parts are grouped with the season they
 * continue, so season `S` episode `E` is counted through the `S`th group's parts, and an absolute episode through
 * every entry. When the readings disagree, or a length is unknown, the user's list decides; only then is the match
 * unsure and the user picks.
 */
class MalMatcher(private val catalog: MalCatalog) {

    suspend fun match(info: EpisodeInfo, saved: MalMapping? = null): MalMatch? {
        val e = info.episode?.takeIf { it > 0 } ?: return null
        if (saved != null) fromMapping(saved, e)?.let { return it }
        info.malId?.let { id ->
            val anime = catalog.anime(id)
            if (anime.numEpisodes == 0 || e <= anime.numEpisodes) return MalMatch(anime, e, sure = true, listOf(anime))
            val chain = listOf(anime) + sequels(anime)
            countThrough(chain, e, chain.last().id)?.let { r -> return MalMatch(r.anime, r.episode, r.known, chain) }
        }

        val (results, scored) = search(info)
        val (best, score) = scored.firstOrNull() ?: return null
        val chain = franchise(best)
        val others = results.filter { r -> chain.none { it.id == r.id } }
        val candidates = chain + others

        val readings = readings(chain, info.season, e)
        val single = readings.singleOrNull()
        val pick = when {
            readings.isEmpty() -> return MalMatch(best, e, sure = false, candidates)
            single != null && single.known -> single
            else -> fromList(readings) ?: return readings.first().let { MalMatch(it.anime, it.episode, sure = false, candidates) }
        }
        // One answer from the counting (or the list's) and a fair title, or a franchise the user's list already has.
        val sure = score >= FAIR_TITLE || onList(chain)
        return MalMatch(pick.anime, pick.episode, sure, candidates)
    }

    private data class Reading(val anime: MalAnime, val episode: Int, val known: Boolean)

    /**
     * Season `S` episode `E`: counted through the `S`th group's parts, or, for libraries that number a later season from
     * the start of the show, counted through everything when that lands in the same group. Season 1 (or none):
     * counted through everything.
     */
    private fun readings(chain: List<MalAnime>, season: Int?, e: Int): List<Reading> {
        val last = chain.last().id
        val absolute = countThrough(chain, e, last)
        if (season == null || season <= 1) return listOfNotNull(absolute)
        val group = groups(chain).getOrNull(season - 1) ?: return emptyList()
        val bySeason = countThrough(group, e, last)
        val absoluteInSeason = absolute?.takeIf { r -> group.any { it.id == r.anime.id } }
        return listOfNotNull(bySeason, absoluteInSeason).distinctBy { it.anime.id to it.episode }
    }

    /** A saved match: its entry's place in the franchise, so earlier and later episodes map too. */
    private suspend fun fromMapping(saved: MalMapping, e: Int): MalMatch? {
        val start = catalog.anime(saved.malId)
        val local = e - saved.offset
        if (local >= 1) {
            val forward = listOf(start) + sequels(start)
            return countThrough(forward, local, forward.last().id)?.let { r -> MalMatch(r.anime, r.episode, r.known, forward) }
        }
        // Before the matched entry: count from the first entry, which needs every length before it.
        val chain = franchise(start)
        val index = chain.indexOfFirst { it.id == start.id }
        if (index < 0 || chain.take(index).any { it.numEpisodes == 0 }) return null
        val absolute = chain.take(index).sumOf { it.numEpisodes } + local
        return countThrough(chain, absolute, chain.last().id)?.let { r -> MalMatch(r.anime, r.episode, r.known, chain) }
    }

    /** The reading the user's list already points at: the entry they're watching, at the episode before this one. */
    private suspend fun fromList(readings: List<Reading>): Reading? {
        val watching = runCatching { catalog.watching() }.getOrDefault(emptyList())
        return readings.firstOrNull { r -> watching.any { (a, s) -> a.id == r.anime.id && s.watchedEpisodes == r.episode - 1 } }
    }

    /** Whether the user's list has any entry of the franchise [chain]. */
    private suspend fun onList(chain: List<MalAnime>): Boolean {
        val ids = chain.mapTo(HashSet()) { it.id }
        val listed = runCatching { catalog.watching() + catalog.completed() }.getOrDefault(emptyList())
        return listed.any { (a, _) -> a.id in ids }
    }

    /**
     * The search results for the show and how well each matches its name, best first. One search when the name
     * matches well; otherwise also its part before a comma or colon and its first words, pooled.
     */
    private suspend fun search(info: EpisodeInfo): Pair<List<MalAnime>, List<Pair<MalAnime, Double>>> {
        val pooled = LinkedHashMap<Int, MalAnime>()
        fun scored() = pooled.values.map { it to titleScore(info.seriesName, it, info.year) }.sortedByDescending { it.second }
        for ((i, query) in searchQueries(info.seriesName).withIndex()) {
            if (i > 0 && (scored().firstOrNull()?.second ?: 0.0) >= STRONG_TITLE) break
            runCatching { catalog.search(query) }.getOrDefault(emptyList()).forEach { pooled.putIfAbsent(it.id, it) }
        }
        return pooled.values.toList() to scored()
    }

    /**
     * Episode [absolute] counted through [chain]: the entry, its episode, and whether every length on the way was
     * known. Nothing can be counted past an entry of unknown length (still airing); it counts as known only when it
     * is the franchise's last entry ([lastId]), so a new season's episodes land on it.
     */
    private fun countThrough(chain: List<MalAnime>, absolute: Int, lastId: Int): Reading? {
        if (absolute <= 0) return null
        var left = absolute
        for (anime in chain) {
            if (anime.numEpisodes == 0) return Reading(anime, left, anime.id == lastId)
            if (left <= anime.numEpisodes) return Reading(anime, left, true)
            left -= anime.numEpisodes
        }
        return null
    }

    /** The franchise's series entries in airing order: prequels back to the first, then sequels forward. */
    private suspend fun franchise(anime: MalAnime): List<MalAnime> {
        var first = catalog.anime(anime.id)
        val seen = hashSetOf(first.id)
        while (seen.size <= MAX_WALK) {
            first = related(first, "prequel", seen) ?: break
            seen += first.id
        }
        val chain = listOf(first) + sequels(first)
        return chain.filter { it.isMainSeries }.ifEmpty { listOf(first) }
    }

    /** The sequels after [anime], in order (through movies, specials and recaps, which aren't included). */
    private suspend fun sequels(anime: MalAnime): List<MalAnime> {
        val out = ArrayList<MalAnime>()
        val seen = hashSetOf(anime.id)
        var current = catalog.anime(anime.id)
        while (out.size < MAX_WALK && seen.size <= MAX_WALK * 2) {
            current = related(current, "sequel", seen) ?: break
            seen += current.id
            if (current.isMainSeries) out += current
        }
        return out
    }

    /** The [relation] of [anime] not seen yet; with several, the series entry (a season rather than a movie). */
    private suspend fun related(anime: MalAnime, relation: String, seen: Set<Int>): MalAnime? {
        val nodes = anime.relatedAnime.filter { it.relationType == relation && it.node.id !in seen }
        if (nodes.size <= 1) return nodes.firstOrNull()?.let { catalog.anime(it.node.id) }
        val entries = nodes.map { catalog.anime(it.node.id) }
        return entries.firstOrNull { it.isMainSeries } ?: entries.first()
    }

    companion object {
        /** How far the franchise walk goes, so a long franchise can't cost many requests. */
        const val MAX_WALK = 15
        /** A title match good enough on its own. */
        const val STRONG_TITLE = 0.8
        /** A title match good enough when the episode counting leaves exactly one answer. */
        const val FAIR_TITLE = 0.6

        private val seasonSuffix = Regex(
            """(?i)[\s:,-]*(?:the\s+)?(?:season\s*\d+|\d+(?:st|nd|rd|th)\s+season|s\d+|part\s*(?:\d+|[ivx]+)|\d+(?:st|nd|rd|th)\s+cour|\(second half\))\s*$""",
        )
        private val continuation = Regex(
            """(?i)\bpart\s*(?:[2-9]|ii|iii|iv|v)\b|\b(?:2nd|second|3rd|third)\s+cour\b|\(second half\)|kanketsu[\s-]?hen|\bthe final chapters?\b""",
        )

        /** The show's name without a trailing "Season 2", "2nd Season" or "Part 2": the search finds the franchise. */
        fun searchTitle(name: String): String {
            var s = name.trim()
            while (true) {
                val next = seasonSuffix.replace(s, "").trim()
                if (next == s || next.isEmpty()) return s
                s = next
            }
        }

        /**
         * What to search for: the name (cut at a word boundary to MyAnimeList's 64 characters), its part before a
         * comma or colon, and its first four and three words, without repeats.
         */
        fun searchQueries(name: String): List<String> {
            val title = searchTitle(name)
            val words = title.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return listOfNotNull(
                MalClient.searchQuery(title),
                title.split(',', ':').first().trim().takeIf { it.length >= 3 },
                words.take(4).joinToString(" ").takeIf { words.size > 4 },
                words.take(3).joinToString(" ").takeIf { words.size > 3 },
            ).map(MalClient::searchQuery).filter { it.length >= 3 }.distinct()
        }

        /**
         * 0..1: how well [name] matches one of [anime]'s titles, season and part markers left out on both sides:
         * the same title, the name being the title's start (or its part before a comma or colon), or how many words
         * they share. Words a letter apart ("Tensai" and "Tensei") count as the same. Plus a little for the year.
         */
        fun titleScore(name: String, anime: MalAnime, year: Int? = null): Double {
            val want = words(searchTitle(name))
            if (want.isEmpty()) return 0.0
            val best = anime.titles.maxOfOrNull { title ->
                val bare = searchTitle(title)
                val have = words(bare)
                val head = words(bare.split(',', ':').first())
                when {
                    have.isEmpty() -> 0.0
                    same(want, have) -> 1.0
                    same(want, head) || startsWith(have, want) -> 0.9
                    else -> 2.0 * want.count { w -> have.any { similar(w, it) } } / (want.size + have.size)
                }
            } ?: 0.0
            val yearBonus = if (year != null && anime.year != null && kotlin.math.abs(anime.year!! - year) <= 1) 0.05 else 0.0
            val typeBonus = if (anime.isSeries) 0.02 else 0.0
            return (best + yearBonus + typeBonus).coerceAtMost(1.0)
        }

        /**
         * MyAnimeList's entries grouped into library seasons: an entry that continues the previous one ("Part 2",
         * "2nd Cour", "Kanketsu-hen", …) is part of its season.
         */
        fun groups(chain: List<MalAnime>): List<List<MalAnime>> {
            val out = ArrayList<MutableList<MalAnime>>()
            for (anime in chain) {
                if (out.isNotEmpty() && anime.titles.any { continuation.containsMatchIn(it) }) out.last() += anime else out += mutableListOf(anime)
            }
            return out
        }

        private fun same(a: List<String>, b: List<String>) = a.size == b.size && a.indices.all { similar(a[it], b[it]) }
        private fun startsWith(have: List<String>, want: List<String>) = have.size > want.size && same(have.take(want.size), want)

        /** The same word, or one letter apart in a word of 4 letters or more (two in 8 or more). */
        private fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            val len = minOf(a.length, b.length)
            val allowed = when {
                len >= 8 -> 2
                len >= 4 -> 1
                else -> 0
            }
            return allowed > 0 && kotlin.math.abs(a.length - b.length) <= allowed && distance(a, b) <= allowed
        }

        private fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val row = IntArray(b.length + 1)
                row[0] = i
                for (j in 1..b.length) {
                    row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                previous = row
            }
            return previous[b.length]
        }

        private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }
    }
}

/** A season of a series (TV or web), not a movie, OVA, special or a one- or two-episode web recap. */
val MalAnime.isMainSeries: Boolean get() = isSeries && !(mediaType == "ona" && numEpisodes in 1..2)
