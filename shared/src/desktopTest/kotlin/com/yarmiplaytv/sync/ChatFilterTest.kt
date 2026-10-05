package com.yarmiplaytv.sync

import com.yarmiplaytv.FakePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFilterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sync = SyncController(scope, FakePlayer())

    @After
    fun tearDown() = scope.cancel()

    private fun lines() = sync.feed.value.map { (it.from ?: "") + ": " + it.text }

    @Test
    fun `hiding chat removes what was shown and drops new lines, but keeps notifications`() {
        sync.receiveChat("Alex", "hi")
        sync.postLocal("Joined room 'movie-night' as Sam")
        sync.showChat = false
        sync.receiveChat("Alex", "still there?")

        assertEquals(listOf(": Joined room 'movie-night' as Sam"), lines())

        sync.showChat = true
        sync.receiveChat("Alex", "back")
        assertEquals(listOf(": Joined room 'movie-night' as Sam", "Alex: back"), lines())
    }

    @Test
    fun `a blocked name's lines disappear and stay hidden until unblocked`() {
        sync.receiveChat("Alex", "hi")
        sync.receiveChat("Troll", "spam")
        sync.block("Troll")
        sync.receiveChat("Troll", "more spam")
        sync.receiveChat("Alex", "ok")

        assertEquals(listOf("Alex: hi", "Alex: ok"), lines())
        assertEquals(setOf("Troll"), sync.blocked.value)

        sync.unblock("Troll")
        sync.receiveChat("Troll", "sorry")
        assertEquals("Troll: sorry", lines().last())
    }

    @Test
    fun `blocks end when the room is left or changed`() {
        sync.block("Troll")
        sync.changeRoom("another-room")
        assertTrue(sync.blocked.value.isEmpty())

        sync.block("Troll")
        sync.disconnect()
        assertTrue(sync.blocked.value.isEmpty())
        sync.receiveChat("Troll", "hello again")
        assertEquals(listOf("Troll: hello again"), lines())
    }
}
