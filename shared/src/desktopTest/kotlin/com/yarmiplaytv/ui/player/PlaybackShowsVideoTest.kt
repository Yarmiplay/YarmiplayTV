package com.yarmiplaytv.ui.player

import com.yarmiplaytv.player.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackShowsVideoTest {
    @Test
    fun audioOnlyStaysOutOfTheVideoWindowEvenWhenTheSpectrumHasASize() {
        val music = PlaybackState(
            fileLoaded = true,
            url = "song.mp3",
            audioOnly = true,
            audioVisualizer = true,
            videoWidth = 960,
            videoHeight = 540,
            displayAspect = 16.0 / 9.0,
        )
        assertFalse(playbackShowsVideo(music))
        assertFalse(playbackInfo(music).contains("960"))
        assertFalse(playbackInfo(music).contains("SW"))
    }

    @Test
    fun aVideoFileIsUnchangedAndANamelessStreamWaitsForTheTracks() {
        val video = PlaybackState(fileLoaded = true, url = "movie.mkv", videoWidth = 1920, videoHeight = 1080)
        assertTrue(playbackShowsVideo(video))
        val beforeTracks = PlaybackState(fileLoaded = true, url = "http://radio.example/live")
        assertTrue(playbackShowsVideo(beforeTracks))
        assertFalse(playbackShowsVideo(beforeTracks.copy(audioOnly = true)))
        assertFalse(playbackShowsVideo(PlaybackState(fileLoaded = true, url = "song.flac")))
    }
}
