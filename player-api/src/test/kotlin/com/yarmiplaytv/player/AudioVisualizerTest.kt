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
    fun defaultBarsGraphIsTheOnePhonesAlreadyUse() {
        assertEquals(
            "[aid1]asplit=2[ao][vis];[vis]showfreqs=s=960x540:mode=bar:cmode=combined:colors=0x3DA5F4:" +
                "fscale=log:ascale=cbrt:win_size=2048:win_func=hann:overlap=0:rate=30:averaging=1[vo]",
            AudioVisualizerGraph.bars(1),
        )
        assertEquals(
            "[aid1]asplit=2[ao][vis];[vis]showspectrum=s=960x540:mode=combined:color=cool:scale=cbrt:fscale=log:" +
                "slide=replace:win_func=hann:overlap=0:fps=30:legend=0[vo]",
            AudioVisualizerGraph.spectrum(1),
        )
    }

    @Test
    fun lightProfileDrawsFewerPixelsAtALowerRate() {
        val bars = AudioVisualizerGraph.bars(1, profile = VisualizerProfile.LIGHT)
        val spectrum = AudioVisualizerGraph.spectrum(1, VisualizerProfile.LIGHT)
        assertTrue(bars.contains("s=480x270"))
        assertTrue(bars.contains("rate=15"))
        assertTrue(bars.contains("win_size=1024"))
        assertTrue(spectrum.contains("s=480x270"))
        assertTrue(spectrum.contains("fps=15"))
        assertEquals(VisualizerProfile.LIGHT, VisualizerProfile.choose(lowEnd = true, softwareRasterizer = false, processors = 8))
        assertEquals(VisualizerProfile.LIGHT, VisualizerProfile.choose(lowEnd = false, softwareRasterizer = true, processors = 8))
        assertEquals(VisualizerProfile.LIGHT, VisualizerProfile.choose(lowEnd = false, softwareRasterizer = false, processors = 2))
        assertEquals(VisualizerProfile.FULL, VisualizerProfile.choose(lowEnd = false, softwareRasterizer = false, processors = 4))
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
