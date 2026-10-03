package com.yarmiplaytv.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeMediaSourceTest {
    private class FakeSource(
        override val key: String,
        private val items: List<MediaItem>,
        private val failing: Boolean = false,
    ) : MediaSource {
        override val kind = key.substringBefore(':')
        override val displayName = key.replaceFirstChar { it.uppercase() }
        val childrenOf = mutableListOf<String>()

        private fun check() { if (failing) throw MediaSourceException("$key is down") }
        override suspend fun libraries() = items.filter { it.type == MediaItemType.LIBRARY }.also { check() }
        override suspend fun children(parent: MediaItem): List<MediaItem> { childrenOf += parent.id; return emptyList() }
        override suspend fun search(query: String, limit: Int) = items.filter { it.isPlayable }.also { check() }
        override suspend fun recent(limit: Int) = items.filter { it.isPlayable }.also { check() }
        override suspend fun playable(item: MediaItem) = PlayableMedia(item.id, "http://$key/${item.id}", "${item.id}.mkv", 1, 1.0, item.name, key)
        override suspend fun resolveByFilename(fileName: String): ResolveResult = ResolveResult.NotFound(fileName, "no")
        override suspend fun findExact(fileName: String): ResolveResult.Found? = null
        override fun imageUrl(item: MediaItem, maxWidth: Int) = "http://$key/img/${item.id}"
    }

    private fun lib(key: String, id: String) = MediaItem(id, "$key lib", MediaItemType.LIBRARY, true, sourceKey = key)
    private fun movie(key: String, id: String) = MediaItem(id, "$key $id", MediaItemType.MOVIE, false, sourceKey = key)

    private val jellyfin = FakeSource("jellyfin", listOf(lib("jellyfin", "1"), movie("jellyfin", "a"), movie("jellyfin", "b")))
    private val plex = FakeSource("plex", listOf(lib("plex", "1"), movie("plex", "a")))
    private val composite = CompositeMediaSource(listOf(jellyfin, plex))

    @Test
    fun librariesFromBothServersStayDistinct() = runBlocking {
        val libs = composite.libraries()
        assertEquals(listOf("jellyfin/1", "plex/1"), libs.map { it.uniqueKey })
        assertEquals("Plex", composite.sourceOf(libs[1])?.displayName)
    }

    @Test
    fun recentAndSearchInterleaveServers() = runBlocking {
        assertEquals(listOf("jellyfin/a", "plex/a", "jellyfin/b"), composite.recent(10).map { it.uniqueKey })
        assertEquals(listOf("jellyfin/a", "plex/a"), composite.search("x", 2).map { it.uniqueKey })
    }

    @Test
    fun theSameFileOnBothServersIsListedOnce() = runBlocking {
        fun withFile(m: MediaItem, file: String) = m.copy(path = "/media/$file")
        val shared = CompositeMediaSource(listOf(
            FakeSource("jellyfin", listOf(withFile(movie("jellyfin", "a"), "Paper Boats.mkv"), withFile(movie("jellyfin", "b"), "Other.mkv"))),
            FakeSource("plex", listOf(withFile(movie("plex", "x"), "paper boats.MKV"), movie("plex", "y"))),
        ))
        assertEquals(listOf("jellyfin/a", "jellyfin/b", "plex/y"), shared.recent(10).map { it.uniqueKey })
    }

    @Test
    fun routesItemCallsToTheirOwnServer() = runBlocking {
        val plexMovie = movie("plex", "a")
        assertEquals("http://plex/a", composite.playable(plexMovie).url)
        assertEquals("http://plex/img/a", composite.imageUrl(plexMovie))
        composite.children(lib("plex", "1"))
        assertEquals(listOf("1"), plex.childrenOf)
        assertTrue(jellyfin.childrenOf.isEmpty())
        assertNull(composite.imageUrl(movie("gone", "x")))
    }

    @Test
    fun twoServersOfOneKindKeepTheirItemsApart() = runBlocking {
        val den = FakeSource("plex:den", listOf(lib("plex:den", "1"), movie("plex:den", "7")))
        val office = FakeSource("plex:office", listOf(lib("plex:office", "1"), movie("plex:office", "7")))
        val both = CompositeMediaSource(listOf(den, office))

        val libs = both.libraries()
        assertEquals(listOf("plex:den/1", "plex:office/1"), libs.map { it.uniqueKey })
        assertEquals("http://plex:office/7", both.playable(movie("plex:office", "7")).url)
        assertEquals("http://plex:den/img/7", both.imageUrl(movie("plex:den", "7")))
        both.children(libs[1])
        assertEquals(listOf("1"), office.childrenOf)
        assertTrue(den.childrenOf.isEmpty())
    }

    @Test
    fun skipsAFailingServer() = runBlocking {
        val down = CompositeMediaSource(listOf(FakeSource("jellyfin", listOf(lib("jellyfin", "1")), failing = true), plex))
        assertEquals(listOf("plex/1"), down.libraries().map { it.uniqueKey })
        assertEquals(listOf("plex/a"), down.recent(10).map { it.uniqueKey })
    }

    @Test(expected = MediaSourceException::class)
    fun failsWhenEveryServerFails() {
        runBlocking { CompositeMediaSource(listOf(FakeSource("plex", emptyList(), failing = true))).libraries() }
    }
}
