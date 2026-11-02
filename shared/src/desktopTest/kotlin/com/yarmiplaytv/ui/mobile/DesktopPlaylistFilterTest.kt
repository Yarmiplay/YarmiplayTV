package com.yarmiplaytv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopPlaylistFilterTest {
    @Test
    fun filterIsCaseInsensitiveSubstring() {
        val list = listOf("Show - 01.mkv", "Show - 02.mkv", "Other.mp4", "https://cdn/ep1.mkv")
        assertEquals(listOf(0, 1, 3), playlistFilterIndices(list, "mkv"))
        assertEquals(listOf(0, 1), playlistFilterIndices(list, "show"))
        assertEquals(listOf(3), playlistFilterIndices(list, "CDN"))
        assertEquals(list.indices.toList(), playlistFilterIndices(list, "  "))
    }

    @Test
    fun durationFormatting() {
        assertNull(formatPlaylistDuration(null))
        assertNull(formatPlaylistDuration(0.0))
        assertEquals("1:05", formatPlaylistDuration(65.0))
        assertEquals("1:02:03", formatPlaylistDuration(3723.0))
    }

    @Test
    fun sourceLabels() {
        assertEquals("Local", playlistSourceLabel(PlaylistSourceKind.LOCAL))
        assertEquals("Jellyfin", playlistSourceLabel(PlaylistSourceKind.JELLYFIN))
        assertEquals("Plex", playlistSourceLabel(PlaylistSourceKind.PLEX))
        assertEquals("URL", playlistSourceLabel(PlaylistSourceKind.URL))
        assertEquals("", playlistSourceLabel(PlaylistSourceKind.UNKNOWN))
    }
}
