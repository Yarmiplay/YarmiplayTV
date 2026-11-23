package com.yarmiplaytv.ui.player

import com.yarmiplaytv.sync.PlaylistStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCardRemoteTest {
    @Test
    fun `a file that was not found takes the remote, including back`() {
        val status = PlaylistStatus.NotFound("Show.mkv", "Not found")
        assertTrue(statusCardTakesRemote(status, nothingLoaded = true, fileLoaded = false))
        assertTrue(backDismissesStatusCard(status))
    }

    @Test
    fun `a failed file and an untrusted link do too`() {
        assertTrue(statusCardTakesRemote(PlaylistStatus.Failed("a.mkv", "no"), nothingLoaded = true, fileLoaded = false))
        assertTrue(backDismissesStatusCard(PlaylistStatus.Failed("a.mkv", "no")))
        assertTrue(statusCardTakesRemote(PlaylistStatus.Untrusted("https://example.com/a.mkv", null), nothingLoaded = true, fileLoaded = false))
        assertTrue(backDismissesStatusCard(PlaylistStatus.Untrusted("https://example.com/a.mkv", null)))
    }

    @Test
    fun `nothing playing takes the remote but back leaves the player`() {
        assertTrue(statusCardTakesRemote(PlaylistStatus.Idle, nothingLoaded = true, fileLoaded = false))
        assertFalse(backDismissesStatusCard(PlaylistStatus.Idle))
    }

    @Test
    fun `the missing-file card's rows share a column`() {
        // No library: Search again, Connect Jellyfin, Connect Plex, Dismiss over Browse, Room, Playlist.
        assertEquals(4, missingFileButtonColumns(4, 3))
        assertEquals(0, missingFileVerticalTarget(0, 3))
        assertEquals(1, missingFileVerticalTarget(1, 3))
        assertEquals(2, missingFileVerticalTarget(2, 3))
        // Dismiss has no button under it, so Down lands on Playlist.
        assertEquals(2, missingFileVerticalTarget(3, 3))
        // Up from Playlist lands on Connect Plex, the button in its column.
        assertEquals(2, missingFileVerticalTarget(2, 4))
        // With a library both rows are Search again, Pick manually, Dismiss and Browse, Room, Playlist.
        assertEquals(3, missingFileButtonColumns(3, 3))
        assertEquals(2, missingFileVerticalTarget(2, 3))
    }

    @Test
    fun `a playing file keeps seek and the bar`() {
        assertFalse(statusCardTakesRemote(PlaylistStatus.Idle, nothingLoaded = false, fileLoaded = true))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Idle, nothingLoaded = true, fileLoaded = true))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Loading("a.mkv"), nothingLoaded = false, fileLoaded = false))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Resolving("a.mkv"), nothingLoaded = true, fileLoaded = false))
    }
}
