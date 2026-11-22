package com.yarmiplaytv.ui.mobile

import com.yarmiplaytv.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStateWithoutClockTest {
    @Test
    fun clockTicksDoNotEmit(): Unit = runBlocking {
        val state = MutableStateFlow(PlaybackState(fileLoaded = true, paused = false, position = 1.0))
        val seen = mutableListOf<PlaybackState>()
        val job = launch { state.map { it.withoutClock() }.distinctUntilChanged().collect { seen += it } }
        yield()
        for (i in 2..10) {
            state.value = state.value.copy(position = i * 0.25)
            yield()
        }
        state.value = state.value.copy(paused = true, position = 3.0)
        yield()
        job.cancel()
        assertEquals(listOf(false, true), seen.map { it.paused })
        assertEquals(listOf(0.0, 0.0), seen.map { it.position })
    }

    @Test
    fun everythingButTheClockIsKept() {
        val s = PlaybackState(url = "u", fileLoaded = true, position = 12.5, duration = 60.0, paused = false, cacheSeconds = 3.0)
        assertEquals(s.copy(position = 0.0), s.withoutClock())
    }
}
