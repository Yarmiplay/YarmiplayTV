package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.EpisodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seasons and episodes worked out without asking: short names, typos, recaps, branches, airing seasons and parts. */
class MalMatcherSeasonsTest {
    private class Catalog(entries: List<MalAnime>, private val results: (String) -> List<Int>) : MalCatalog {
        val all = entries.associateBy { it.id }
        var watching: List<Pair<MalAnime, MalListStatus>> = emptyList()
        var completed: List<Pair<MalAnime, MalListStatus>> = emptyList()
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): List<MalAnime> {
            queries += query
            return results(query).map(all::getValue)
        }
        override suspend fun anime(id: Int): MalAnime = all.getValue(id)
        override suspend fun watching() = watching
        override suspend fun completed() = completed
    }

    private fun match(catalog: MalCatalog, name: String, season: Int?, episode: Int, saved: MalMapping? = null) =
        runBlocking { MalMatcher(catalog).match(EpisodeInfo(name, season, episode, seriesKey = name), saved) }

    // --- Tensei Kizoku: a long MyAnimeList title, 12 + 12 + a season still airing --------------------------------

    private val tk1 = anime(1, "Tensei Kizoku, Kantei Skill de Nariagaru", 12, sequels = listOf(2), en = "As a Reincarnated Aristocrat, I'll Use My Appraisal Skill to Rise in the World")
    private val tk2 = anime(2, "Tensei Kizoku, Kantei Skill de Nariagaru 2nd Season", 12, prequels = listOf(1), sequels = listOf(3))
    private val tk3 = anime(3, "Tensei Kizoku, Kantei Skill de Nariagaru 3rd Season", 0, prequels = listOf(2))
    private fun tensei() = Catalog(listOf(tk1, tk2, tk3)) { q -> if ("kizoku" in q.lowercase() || "tens" in q.lowercase()) listOf(2, 1, 3) else emptyList() }

    @Test
    fun `episode 27 of a short name is the third season's third episode, without asking`() {
        val m = match(tensei(), "Tensei Kizoku", 1, 27)!!
        assertEquals(3 to 3, m.anime.id to m.episode)
        assertTrue(m.sure)
        assertEquals(3 to 3, match(tensei(), "Tensei Kizoku", null, 27)!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `a typo in the name still matches`() {
        val m = match(tensei(), "Tensai Kizoku", null, 27)!!
        assertEquals(3 to 3, m.anime.id to m.episode)
        assertTrue(m.sure)
        assertTrue(MalMatcher.titleScore("Tensai Kizoku", tk1) >= MalMatcher.STRONG_TITLE)
    }

    @Test
    fun `a saved match maps the episodes before it too`() {
        // The user matched episode 27 to the third season's episode 3.
        val saved = MalMapping(3, offset = 24)
        assertEquals(1 to 5, match(tensei(), "Whatever", null, 5, saved)!!.let { it.anime.id to it.episode })
        assertEquals(2 to 3, match(tensei(), "Whatever", null, 15, saved)!!.let { it.anime.id to it.episode })
        assertEquals(3 to 4, match(tensei(), "Whatever", null, 28, saved)!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `when the name matches poorly, shorter searches find the show`() {
        val catalog = Catalog(listOf(tk1, tk2, tk3)) { q -> if (q == "Tensei Kizoku Kantei") listOf(1, 2, 3) else emptyList() }
        val m = match(catalog, "Tensei Kizoku Kantei Skill Something Long", null, 13)!!
        assertEquals(2 to 1, m.anime.id to m.episode)
        assertTrue(catalog.queries.toString(), "Tensei Kizoku Kantei" in catalog.queries)
    }

    // --- Recaps, movies and branches ---------------------------------------------------------------------------

    @Test
    fun `recaps and movies between seasons don't shift the count`() {
        val s1 = anime(10, "Blue Hour", 12, sequels = listOf(11))
        val recap = anime(11, "Blue Hour: Recap", 1, prequels = listOf(10), sequels = listOf(12), type = "ona")
        val movie = anime(12, "Blue Hour Movie", 1, prequels = listOf(11), sequels = listOf(13), type = "movie")
        val s2 = anime(13, "Blue Hour 2nd Season", 12, prequels = listOf(12))
        val catalog = Catalog(listOf(s1, recap, movie, s2)) { listOf(10, 13) }
        assertEquals(13 to 3, match(catalog, "Blue Hour", null, 15)!!.let { it.anime.id to it.episode })
        assertEquals(13 to 3, match(catalog, "Blue Hour", 2, 3)!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `with two sequels, the next season is followed rather than the movie`() {
        val s1 = anime(20, "Night Garden", 12, sequels = listOf(21, 22))
        val movie = anime(21, "Night Garden: The Movie", 1, prequels = listOf(20), type = "movie")
        val s2 = anime(22, "Night Garden Season 2", 12, prequels = listOf(20))
        val catalog = Catalog(listOf(s1, movie, s2)) { listOf(20) }
        val m = match(catalog, "Night Garden", null, 15)!!
        assertEquals(22 to 3, m.anime.id to m.episode)
        assertTrue(m.sure)
    }

    // --- Attack on Titan: seasons in parts ---------------------------------------------------------------------

    private val aot = listOf(
        anime(30, "Shingeki no Kyojin", 25, sequels = listOf(31), en = "Attack on Titan", related = listOf(40 to "side_story")),
        anime(31, "Shingeki no Kyojin Season 2", 12, prequels = listOf(30), sequels = listOf(32), en = "Attack on Titan Season 2"),
        anime(32, "Shingeki no Kyojin Season 3", 12, prequels = listOf(31), sequels = listOf(33), en = "Attack on Titan Season 3", related = listOf(41 to "side_story")),
        anime(33, "Shingeki no Kyojin Season 3 Part 2", 10, prequels = listOf(32), sequels = listOf(34), en = "Attack on Titan Season 3 Part 2"),
        anime(34, "Shingeki no Kyojin: The Final Season", 16, prequels = listOf(33), sequels = listOf(35), en = "Attack on Titan: Final Season"),
        anime(35, "Shingeki no Kyojin: The Final Season Part 2", 12, prequels = listOf(34), sequels = listOf(36), en = "Attack on Titan: Final Season Part 2"),
        anime(36, "Shingeki no Kyojin: The Final Season - Kanketsu-hen", 2, prequels = listOf(35), type = "special"),
        anime(40, "Shingeki no Kyojin OVA", 8, type = "ova"),
        anime(41, "Shingeki no Kyojin Movie 3", 1, type = "movie"),
    )
    private fun titan() = Catalog(aot) { listOf(33, 30, 34, 40) }

    @Test
    fun `a library season made of parts counts through them`() {
        val s3e15 = match(titan(), "Attack on Titan", 3, 15)!!
        assertEquals(33 to 3, s3e15.anime.id to s3e15.episode)
        assertTrue(s3e15.sure)
        val s4e20 = match(titan(), "Attack on Titan", 4, 20)!!
        assertEquals(35 to 4, s4e20.anime.id to s4e20.episode)
        assertTrue(s4e20.sure)
        assertEquals(32 to 5, match(titan(), "Attack on Titan", 3, 5)!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `absolute numbering counts through every part`() {
        // 25 + 12 + 12 + 10 = 59.
        val m = match(titan(), "Attack on Titan", null, 60)!!
        assertEquals(34 to 1, m.anime.id to m.episode)
        assertTrue(m.sure)
        assertEquals(listOf(listOf(30), listOf(31), listOf(32, 33), listOf(34, 35)), MalMatcher.groups(aot.filter { it.isMainSeries }).map { g -> g.map { it.id } })
    }

    // --- Unknown lengths and the user's list -----------------------------------------------------------------------

    private val g1 = anime(50, "Star Drift", 12, sequels = listOf(51))
    private val g2 = anime(51, "Star Drift 2nd Season", 0, prequels = listOf(50), sequels = listOf(52))
    private val g3 = anime(52, "Star Drift 3rd Season", 12, prequels = listOf(51))
    private fun drift() = Catalog(listOf(g1, g2, g3)) { listOf(50) }

    @Test
    fun `an unknown length that isn't the last season makes it ask`() {
        assertFalse(match(drift(), "Star Drift", null, 15)!!.sure)
    }

    @Test
    fun `the user's list settles what the lengths can't`() {
        val catalog = drift().apply { watching = listOf(g2 to MalListStatus("watching", watchedEpisodes = 2)) }
        val m = match(catalog, "Star Drift", null, 15)!!
        assertEquals(51 to 3, m.anime.id to m.episode)
        assertTrue(m.sure)
    }

    @Test
    fun `a franchise on the user's list is trusted even with a weak name`() {
        val catalog = Catalog(listOf(tk1, tk2, tk3)) { listOf(1) }
        assertFalse(match(catalog, "Appraisal Aristocrat Story", null, 3)!!.sure)
        catalog.completed = listOf(tk1 to MalListStatus("completed", watchedEpisodes = 12))
        assertTrue(match(catalog, "Appraisal Aristocrat Story", null, 3)!!.sure)
    }

    private fun anime(
        id: Int,
        title: String,
        episodes: Int,
        prequels: List<Int> = emptyList(),
        sequels: List<Int> = emptyList(),
        type: String = "tv",
        en: String? = null,
        related: List<Pair<Int, String>> = emptyList(),
    ) = MalAnime(
        id = id,
        title = title,
        mediaType = type,
        numEpisodes = episodes,
        alternativeTitles = en?.let { MalAlternativeTitles(en = it) },
        relatedAnime = prequels.map { MalRelated(MalAnime(it, "p"), "prequel") } +
            sequels.map { MalRelated(MalAnime(it, "s"), "sequel") } +
            related.map { (rid, kind) -> MalRelated(MalAnime(rid, "r"), kind) },
    )
}
