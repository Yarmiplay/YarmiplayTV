package com.syncplaytv.syncplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private val clock = FakeClock()
    private val player = FakePlayer(clock)
    private val host = RecordingHost()
    private val engine = SyncEngine(player, clock, SyncSettings(), host)

    /** Server says the room is playing at [position]; engine polls the player afterwards like the real client. */
    private fun serverState(position: Double, paused: Boolean, doSeek: Boolean = false, setBy: String? = "alice") {
        engine.updateGlobalState(position, paused, doSeek, setBy, messageAge = 0.0)
        engine.poll()
    }

    private fun step(seconds: Double) {
        repeat((seconds * 10).toInt()) {
            clock.advance(0.1)
            engine.poll()
        }
    }

    @Test
    fun `first state moves the player to the room position and play state`() {
        serverState(120.0, paused = false)
        assertEquals(120.0, player.position, 0.01)
        assertFalse(player.isPaused)
    }

    @Test
    fun `remote pause pauses the player without echoing a state change`() {
        serverState(10.0, paused = false)
        step(2.0)
        host.sent.clear()
        serverState(12.0, paused = true, setBy = "alice")
        step(0.5)
        assertTrue(player.isPaused)
        assertTrue("no pause should be echoed back: ${host.sent}", host.sent.none { it.stateChange })
        assertTrue(host.notifications.any { it.startsWith("alice paused") })
    }

    @Test
    fun `remote seek jumps the player`() {
        serverState(10.0, paused = false)
        step(1.0)
        serverState(600.0, paused = false, doSeek = true, setBy = "alice")
        assertEquals(600.0, player.position, 0.2)
        assertTrue(host.notifications.toString(), "alice jumped from 00:11 to 10:00" in host.notifications)
        step(1.0)
        assertTrue(host.sent.none { it.doSeek })
    }

    @Test
    fun `a remote seek to where we already are is not announced`() {
        serverState(0.0, paused = true)
        step(1.0)
        serverState(0.0, paused = true, doSeek = true, setBy = "alice")
        assertTrue(host.notifications.toString(), host.notifications.none { "jumped" in it })
    }

    @Test
    fun `local seek is reported with doSeek`() {
        serverState(10.0, paused = false)
        step(1.0)
        player.seek(300.0)
        step(0.2)
        val seek = host.sent.last()
        assertTrue(seek.doSeek)
        assertTrue(seek.stateChange)
        assertEquals(300.0, seek.position!!, 0.5)
    }

    @Test
    fun `being more than the rewind threshold ahead rewinds to the room`() {
        serverState(100.0, paused = false)
        step(1.0)
        // Someone else in the room is 5 seconds behind us.
        val behind = player.position - 5.0
        engine.updateGlobalState(behind, false, false, "alice", 0.0)
        assertEquals(behind, player.position, 0.2)
        assertTrue(host.notifications.any { it.startsWith("Rewinded") })
    }

    @Test
    fun `small drift slows playback and recovers`() {
        serverState(100.0, paused = false)
        step(1.0)
        engine.updateGlobalState(player.position - 2.0, false, false, "alice", 0.0)
        assertEquals(Constants.SLOWDOWN_RATE, player.speed, 0.0001)
        engine.updateGlobalState(player.position, false, false, "alice", 0.0)
        assertEquals(1.0, player.speed, 0.0001)
    }

    @Test
    fun `local pause marks not ready and is sent to the room`() {
        host.isReady = true
        serverState(50.0, paused = false)
        step(1.0)
        host.sent.clear()
        player.setPaused(true)
        step(0.2)
        assertEquals(false to false, host.readyChanges.last())
        assertTrue(host.sent.any { it.paused == true && it.stateChange })
    }

    @Test
    fun `unpausing while others are not ready only marks us ready`() {
        host.othersReady = false
        host.isReady = false
        serverState(50.0, paused = true)
        step(0.5)
        player.setPaused(false)
        step(0.2)
        assertTrue(player.isPaused)
        assertEquals(true to true, host.readyChanges.last())
        assertTrue(host.sent.none { it.paused == false && it.stateChange })
    }

    @Test
    fun `unpausing when everyone else is ready unpauses the room`() {
        host.othersReady = true
        serverState(50.0, paused = true)
        step(0.5)
        player.setPaused(false)
        step(0.2)
        assertFalse(player.isPaused)
        assertTrue(host.sent.any { it.paused == false && it.stateChange })
    }

    @Test
    fun `pausing at the end of the file asks to advance the playlist`() {
        player.duration = 1440.0
        host.currentFileDuration = 1440.0
        serverState(1437.0, paused = false)
        step(1.0)
        player.setPaused(true)
        step(0.2)
        assertEquals(1, host.advanceChecks)
    }

    @Test
    fun `reset file load reports position zero and ignores stale room position`() {
        serverState(900.0, paused = false)
        step(1.0)
        engine.onFileLoaded(resetPosition = true)
        assertEquals(0.0, player.position, 0.01)
        // Old room position arrives before others have switched: must not jump back to 900.
        engine.updateGlobalState(901.0, false, false, "alice", 0.0)
        assertTrue(player.position < 5.0)
    }

    @Test
    fun `non-reset file load jumps to the room position`() {
        player.isFileLoaded = false
        serverState(300.0, paused = false)
        step(1.0)
        player.isFileLoaded = true
        engine.onFileLoaded(resetPosition = false)
        assertEquals(301.0, player.position, 0.3)
        assertFalse(player.isPaused)
    }

    @Test
    fun `joining mid-playback does not pause or rewind the room while the file opens`() {
        player.isFileLoaded = false
        engine.onFileLoading()
        serverState(444.0, paused = false)
        step(1.0)
        // mpv has opened the file (paused at 0) but the app hasn't reported it yet.
        player.isFileLoaded = true
        step(1.0)
        assertTrue("nothing may be broadcast before onFileLoaded: ${host.sent}", host.sent.none { it.stateChange })
        assertTrue(host.readyChanges.isEmpty())
        engine.onFileLoaded(resetPosition = false)
        step(1.0)
        assertEquals(447.0, player.position, 0.3)
        assertFalse(player.isPaused)
        assertTrue(host.sent.none { it.stateChange })
    }

    @Test
    fun `no file loaded never sends a state change`() {
        player.isFileLoaded = false
        serverState(10.0, paused = false)
        step(3.0)
        serverState(13.0, paused = true)
        step(1.0)
        assertTrue(host.sent.none { it.stateChange })
    }
}
