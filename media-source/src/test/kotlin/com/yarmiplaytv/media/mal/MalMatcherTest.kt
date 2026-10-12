package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.EpisodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MalMatcherTest {
    /**
     * A franchise like many on MyAnimeList: four TV seasons (12, 12, 24, 16 episodes) with a movie and a special
     * among the sequels, and an unrelated show with a similar name.
     */
    private val s1 = anime(100, "Hero Academy", 12, prequel = null, sequel = 101, year = 2016)
    private val movie = anime(101, "Hero Academy: The Movie", 1, prequel = 100, sequel = 102, type = "movie")
    private val s2 = anime(102, "Hero Academy 2nd Season", 12, prequel = 101, sequel = 103)
    private val s3 = anime(103, "Hero Academy 3rd Season", 24, prequel = 102, sequel = 104)
    private val special = anime(104, "Hero Academy: Special", 2, prequel = 103, sequel = 105, type = "special")
    private val s4 = anime(105, "Hero Academy 4th Season", 16, prequel = 104, sequel = null)
    private val other = anime(900, "Heroic Academy Tales", 10, prequel = null, sequel = null)
    private val all = listOf(s1, movie, s2, s3, special, s4, other).associateBy { it.id }

    private val catalog = object : MalCatalog {
        var watching: List<Pair<MalAnime, MalListStatus>> = emptyList()
        val fetched = mutableListOf<Int>()
        override suspend fun search(query: String) = listOf(s3, other, s1, s2)
        override suspend fun anime(id: Int): MalAnime = all.getValue(id).also { fetched += id }
        override suspend fun watching() = watching
    }
    private val matcher = MalMatcher(catalog)

    private fun ep(season: Int?, episode: Int, name: String = "Hero Academy", malId: Int? = null) =
        EpisodeInfo(name, season, episode, seriesKey = name, malId = malId)

    private fun match(info: EpisodeInfo, saved: MalMapping? = null) = runBlocking { matcher.match(info, saved) }

    @Test
    fun `season and episode pick that season's entry, skipping movies and specials`() {
        val m = match(ep(2, 5))!!
        assertEquals(102, m.anime.id)
        assertEquals(5, m.episode)
        assertTrue(m.sure)
        assertEquals(listOf(100, 102, 103, 105), m.candidates.take(4).map { it.id })
    }

    @Test
    fun `an absolute episode is counted through the seasons`() {
        // 12 + 12 + 24 = 48, so episode 50 is the 4th season's 2nd episode.
        val m = match(ep(1, 50))!!
        assertEquals(105, m.anime.id)
        assertEquals(2, m.episode)
        assertTrue(m.sure)
        assertEquals(103 to 4, match(ep(null, 28))!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `a later season numbered from the start of the show lands in that season`() {
        // Season 4, numbered absolutely: episode 50 is still the 4th season's 2nd.
        val m = match(ep(4, 50))!!
        assertEquals(105 to 2, m.anime.id to m.episode)
        assertTrue(m.sure)
    }

    @Test
    fun `an episode beyond the whole franchise is unsure`() {
        val m = match(ep(1, 200))!!
        assertFalse(m.sure)
        assertTrue(m.candidates.any { it.id == 900 })
    }

    @Test
    fun `a saved mapping is used first, and continues into the sequels`() {
        val m = match(ep(1, 15, name = "Totally different name"), MalMapping(102, offset = 0))!!
        assertEquals(103 to 3, m.anime.id to m.episode)
        assertTrue(m.sure)
        assertEquals(102 to 4, match(ep(1, 6), MalMapping(102, offset = 2))!!.let { it.anime.id to it.episode })
    }

    @Test
    fun `a MyAnimeList id from the media server is used directly`() {
        val m = match(ep(3, 7, malId = 103))!!
        assertEquals(103 to 7, m.anime.id to m.episode)
        assertTrue(m.sure)
    }

    @Test
    fun `a weak title match is never sure`() {
        val m = match(ep(1, 3, name = "Something Else Entirely"))!!
        assertFalse(m.sure)
    }

    @Test
    fun `no episode number means no match`() {
        assertNull(match(EpisodeInfo("Hero Academy", 1, null, seriesKey = "x")))
    }

    @Test
    fun `titles are compared without the season suffix`() {
        assertEquals("Hero Academy", MalMatcher.searchTitle("Hero Academy Season 2"))
        assertEquals("Hero Academy", MalMatcher.searchTitle("Hero Academy 2nd Season"))
        assertTrue(MalMatcher.titleScore("Hero Academy", s1) >= MalMatcher.STRONG_TITLE)
        assertTrue(MalMatcher.titleScore("Hero Academy", other) < MalMatcher.STRONG_TITLE)
    }

    private fun anime(id: Int, title: String, episodes: Int, prequel: Int?, sequel: Int?, type: String = "tv", year: Int? = null) = MalAnime(
        id = id,
        title = title,
        mediaType = type,
        numEpisodes = episodes,
        startDate = year?.let { "$it-04-01" },
        relatedAnime = listOfNotNull(
            prequel?.let { MalRelated(MalAnime(it, "p"), "prequel") },
            sequel?.let { MalRelated(MalAnime(it, "s"), "sequel") },
        ),
    )
}
