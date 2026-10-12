package com.yarmiplaytv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedRoomsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun room(name: String, at: Long, host: String = "syncplay.pl", saved: Boolean = false, label: String = "") =
        SavedRoom(host, 8999, true, "Sam", name, "", label, saved, at)

    @Test
    fun `rooms round-trip through their stored lines`() {
        val rooms = listOf(room("movie-night", 5, saved = true, label = "Anime night"), room("+club:ABCDEF123456", 3).copy(password = "pw\twith tab"))
        val back = SavedRooms.decode(SavedRooms.encode(rooms))
        assertEquals(rooms[0], back[0])
        assertEquals("pw with tab", back[1].password)
        assertEquals(emptyList<SavedRoom>(), SavedRooms.decode("junk\nmore junk"))
    }

    @Test
    fun `recent rooms are newest first and capped, saved ones are kept and come first`() {
        var rooms = listOf(room("keep", 1, saved = true, label = "Mine"))
        for (i in 1..20) rooms = SavedRooms.visited(rooms, room("r$i", 100L + i))
        assertEquals("keep", rooms.first().room)
        assertEquals(1 + SavedRooms.MAX_RECENT, rooms.size)
        assertEquals("r20", rooms[1].room)
        assertFalse("the oldest recents went", rooms.any { it.room == "r1" })
    }

    @Test
    fun `rejoining keeps a saved room's name and the same room on a server is one entry`() {
        var rooms = listOf(room("movie-night", 1, saved = true, label = "Anime night"))
        rooms = SavedRooms.visited(rooms, room("movie-night", 50, host = "SyncPlay.pl").copy(username = "Alex"))
        assertEquals(1, rooms.size)
        assertEquals("Anime night" to true, rooms.single().label to rooms.single().saved)
        assertEquals("Alex" to 50L, rooms.single().username to rooms.single().lastUsedMillis)
    }

    @Test
    fun `saving, removing from saved and forgetting rooms in the settings`() = runBlocking {
        val store = desktopSettingsStore(tmp.newFolder("config"), scope)
        store.saveRoomVisit(room("a", 1))
        store.saveRoomVisit(room("b", 2))
        val a = store.current().savedRooms.first { it.room == "a" }
        store.setRoomSaved(a.key, true, "  Friday  ")
        var rooms = store.current().savedRooms
        assertEquals("a", rooms.first().room)
        assertEquals("Friday", rooms.first().label)
        store.setRoomSaved(a.key, false)
        rooms = store.current().savedRooms
        assertTrue(rooms.none { it.saved })
        assertEquals("", rooms.first { it.room == "a" }.label)
        store.saveRoom(room("new", 3, label = "Typed"))
        assertTrue(store.current().savedRooms.first { it.room == "new" }.saved)
        store.forgetSavedRoom(a.key)
        assertTrue(store.current().savedRooms.none { it.room == "a" })
    }
}
