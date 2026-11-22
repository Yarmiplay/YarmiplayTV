package com.yarmiplaytv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ac4Test {
    @Test
    fun passthroughWinsOverADecoder() {
        assertEquals(Ac4Route.PASSTHROUGH, Ac4.route(directPlayback = true, platformDecoder = true))
        assertEquals(Ac4Route.PASSTHROUGH, Ac4.route(directPlayback = true, platformDecoder = false))
        assertEquals(Ac4Route.DECODE, Ac4.route(directPlayback = false, platformDecoder = true))
        assertEquals(Ac4Route.NONE, Ac4.route(directPlayback = false, platformDecoder = false))
    }

    @Test
    fun codecNamesThatMeanAc4() {
        assertTrue(Ac4.isAc4Codec("ac4"))
        assertTrue(Ac4.isAc4Codec("AC-4"))
        assertTrue(Ac4.isAc4Codec("audio/ac4"))
        assertFalse(Ac4.isAc4Codec("ac3"))
        assertFalse(Ac4.isAc4Codec("eac3"))
        assertFalse(Ac4.isAc4Codec("aac"))
        assertFalse(Ac4.isAc4Codec("truehd"))
        assertFalse(Ac4.isAc4Codec(null))
        assertFalse(Ac4.isAc4Codec(""))
    }

    @Test
    fun aSupportedTrackPlaysOnAndroidAndMpvIsSilenced() {
        val tracks = listOf(audio(1, "aac", selected = true), audio(2, "ac4"))
        val plan = Ac4.plan(tracks, Ac4Selection.Pick(2), Ac4Route.PASSTHROUGH)
        assertTrue(plan.playOnAndroid)
        assertEquals(0, plan.ordinal)
        assertNull(plan.mpvAid)
        assertTrue(plan.overrideMpv)
        assertEquals(2, plan.selectedId)
        assertTrue(plan.tracks.first { it.id == 2 }.selected)
        assertFalse(plan.tracks.first { it.id == 1 }.selected)
        assertTrue(plan.tracks.first { it.id == 2 }.playable)
    }

    @Test
    fun decodeIsEnoughWhenTheDeviceCannotPassThrough() {
        val plan = Ac4.plan(listOf(audio(3, "ac4", selected = true)), Ac4Selection.Follow, Ac4Route.DECODE)
        assertTrue(plan.playOnAndroid)
        assertNull(plan.mpvAid)
        assertEquals(3, plan.selectedId)
    }

    @Test
    fun anUnsupportedTrackFallsBackToTheDefaultOtherTrack() {
        val tracks = listOf(
            audio(1, "aac"),
            audio(2, "ac3", isDefault = true),
            audio(4, "ac4", selected = true),
        )
        val plan = Ac4.plan(tracks, Ac4Selection.Follow, Ac4Route.NONE)
        assertFalse(plan.playOnAndroid)
        assertEquals(2, plan.mpvAid)
        assertEquals(2, plan.selectedId)
        assertTrue(plan.overrideMpv)
        val ac4 = plan.tracks.first { it.id == 4 }
        assertFalse(ac4.playable)
        assertFalse(ac4.selected)
        assertTrue(ac4.label.endsWith("unsupported"))
        assertEquals(-1, plan.ordinal)
    }

    @Test
    fun anUnsupportedTrackWithNothingElseLeavesAudioOff() {
        val plan = Ac4.plan(listOf(audio(4, "ac4", selected = true)), Ac4Selection.Follow, Ac4Route.NONE)
        assertFalse(plan.playOnAndroid)
        assertNull(plan.mpvAid)
        assertNull(plan.selectedId)
        assertTrue(plan.overrideMpv)
        assertTrue(plan.tracks.single().label.endsWith("unsupported"))
    }

    @Test
    fun pickingAnUnsupportedTrackKeepsTheTrackThatWasPlaying() {
        val tracks = listOf(audio(1, "aac", selected = true), audio(2, "ac4"))
        val plan = Ac4.plan(tracks, Ac4Selection.Pick(2), Ac4Route.NONE)
        assertEquals(1, plan.selectedId)
        assertEquals(1, plan.mpvAid)
        assertFalse(plan.playOnAndroid)
        assertFalse(plan.tracks.first { it.id == 2 }.selected)
    }

    @Test
    fun aNormalTrackStaysWithMpv() {
        val tracks = listOf(video(1), audio(1, "aac", selected = true), audio(2, "ac4"))
        val follow = Ac4.plan(tracks, Ac4Selection.Follow, Ac4Route.PASSTHROUGH)
        assertFalse(follow.playOnAndroid)
        assertFalse(follow.overrideMpv)
        assertEquals(1, follow.selectedId)
        assertEquals(tracks[0], follow.tracks[0])

        val picked = Ac4.plan(tracks, Ac4Selection.Pick(1), Ac4Route.PASSTHROUGH)
        assertEquals(1, picked.mpvAid)
        assertTrue(picked.overrideMpv)
        assertFalse(picked.playOnAndroid)
    }

    @Test
    fun turningAudioOffClearsTheAndroidPath() {
        val plan = Ac4.plan(listOf(audio(2, "ac4", selected = true)), Ac4Selection.Pick(null), Ac4Route.DECODE)
        assertFalse(plan.playOnAndroid)
        assertNull(plan.mpvAid)
        assertTrue(plan.overrideMpv)
        assertTrue(plan.tracks.none { it.selected })
    }

    @Test
    fun theSecondAc4TrackIsTheSecondPlatformStream() {
        val tracks = listOf(audio(1, "ac4"), audio(2, "aac", selected = true), audio(3, "ac-4"))
        val plan = Ac4.plan(tracks, Ac4Selection.Pick(3), Ac4Route.DECODE)
        assertEquals(1, plan.ordinal)
        assertEquals(3, Ac4.trackId(tracks, 1))
        assertNull(plan.mpvAid)
    }

    @Test
    fun platformStreamsAreAddedOnlyWhenMpvDidNotListAc4() {
        val listed = Ac4.withStreams(listOf(audio(1, "ac4")), listOf(Ac4Stream("eng")))
        assertEquals(1, listed.size)

        val added = Ac4.withStreams(listOf(audio(1, "aac", selected = true)), listOf(Ac4Stream("eng"), Ac4Stream(null)))
        assertEquals(listOf(1, Ac4.SYNTHETIC_ID_BASE, Ac4.SYNTHETIC_ID_BASE + 1), added.map { it.id })
        assertEquals("eng", added[1].language)
        assertEquals("ac4", added[1].codec)
        assertFalse(added[1].selected)

        val only = Ac4.plan(added, Ac4Selection.Follow, Ac4Route.NONE)
        assertEquals(1, only.selectedId)
        assertFalse(only.tracks.first { it.id == Ac4.SYNTHETIC_ID_BASE }.playable)
    }

    @Test
    fun aFailedAc4AttemptReturnsToAnotherTrack() {
        val tracks = listOf(audio(1, "aac", isDefault = true), audio(2, "ac4"))
        val plan = Ac4.plan(tracks, Ac4Selection.Follow, Ac4Route.NONE)
        assertEquals(1, plan.selectedId)
        assertEquals(1, plan.mpvAid)
        assertTrue(plan.overrideMpv)
        assertFalse(plan.playOnAndroid)
        assertFalse(plan.tracks.first { it.id == 2 }.playable)
    }

    @Test
    fun aFileWhoseOnlyAudioIsAc4PlaysItWhenTheDeviceCan() {
        val tracks = Ac4.withStreams(emptyList(), listOf(Ac4Stream(null)))
        val plan = Ac4.plan(tracks, Ac4Selection.Follow, Ac4Route.PASSTHROUGH)
        assertTrue(plan.playOnAndroid)
        assertEquals(Ac4.SYNTHETIC_ID_BASE, plan.selectedId)
        assertNull(plan.mpvAid)
        assertEquals(0, plan.ordinal)
    }

    private fun audio(id: Int, codec: String, selected: Boolean = false, isDefault: Boolean = false) = Track(
        id = id,
        type = TrackType.AUDIO,
        title = null,
        language = null,
        codec = codec,
        selected = selected,
        isDefault = isDefault,
        isExternal = false,
    )

    private fun video(id: Int) = Track(
        id = id,
        type = TrackType.VIDEO,
        title = null,
        language = null,
        codec = "h264",
        selected = true,
        isDefault = true,
        isExternal = false,
    )
}
