package com.yarmiplaytv.ui.player

import com.yarmiplaytv.sync.PlaylistStatus
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
    fun `a playing file keeps seek and the bar`() {
        assertFalse(statusCardTakesRemote(PlaylistStatus.Idle, nothingLoaded = false, fileLoaded = true))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Idle, nothingLoaded = true, fileLoaded = true))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Loading("a.mkv"), nothingLoaded = false, fileLoaded = false))
        assertFalse(statusCardTakesRemote(PlaylistStatus.Resolving("a.mkv"), nothingLoaded = true, fileLoaded = false))
    }
}
