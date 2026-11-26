package com.yarmiplaytv.relay

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.PlaylistStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPreloaderTest {

    private val defaultSettings = AppSettings()
    private val remoteMedia = NowPlaying(
        title = "Ep 1",
        fileName = "Show.S01E01.mkv",
        sizeBytes = 500_000_000L,
        durationHint = 1440.0,
        url = "https://server.example/video.mkv",
    )

    @Test
    fun defaultsEnabledOnWifiAndDisabledOnMobile() {
        assertTrue(defaultSettings.preDownloadPlaylistWifi)
        assertFalse(defaultSettings.preDownloadPlaylistMobile)

        val healthy = PlaybackState(url = remoteMedia.url, fileLoaded = true, duration = 1440.0, position = 100.0, cacheSeconds = 30.0)
        assertEquals(
            PreloadGate.ALLOWED,
            PreloadPolicy.gate(defaultSettings, onUnmetered = true, remoteMedia, healthy, PlaylistStatus.Idle, relayCurrentIncomplete = false),
        )
        assertEquals(
            PreloadGate.DISABLED_BY_SETTING,
            PreloadPolicy.gate(defaultSettings, onUnmetered = false, remoteMedia, healthy, PlaylistStatus.Idle, relayCurrentIncomplete = false),
        )
    }

    @Test
    fun yieldsToCurrentlyWatchingWhenBufferingSeekingLoadingLowCacheOrIncompleteRelay() {
        val base = PlaybackState(url = remoteMedia.url, fileLoaded = true, duration = 1440.0, position = 100.0, cacheSeconds = 30.0)

        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base.copy(buffering = true), PlaylistStatus.Idle, false))
        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base.copy(seeking = true), PlaylistStatus.Idle, false))
        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base.copy(fileLoaded = false), PlaylistStatus.Idle, false))
        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base.copy(cacheSeconds = 5.0), PlaylistStatus.Idle, false))
        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base, PlaylistStatus.Loading("ep.mkv"), false))
        assertEquals(PreloadGate.YIELD_TO_PLAYBACK, PreloadPolicy.gate(defaultSettings, true, remoteMedia, base, PlaylistStatus.Idle, relayCurrentIncomplete = true))

        // Near EOF, low cache does NOT block preloading the next episode.
        assertEquals(
            PreloadGate.ALLOWED,
            PreloadPolicy.gate(defaultSettings, true, remoteMedia, base.copy(position = 1430.0, cacheSeconds = 2.0), PlaylistStatus.Idle, false),
        )
    }

    @Test
    fun upcomingIndicesPrioritizeNextEntryFirst() {
        assertEquals(listOf(2, 3, 4), PreloadPolicy.upcomingIndices(size = 5, currentIndex = 1, loopPlaylist = false))
        assertEquals(listOf(2, 3, 4, 0), PreloadPolicy.upcomingIndices(size = 5, currentIndex = 1, loopPlaylist = true))
        assertEquals(emptyList<Int>(), PreloadPolicy.upcomingIndices(size = 1, currentIndex = 0, loopPlaylist = true))
    }
}
