package com.yarmiplaytv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopPlaylistMetaTest {
    @Test
    fun durationFormatting() {
        assertNull(formatPlaylistDuration(null))
        assertNull(formatPlaylistDuration(0.0))
        assertNull(formatPlaylistDuration(Double.NaN))
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
        assertEquals("Plays from your media folders", playlistSourceHint(PlaylistSourceKind.LOCAL))
    }
}
