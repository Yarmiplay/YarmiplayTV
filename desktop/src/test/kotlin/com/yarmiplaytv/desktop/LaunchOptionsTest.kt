package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.SyncplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchOptionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parsesSyncplayStyleRoomOptions() {
        val o = LaunchOptions.parse(listOf("--host", "localhost:8999", "--name", "Desk", "--room", "tvtest", "-p", "pw"))
        assertEquals("localhost", o.host)
        assertEquals(8999, o.port)
        assertEquals("Desk", o.name)
        assertEquals("tvtest", o.room)
        assertEquals("pw", o.password)
        val profile = o.profile(SyncplayProfile(host = "syncplay.pl", port = 8997, username = "Saved", room = "old"), "PC-Host")!!
        assertEquals(SyncplayProfile(host = "localhost", port = 8999, username = "Desk", room = "tvtest", password = "pw"), profile)
    }

    @Test
    fun keepsSavedValuesForMissingOptions() {
        val o = LaunchOptions.parse(listOf("-r", "movie-night"))
        val profile = o.profile(SyncplayProfile(host = "example.org", port = 9000, username = ""), "PC-Host")!!
        assertEquals("example.org", profile.host)
        assertEquals(9000, profile.port)
        assertEquals("PC-Host", profile.username)
        assertEquals("movie-night", profile.room)
    }

    @Test
    fun noRoomOptionsMeansNoProfile() {
        assertNull(LaunchOptions.parse(emptyList()).profile(SyncplayProfile(), "PC"))
        assertNull(LaunchOptions.parse(listOf("--benchmark")).profile(SyncplayProfile(), "PC"))
    }

    @Test
    fun hostForms() {
        assertEquals("example.org" to null, LaunchOptions.parse(listOf("--host", "example.org")).let { it.host to it.port })
        assertEquals("::1" to 8999, LaunchOptions.parse(listOf("--host", "[::1]:8999")).let { it.host to it.port })
    }

    @Test
    fun filesIncludingPathsSplitAtSpaces() {
        val a = tmp.newFile("a.mkv")
        val spaced = tmp.newFile("Orbit Station S01E01.mkv")
        assertEquals(listOf(a), LaunchOptions.parse(listOf("--room", "r", a.path)).files)
        assertEquals(listOf(spaced), LaunchOptions.parse(spaced.path.split(" ")).files)
        assertEquals(listOf(spaced), LaunchOptions.parse(listOf(spaced.path)).files)
    }
}
