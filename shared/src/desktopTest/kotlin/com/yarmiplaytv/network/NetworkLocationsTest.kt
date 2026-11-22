package com.yarmiplaytv.network

import com.yarmiplaytv.local.removableVolumeNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLocationsTest {
    @Test
    fun rejectsAPathThatClimbsOutOfTheShare() {
        assertNull(NetworkLocations.normalizeRelative("../secret"))
        assertNull(NetworkLocations.normalizeRelative("a/../../x"))
        assertEquals("Season 1/Episode.mkv", NetworkLocations.normalizeRelative("Season 1/../Season 1/Episode.mkv"))
        assertEquals("Episode.mkv", NetworkLocations.childPath("", "Episode.mkv"))
        assertNull(NetworkLocations.childPath("Season", ".."))
        assertNull(NetworkLocations.childPath("Season", "a/b"))
    }

    @Test
    fun keepsThePasswordOutOfTheDescription() {
        val place = NetworkLocation("id", "smb", "nas.local", 445, "ada", "s3cret-password", "media", "NAS")
        assertFalse(place.toString().contains("s3cret"))
        assertTrue(place.toString().contains("nas.local"))
    }

    @Test
    fun draftUsesDefaultPortsAndRejectsABadHost() {
        val ftp = NetworkLocations.draft("1", "ftp", "nas.local", "", "", "", "", "", "")
        assertTrue(ftp is NetworkDraft.Ok)
        assertEquals(21, (ftp as NetworkDraft.Ok).location.port)
        val smb = NetworkLocations.draft("1", "smb", "nas", "", "a", "b", "videos", "", "films")
        assertEquals(445, (smb as NetworkDraft.Ok).location.port)
        assertEquals("films", smb.location.startPath)
        assertTrue(NetworkLocations.draft("1", "ftp", "nas/path", "", "", "", "", "", "") is NetworkDraft.Invalid)
        assertTrue(NetworkLocations.draft("1", "smb", "nas", "", "", "", "", "", "") is NetworkDraft.Invalid)
    }

    @Test
    fun uriRoundTripOmitsThePassword() {
        val uri = NetworkUris.of("abc", "Season/Episode.mkv")
        assertEquals("abc" to "Season/Episode.mkv", NetworkUris.parse(uri))
        assertFalse(uri.contains("@"))
        assertNull(NetworkUris.parse("ytvnet://abc/../secret"))
        assertEquals("Episode.mkv", NetworkUris.fileName(uri))
    }

    @Test
    fun removableVolumesSkipInternalMirrors() {
        assertEquals(listOf("ABCD-1234"), removableVolumeNames(listOf("emulated", "self", "ABCD-1234", ".hidden")))
    }
}
