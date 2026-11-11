package com.yarmiplaytv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualizerTest {
    private fun track(type: TrackType, id: Int, selected: Boolean = false, image: Boolean = false) = Track(
        id = id, type = type, title = null, language = null, codec = null,
        selected = selected, isDefault = false, isExternal = false, image = image,
    )

    @Test
    fun audioOnlyIgnoresCoverArtAndRequiresAudio() {
        val cover = listOf(track(TrackType.VIDEO, 1, image = true), track(TrackType.AUDIO, 1, selected = true))
        assertTrue(AudioOnly.isAudioOnly(cover))
        assertEquals(1, AudioOnly.audioId(cover))
        val video = cover + track(TrackType.VIDEO, 2, selected = true)
        assertFalse(AudioOnly.isAudioOnly(video))
        assertNull(AudioOnly.audioId(video))
        assertFalse(AudioOnly.isAudioOnly(listOf(track(TrackType.VIDEO, 1, image = true))))
        assertEquals(2, AudioOnly.audioId(listOf(track(TrackType.AUDIO, 1), track(TrackType.AUDIO, 2, selected = true))))
    }

    @Test
    fun barsGraphLeavesPlaybackAudioUntouchedAndUsesTheAccent() {
        val graph = AudioVisualizerGraph.bars(3, 0xE39A4A)
        assertTrue(graph.startsWith("[aid3]asplit=2[ao][vis];"))
        assertTrue(graph.endsWith("[vo]"))
        assertTrue(graph.contains("colors=0xE39A4A"))
        assertTrue(graph.contains("mode=bar"))
        assertTrue(graph.contains("overlap=0"))
        assertFalse(graph.substringBefore("[ao]").contains("showfreqs"))
    }

    @Test
    fun spectrumFallbackIsAlsoASplit() {
        val graph = AudioVisualizerGraph.spectrum(1)
        assertTrue(graph.startsWith("[aid1]asplit=2[ao][vis];"))
        assertTrue(graph.contains("showspectrum="))
        assertFalse(graph.contains("showfreqs"))
    }

    @Test
    fun lightProfileDrawsFewerPixels() {
        val full = AudioVisualizerGraph.bars(1)
        val light = AudioVisualizerGraph.bars(1, profile = VisualizerProfile.LIGHT)
        assertTrue(full.contains("s=960x540"))
        assertTrue(full.contains("rate=30"))
        assertTrue(full.contains("win_size=2048"))
        assertTrue(light.contains("s=640x360"))
        assertTrue(light.contains("rate=24"))
        assertTrue(light.contains("win_size=1024"))
        assertTrue(AudioVisualizerGraph.spectrum(1, VisualizerProfile.LIGHT).contains("fps=24"))
    }

    @Test
    fun videoNeverTouchesTheGraph() {
        val viz = AudioVisualizerController()
        val video = listOf(track(TrackType.VIDEO, 1, selected = true), track(TrackType.AUDIO, 1, selected = true))
        assertTrue(viz.onTracks(video, canRender = true) is VisualizerCommand.Leave)
        assertTrue(viz.onNewFile() is VisualizerCommand.Leave)
        assertTrue(viz.onColor(0xFFFFFF) is VisualizerCommand.Leave)
    }

    @Test
    fun audioInstallsBarsOnceThenClearsForVideo() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 2, selected = true), track(TrackType.VIDEO, 1, image = true))
        val first = viz.onTracks(audio, canRender = true)
        assertEquals(AudioVisualizerGraph.bars(2, AudioVisualizerGraph.DEFAULT_ACCENT), (first as VisualizerCommand.Set).graph)
        assertTrue(viz.onTracks(audio, canRender = true) is VisualizerCommand.Leave)
        val cleared = viz.onTracks(listOf(track(TrackType.VIDEO, 1, selected = true), track(TrackType.AUDIO, 1)), true)
        assertEquals("", (cleared as VisualizerCommand.Set).graph)
        assertTrue(viz.onTracks(listOf(track(TrackType.VIDEO, 1, selected = true)), true) is VisualizerCommand.Leave)
    }

    @Test
    fun playlistAdvanceClearsBeforeTheNextFileCanInstall() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 1, selected = true), track(TrackType.VIDEO, 1, image = true))
        assertTrue(viz.onTracks(audio, canRender = true) is VisualizerCommand.Set)
        assertEquals("", (viz.onNewFile() as VisualizerCommand.Set).graph)
        val video = listOf(track(TrackType.VIDEO, 1, selected = true), track(TrackType.AUDIO, 1, selected = true))
        assertTrue(viz.onTracks(video, canRender = true) is VisualizerCommand.Leave)
        assertTrue(viz.onColor(0xFFFFFF) is VisualizerCommand.Leave)
        assertTrue(viz.onProfile(VisualizerProfile.LIGHT) is VisualizerCommand.Leave)
    }

    @Test
    fun givingUpStillRequiresTheGraphToBeCleared() {
        val viz = AudioVisualizerController()
        viz.onTracks(listOf(track(TrackType.AUDIO, 1, selected = true)), true)
        assertTrue(viz.giveUp())
        assertTrue(viz.onTracks(listOf(track(TrackType.AUDIO, 1, selected = true)), true) is VisualizerCommand.Leave)
        assertTrue(viz.onNewFile() is VisualizerCommand.Leave)
    }

    @Test
    fun lostSurfaceClearsAndANewFileDoesNotTouchVideo() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 1, selected = true))
        assertTrue(viz.onTracks(audio, canRender = true) is VisualizerCommand.Set)
        assertEquals("", (viz.onTracks(audio, canRender = false) as VisualizerCommand.Set).graph)
        assertTrue(viz.onNewFile() is VisualizerCommand.Leave)
    }

    @Test
    fun rejectionFallsBackThenGivesUp() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 4, selected = true))
        viz.onTracks(audio, true)
        val fallback = viz.rejected()
        assertEquals(AudioVisualizerGraph.spectrum(4), (fallback as VisualizerCommand.Set).graph)
        assertEquals("", (viz.rejected() as VisualizerCommand.Set).graph)
        assertTrue(viz.onTracks(audio, true) is VisualizerCommand.Leave)
        assertTrue(viz.onNewFile() is VisualizerCommand.Leave)
        val again = viz.onTracks(audio, true)
        assertEquals(AudioVisualizerGraph.bars(4), (again as VisualizerCommand.Set).graph)
    }

    @Test
    fun commitTriesTheFallbackThenClears() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 4, selected = true))
        val first = viz.onTracks(audio, true)
        val seen = mutableListOf<String>()
        val showing = viz.commit(first) { graph ->
            seen += graph
            graph.isEmpty()
        }
        assertEquals(false, showing)
        assertEquals(
            listOf(AudioVisualizerGraph.bars(4), AudioVisualizerGraph.spectrum(4), ""),
            seen,
        )
    }

    @Test
    fun colourChangeRebuildsOnlyTheShowingBars() {
        val viz = AudioVisualizerController()
        val audio = listOf(track(TrackType.AUDIO, 1, selected = true))
        viz.onTracks(audio, true)
        val next = viz.onColor(0xC6EF84)
        assertEquals(AudioVisualizerGraph.bars(1, 0xC6EF84), (next as VisualizerCommand.Set).graph)
        assertTrue(viz.onColor(0xC6EF84) is VisualizerCommand.Leave)
    }

    @Test
    fun pausedMotionHoldsTheFrame() {
        val frame = floatArrayOf(0.2f, 0.8f, 0.4f)
        val held = SpectrumMotion.approach(frame, FloatArray(3) { 1f }, dt = 0f, bars = 3)
        assertArrayEquals(frame, held, 0f)
        val moved = SpectrumMotion.approach(frame, FloatArray(3) { 1f }, dt = 1f, bars = 3)
        assertTrue(moved[0] > frame[0] && moved[0] <= 1f)
    }

    @Test
    fun idleBarsStayInRangeAndChangeWithPhase() {
        val a = SpectrumMotion.idle(0.2f)
        val b = SpectrumMotion.idle(1.4f)
        assertEquals(SpectrumMotion.BARS, a.size)
        assertTrue(a.all { it in 0.04f..1f })
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun tagsPreferTheFirstPresentKey() {
        val values = mapOf("metadata/by-key/Artist" to "  Ada  ", "metadata/by-key/Title" to "")
        assertEquals("Ada", MediaTags.artist { values[it] })
        assertNull(MediaTags.title { values[it] })
    }

    @Test
    fun signalsPublishPositionAFewTimesASecondAndCacheOnlyWhileBuffering() {
        val signals = PlaybackSignals()
        assertTrue(signals.position(1_000))
        assertFalse(signals.position(1_100))
        assertTrue(signals.position(1_000 + PlaybackSignals.POSITION_MS))
        assertTrue(signals.position(1_400, seeking = true))
        assertFalse(signals.cache(3.2, buffering = false))
        assertTrue(signals.cache(3.2, buffering = true))
        assertFalse(signals.cache(3.8, buffering = true))
        assertTrue(signals.cache(4.1, buffering = true))
        assertTrue(signals.stats(0))
        assertFalse(signals.stats(100))
        assertTrue(signals.stats(PlaybackSignals.STATS_MS))
        signals.reset()
        assertTrue(signals.position(2_000))
    }

    @Test
    fun renderEdgeCapsVideoAndStopsUpscalingTheSpectrum() {
        assertEquals(1080, renderLongEdge(1080, configured = 1920, adaptive = 1920, visualizerEdge = null))
        assertEquals(1280, renderLongEdge(2560, configured = 1920, adaptive = 1280, visualizerEdge = null))
        assertEquals(960, renderLongEdge(2560, configured = 1920, adaptive = 1920, visualizerEdge = 960))
        assertEquals(640, renderLongEdge(800, configured = 1920, adaptive = 1920, visualizerEdge = 640))
        assertEquals(2, renderLongEdge(1, configured = 0, adaptive = 0, visualizerEdge = null))
    }

    @Test
    fun probeFindsANulTerminatedFilterAndNotAPrefix() {
        val library = "xx\u0000showfreqs\u0000showspectrum\u0000asplit\u0000aresample\u0000".encodeToByteArray()
        assertTrue(LavfiProbe.contains(library, "showfreqs"))
        assertFalse(LavfiProbe.contains(library, "show"))
        assertFalse(LavfiProbe.contains(library, "showfreq"))
        assertTrue(LavfiProbe.supports(library, AudioVisualizerGraph.bars(1)))
        assertTrue(LavfiProbe.supports(library, AudioVisualizerGraph.spectrum(1)))
        assertTrue(LavfiProbe.supports(null, AudioVisualizerGraph.bars(1)))
        assertTrue(LavfiProbe.supports(library, ""))
        assertFalse(LavfiProbe.supports("asplit\u0000aresample\u0000".encodeToByteArray(), AudioVisualizerGraph.bars(1)))
    }

    @Test
    fun bundledAndroidLibavfilterHasTheDesktopGraph() {
        val dir = java.io.File("../player-mpv/src/main/prebuilt-libavfilter")
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        for (abi in abis) {
            val file = java.io.File(dir, "$abi/libavfilter.so")
            assertTrue("$abi libavfilter.so is missing", file.isFile)
            val bytes = file.readBytes()
            assertTrue(LavfiProbe.supports(bytes, AudioVisualizerGraph.bars(1)))
            assertTrue(LavfiProbe.supports(bytes, AudioVisualizerGraph.spectrum(1)))
        }
    }
}
