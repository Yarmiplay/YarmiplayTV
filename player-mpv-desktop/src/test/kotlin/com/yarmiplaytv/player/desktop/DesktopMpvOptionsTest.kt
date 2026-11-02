package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMpvOptionsTest {
    @Test
    fun defaultAndLowEndShareTunedPlaybackKeys() {
        val normal = DesktopMpvPlayer.mpvOptions(DesktopMpvPlayer.effective(DesktopMpvOptions()))
        val low = DesktopMpvPlayer.mpvOptions(DesktopMpvPlayer.effective(DesktopMpvOptions(lowEnd = true)))
        for (key in listOf("hwdec", "framedrop", "video-sync", "scale", "cache", "demuxer-thread")) {
            assertTrue("missing $key", key in normal)
            assertEquals(key, normal[key], low[key])
        }
        assertEquals("vo", normal["framedrop"])
        assertEquals("audio", normal["video-sync"])
        assertEquals("bilinear", normal["scale"])
        assertEquals("128MiB", normal["demuxer-max-bytes"])
        assertEquals("64MiB", low["demuxer-max-bytes"])
        assertEquals("4", low["vd-lavc-threads"])
        assertEquals(1280, DesktopMpvPlayer.effective(DesktopMpvOptions(lowEnd = true)).maxRenderEdge)
    }
}
