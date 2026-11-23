package com.yarmiplaytv.sync

import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomWithoutClockTest {
    @Test
    fun serverStateMessagesDoNotEmit(): Unit = runBlocking {
        val room = MutableStateFlow(RoomState(status = ConnectionStatus.CONNECTED, room = "r", globalPaused = false))
        val seen = mutableListOf<RoomState>()
        val job = launch { room.map { it.withoutClock() }.distinctUntilChanged().collect { seen += it } }
        yield()
        // What every State message does: the clock moves and the ping wobbles.
        for (i in 1..10) {
            room.value = room.value.copy(globalPosition = i.toDouble(), rttMillis = 20.0 + i)
            yield()
        }
        room.value = room.value.copy(users = listOf(RoomUser("a", "r")), globalPosition = 11.0)
        yield()
        job.cancel()
        assertEquals(listOf(0, 1), seen.map { it.users.size })
        assertEquals(listOf(0.0, 0.0), seen.map { it.globalPosition })
    }

    @Test
    fun everythingButTheClockAndPingIsKept() {
        val s = RoomState(status = ConnectionStatus.CONNECTED, room = "r", globalPaused = false, globalPosition = 42.0, rttMillis = 31.0, lastSetBy = "a")
        assertEquals(s.copy(globalPosition = 0.0, rttMillis = 0.0), s.withoutClock())
    }
}
