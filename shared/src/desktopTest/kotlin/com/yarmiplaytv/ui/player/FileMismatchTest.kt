package com.yarmiplaytv.ui.player

import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FileMismatchTest {
    private val ep = "Orbit Station S01E02.mkv"
    private val mine = FileInfo(ep, 1440.0, 2L shl 20)
    private lateinit var locale: Locale

    @Before fun setUp() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() = Locale.setDefault(locale)

    private fun room(vararg others: RoomUser, myFile: FileInfo? = mine) = RoomState(
        status = ConnectionStatus.CONNECTED,
        username = "me",
        room = "r",
        users = listOf(RoomUser("me", "r", myFile)) + others,
        playlist = listOf(ep, "Other.mkv"),
    )

    @Test
    fun `the playlist warns on the entry whose file sizes differ`() {
        val r = room(RoomUser("Alex", "r", mine.copy(name = "Orbit_Station_S01E02.mkv", size = 700L shl 20)))
        assertEquals("File size differs from Alex's: 700 MB vs your 2 MB", r.sizeWarning(ep))
        assertNull(r.sizeWarning("Other.mkv"))
    }

    @Test
    fun `sizes that look alike are shown in bytes, and hashed sizes without numbers`() {
        assertEquals(
            "File size differs from Alex's: 2,097,153 bytes vs your 2,097,152 bytes",
            room(RoomUser("Alex", "r", mine.copy(size = mine.size + 1))).sizeWarning(ep),
        )
        val hashed = mine.copy(size = 0, sizeHash = Filenames.hashSize(1))
        assertEquals(
            "File size differs from Alex's and Jordan's",
            room(RoomUser("Alex", "r", hashed), RoomUser("Jordan", "r", mine.copy(size = 1))).sizeWarning(ep),
        )
    }

    @Test
    fun `no warning for matching, unknown or other files, or when we play something else`() {
        assertNull(room(RoomUser("Alex", "r", mine)).sizeWarning(ep))
        assertNull(room(RoomUser("Alex", "r", mine.copy(size = 0))).sizeWarning(ep))
        assertNull(room(RoomUser("Alex", "r", mine.copy(size = 0, sizeHash = Filenames.hashSize(mine.size)))).sizeWarning(ep))
        assertNull(room(RoomUser("Alex", "r", FileInfo("Other.mkv", 1440.0, 1))).sizeWarning(ep))
        assertNull(room(RoomUser("Alex", "r", mine.copy(size = 1)), myFile = FileInfo("Other.mkv", 1440.0, 5)).sizeWarning(ep))
        assertNull(room(RoomUser("Alex", "r", mine.copy(size = 1)), myFile = null).sizeWarning(ep))
    }

    @Test
    fun `the room panel says how a file differs`() {
        assertEquals("Different size than yours: 700 MB vs 2 MB", fileDifferenceNote(mine.copy(size = 700L shl 20), mine))
        assertEquals("Different name and duration than yours", fileDifferenceNote(FileInfo("Other.mkv", 60.0, 0), mine))
        assertNull(fileDifferenceNote(mine, mine))
        assertNull(fileDifferenceNote(null, mine))
    }
}
