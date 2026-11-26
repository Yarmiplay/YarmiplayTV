package com.yarmiplaytv.media.anilist

import com.yarmiplaytv.media.EpisodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListMatcherTest {

    private class FakeCatalog(private val entries: Map<Int, AniListAnime>) : AniListCatalog {
        override suspend fun search(query: String): List<AniListAnime> {
            val q = query.lowercase()
            return entries.values.filter {
                it.title.lowercase().contains(q) || (it.englishTitle?.lowercase()?.contains(q) == true)
            }
        }
        override suspend fun anime(id: Int): AniListAnime = entries.getValue(id)
        override suspend fun watching(): List<AniListAnime> = emptyList()
    }

    @Test
    fun parsesImplicitGrantFragmentAndRedactsToString() {
        val client = AniListClient("12345")
        val url = "http://127.0.0.1:47822/anilist-callback#access_token=secret_tok_999&token_type=Bearer&expires_in=31536000"
        val tokens = client.parseFragment(url)
        assertNotNull(tokens)
        assertEquals("secret_tok_999", tokens!!.accessToken)
        assertTrue(!tokens.toString().contains("secret_tok_999"))
        assertNull(client.parseFragment("http://127.0.0.1:47822/anilist-callback#error=access_denied"))
    }

    @Test
    fun walksSequelsForSeasonTwoAndPreservesMalId() = runBlocking {
        val s2Stub = AniListAnime(
            id = 102,
            idMal = 25777,
            title = "Attack on Titan Season 2",
            format = "TV",
            episodes = 12,
            seasonYear = 2017,
        )
        val s1Stub = AniListAnime(
            id = 101,
            idMal = 5114,
            title = "Attack on Titan",
            format = "TV",
            episodes = 25,
            seasonYear = 2013,
        )
        val s1 = s1Stub.copy(relations = listOf(AniListRelation("SEQUEL", s2Stub)))
        val s2 = s2Stub.copy(relations = listOf(AniListRelation("PREQUEL", s1Stub)))
        val matcher = AniListMatcher(FakeCatalog(mapOf(101 to s1, 102 to s2)))

        val m1 = matcher.match(EpisodeInfo("Attack on Titan", season = 1, episode = 5, year = 2013, seriesKey = "k"))
        assertNotNull(m1)
        assertEquals(101, m1!!.anime.id)
        assertEquals(5114, m1.anime.idMal)
        assertEquals(5, m1.episode)
        assertTrue(m1.sure)

        val m2 = matcher.match(EpisodeInfo("Attack on Titan", season = 2, episode = 3, year = 2013, seriesKey = "k"))
        assertNotNull(m2)
        assertEquals(102, m2!!.anime.id)
        assertEquals(25777, m2.anime.idMal)
        assertEquals(3, m2.episode)
        assertTrue(m2.sure)
    }

    @Test
    fun scoreFormatFormatting() {
        assertEquals("8 / 10", AniListScoreFormat.POINT_10.format(8.0))
        assertEquals("8.5 / 10", AniListScoreFormat.POINT_10_DECIMAL.format(8.5))
        assertEquals("85 / 100", AniListScoreFormat.POINT_100.format(85.0))
        assertEquals("No score", AniListScoreFormat.POINT_10.format(0.0))
    }
}
