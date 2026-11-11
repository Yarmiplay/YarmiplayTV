package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** time-pos used to be copied into PlaybackState on every video frame. Steady playback is now about 4 Hz. */
class PlaybackPublishesTest {
    @Test
    fun realtimePlaybackPublishesAboutFourTimesASecond() {
        val publishes = PlaybackPublishes()
        var now = 0L
        var position = 0.0
        var writes = 0
        val frame = 1_000_000_000L / 60
        repeat(300) {
            if (publishes.position(now, position)) writes++
            now += frame
            position += 1.0 / 60.0
        }
        assertTrue("published $writes times in 5s", writes in 15..25)
        assertTrue(writes < 40)
    }

    @Test
    fun theFirstSampleAndASeekPublishImmediately() {
        val publishes = PlaybackPublishes()
        assertTrue(publishes.position(0L, 0.0))
        assertFalse(publishes.position(10_000_000L, 0.01))
        assertTrue(publishes.position(20_000_000L, 2.0))
    }

    @Test
    fun aForcedPositionDoesNotPublishAgainOnTheNextTick() {
        val publishes = PlaybackPublishes()
        publishes.notePosition(0L, 40.0)
        assertFalse(publishes.position(50_000_000L, 40.1))
        assertTrue(publishes.position(300_000_000L, 40.4))
    }

    @Test
    fun cacheAndAvSyncAreCoarserThanTheFrameClock() {
        val publishes = PlaybackPublishes()
        assertTrue(publishes.cache(1.0, fine = false))
        assertFalse(publishes.cache(1.2, fine = false))
        assertTrue(publishes.cache(1.6, fine = false))
        assertTrue(publishes.avSync(0L))
        assertFalse(publishes.avSync(100_000_000L))
        assertTrue(publishes.avSync(500_000_000L))
    }
}
