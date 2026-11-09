package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceProcessingUpgradeTest {
    @Test
    fun `processing chain enforces at most one NS one AEC and one AGC stage for every toggle combination on every platform`() {
        val bools = listOf(false, true)
        var testedCombinations = 0

        for (platform in VoiceChainPlatform.entries) {
            for (discordLobby in bools) {
                for (aec in bools) {
                    for (agc in bools) {
                        for (ns in bools) {
                            for (aiNs in bools) {
                                for (dfnAvailable in bools) {
                                    for (hwAvailable in bools) {
                                        val chain = VoiceProcessingChain.build(
                                            platform = platform,
                                            discordLobby = discordLobby,
                                            echoCancellation = aec,
                                            autoGainControl = agc,
                                            noiseSuppression = ns,
                                            aiNoiseSuppression = aiNs,
                                            webrtcApmAvailable = hwAvailable,
                                            platformAecAvailable = hwAvailable,
                                            platformAgcAvailable = hwAvailable,
                                            platformNsAvailable = hwAvailable,
                                            deepFilterNetAvailable = dfnAvailable,
                                            deepFilterNetFailureReason = if (!dfnAvailable) "test fallback" else null,
                                        )
                                        testedCombinations++

                                        // HARD RULE: At most one stage per category at any moment.
                                        assertTrue("aecStageCount must be <= 1: $chain", chain.aecStageCount <= 1)
                                        assertTrue("agcStageCount must be <= 1: $chain", chain.agcStageCount <= 1)
                                        assertTrue("nsStageCount must be <= 1: $chain", chain.nsStageCount <= 1)

                                        // Deterministic stage ordering: AEC -> AGC -> NS.
                                        assertEquals(
                                            listOf("AEC=${chain.aec.label}", "AGC=${chain.agc.label}", "NS=${chain.ns.label}"),
                                            chain.orderedStages,
                                        )
                                        assertEquals(
                                            "voice chain: AEC=${chain.aec.label} AGC=${chain.agc.label} NS=${chain.ns.label}",
                                            chain.logLine(),
                                        )

                                        // Mutual exclusion: when DeepFilterNet is active, WebRTC NS, platform NS, and spectral NS are ALL false.
                                        if (discordLobby && aiNs && dfnAvailable) {
                                            assertEquals(NsBackend.DEEPFILTERNET, chain.ns)
                                            assertTrue(chain.deepFilterNetEnabled)
                                            assertFalse("WebRTC NS must be disabled when DeepFilterNet is on", chain.webrtcNsEnabled)
                                            assertFalse("Platform NS must be disabled when DeepFilterNet is on", chain.platformNsEnabled)
                                            assertFalse("Spectral NS must be disabled when DeepFilterNet is on", chain.spectralNsEnabled)
                                            assertNull(chain.fallbackNotice)
                                        }

                                        // Syncplay relay does not pick up lobby-only stages (DFN, desktop WebRTC APM).
                                        if (!discordLobby) {
                                            assertFalse(chain.deepFilterNetEnabled)
                                            assertFalse(chain.webrtcAecEnabled)
                                            assertFalse(chain.webrtcAgcEnabled)
                                            assertFalse(chain.webrtcNsEnabled)
                                            assertNull(chain.fallbackNotice)
                                            if (platform == VoiceChainPlatform.DESKTOP) {
                                                assertEquals(AecBackend.NONE, chain.aec)
                                                assertEquals(AgcBackend.NONE, chain.agc)
                                                if (ns) {
                                                    assertEquals(NsBackend.SPECTRAL, chain.ns)
                                                    assertTrue(chain.dropGatedSilence)
                                                } else {
                                                    assertEquals(NsBackend.NONE, chain.ns)
                                                    assertFalse(chain.dropGatedSilence)
                                                }
                                            } else if (ns && hwAvailable) {
                                                assertEquals(NsBackend.PLATFORM, chain.ns)
                                                assertEquals(AecBackend.PLATFORM, chain.aec)
                                                assertEquals(AgcBackend.PLATFORM, chain.agc)
                                                assertFalse(chain.spectralNsEnabled)
                                                assertFalse(chain.dropGatedSilence)
                                            } else {
                                                assertEquals(NsBackend.NONE, chain.ns)
                                                assertEquals(AecBackend.NONE, chain.aec)
                                                assertEquals(AgcBackend.NONE, chain.agc)
                                            }
                                        }

                                        // Graceful fallback when DeepFilterNet is requested on lobby voice but unavailable.
                                        if (discordLobby && aiNs && !dfnAvailable) {
                                            assertFalse(chain.deepFilterNetEnabled)
                                            assertNotNull("Must provide user-facing fallback notice", chain.fallbackNotice)
                                            if (ns && hwAvailable) {
                                                val expectedNs = if (platform == VoiceChainPlatform.DESKTOP) {
                                                    NsBackend.WEBRTC
                                                } else {
                                                    NsBackend.PLATFORM
                                                }
                                                assertEquals(expectedNs, chain.ns)
                                            } else {
                                                assertEquals(NsBackend.NONE, chain.ns)
                                            }
                                        }

                                        // Discord lobby never runs the legacy spectral suppressor or frame-dropping gate.
                                        if (discordLobby) {
                                            assertFalse("Spectral NS must never run on Discord lobby", chain.spectralNsEnabled)
                                            assertFalse("Silence dropping gate must never run on Discord lobby", chain.dropGatedSilence)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(3 * 128, testedCombinations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processing chain rejects stacked deepfilternet and webrtc noise suppression`() {
        VoiceProcessingChain(
            platform = VoiceChainPlatform.DESKTOP,
            discordLobby = true,
            aec = AecBackend.WEBRTC,
            agc = AgcBackend.WEBRTC,
            ns = NsBackend.DEEPFILTERNET,
            webrtcAecEnabled = true,
            platformAecEnabled = false,
            webrtcAgcEnabled = true,
            platformAgcEnabled = false,
            webrtcNsEnabled = true,
            platformNsEnabled = false,
            deepFilterNetEnabled = true,
            spectralNsEnabled = false,
            dropGatedSilence = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processing chain rejects stacked deepfilternet and android platform noise suppression`() {
        VoiceProcessingChain(
            platform = VoiceChainPlatform.ANDROID,
            discordLobby = true,
            aec = AecBackend.PLATFORM,
            agc = AgcBackend.PLATFORM,
            ns = NsBackend.DEEPFILTERNET,
            webrtcAecEnabled = false,
            platformAecEnabled = true,
            webrtcAgcEnabled = false,
            platformAgcEnabled = true,
            webrtcNsEnabled = false,
            platformNsEnabled = true,
            deepFilterNetEnabled = true,
            spectralNsEnabled = false,
            dropGatedSilence = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processing chain rejects spectral gate on discord lobby`() {
        VoiceProcessingChain(
            platform = VoiceChainPlatform.DESKTOP,
            discordLobby = true,
            aec = AecBackend.NONE,
            agc = AgcBackend.NONE,
            ns = NsBackend.SPECTRAL,
            webrtcAecEnabled = false,
            platformAecEnabled = false,
            webrtcAgcEnabled = false,
            platformAgcEnabled = false,
            webrtcNsEnabled = false,
            platformNsEnabled = false,
            deepFilterNetEnabled = false,
            spectralNsEnabled = true,
            dropGatedSilence = false,
        )
    }

    @Test
    fun `real fft 960 forward and inverse roundtrip matches original signal`() {
        val fft = RealFft960()
        val input = FloatArray(960) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 48_000.0) * 0.5 +
                kotlin.math.cos(2.0 * Math.PI * 1200.0 * i / 48_000.0) * 0.25).toFloat()
        }
        val re = FloatArray(481)
        val im = FloatArray(481)
        val out = FloatArray(960)
        fft.forward(input, re, im)
        fft.inverse(re, im, out)
        for (i in input.indices) {
            val reconstructed = out[i] / 960f
            assertTrue("mismatch at $i: expected=${input[i]} got=$reconstructed", abs(input[i] - reconstructed) < 1e-4f)
        }
    }

    @Test
    fun `webrtc apm loads on desktop and processes reverse and capture frames`() {
        val apm = DesktopWebRtcApm(
            echoCancellation = true,
            autoGainControl = true,
            noiseSuppression = true,
        )
        try {
            assertTrue("WebRTC APM native library should load on desktop", apm.isAvailable)
            val refTone = VoicePcm.sine(hz = 440.0, frames = 1, amplitude = 0.25)
            val micTone = VoicePcm.sine(hz = 300.0, frames = 1, amplitude = 0.08)
            repeat(15) {
                apm.processReverseFrame20ms(refTone, channels = 1)
                val out = apm.processCaptureFrame20ms(micTone, channels = 1)
                assertEquals(960, out.size)
            }
            assertEquals(15L, apm.reverseFramesProcessed)
            assertEquals(15L, apm.captureFramesProcessed)

            // Verify live toggle updates (e.g. disabling WebRTC NS when DeepFilterNet activates).
            apm.configure(echoCancellation = true, autoGainControl = false, noiseSuppression = false)
            assertTrue(apm.echoCancellationEnabled)
            assertFalse(apm.autoGainControlEnabled)
            assertFalse(apm.noiseSuppressionEnabled)
        } finally {
            apm.close()
        }
    }

    @Test
    fun `deepfilternet3 processor runs in real time at 48 kHz 20 ms frames`() {
        var fallbackReason: String? = null
        val dfn = DeepFilterNetProcessor(
            onFallback = { fallbackReason = it },
        )
        try {
            assertTrue("DeepFilterNet3 should load from ONNX tar.gz", dfn.isAvailable)
            assertFalse(dfn.isDegraded)
            val speech = voicedHarmonic(frames = 50)
            var inRms = 0.0
            var outRms = 0.0
            for (f in 0 until 50) {
                val frame = speech.copyOfRange(f * 960, (f + 1) * 960)
                val out = dfn.processFrame20ms(frame, channels = 1)
                assertEquals(960, out.size)
                if (f >= 36) {
                    inRms += VoicePcm.rms(frame)
                    outRms += VoicePcm.rms(out)
                }
            }
            println("DFN3_EMA_MS=${"%.2f".format(dfn.emaFrameTimeMs)} LAST_MS=${"%.2f".format(dfn.lastFrameTimeMs)} LSNR=${dfn.lastLocalSnrDb} IN=$inRms OUT=$outRms")
            assertTrue("DeepFilterNet3 should stay available without fallback (reason=$fallbackReason)", dfn.isAvailable)
            assertTrue("DeepFilterNet3 EMA frame time (${dfn.emaFrameTimeMs} ms) must be < 20 ms real-time budget", dfn.emaFrameTimeMs < 20.0)
            assertTrue("voiced local SNR should read as speech, got ${dfn.lastLocalSnrDb}", dfn.lastLocalSnrDb > 15f)
            assertTrue(
                "DeepFilterNet3 should keep voiced energy (in=$inRms out=$outRms lsnr=${dfn.lastLocalSnrDb})",
                outRms > inRms * 0.45,
            )

            val noise = ShortArray(960 * 30)
            var seed = 0x12345678
            for (i in noise.indices) {
                seed = seed * 1664525 + 1013904223
                noise[i] = ((((seed ushr 16) and 0xFFFF) - 32768) * 0.08).toInt().toShort()
            }
            var noiseIn = 0.0
            var noiseOut = 0.0
            for (f in 0 until 30) {
                val frame = noise.copyOfRange(f * 960, (f + 1) * 960)
                val out = dfn.processFrame20ms(frame, channels = 1)
                if (f >= 18) {
                    noiseIn += VoicePcm.rms(frame)
                    noiseOut += VoicePcm.rms(out)
                }
            }
            println("DFN3_NOISE IN=$noiseIn OUT=$noiseOut LSNR=${dfn.lastLocalSnrDb}")
            assertTrue(
                "DeepFilterNet3 should attenuate noise (in=$noiseIn out=$noiseOut)",
                noiseOut < noiseIn * 0.55,
            )
        } finally {
            dfn.close()
        }
    }

    @Test
    fun `deepfilternet3 falls back gracefully when model fails to load or device is too slow`() {
        var loadFailureNotice: String? = null
        val missingModel = DeepFilterNetProcessor(
            backendFactory = { null },
            onFallback = { loadFailureNotice = it },
        )
        assertFalse(missingModel.isAvailable)
        assertNotNull(loadFailureNotice)
        val passthrough = VoicePcm.sine(hz = 440.0, frames = 1)
        assertTrue(missingModel.processFrame20ms(passthrough).contentEquals(passthrough))
        missingModel.close()

        // Simulate a slow device where each hop takes 12 ms (24 ms per 20 ms frame > 18 ms budget).
        var clockNs = 0L
        var slowNotice: String? = null
        val slowBackend = object : DeepFilterBackend {
            override val name: String = "mock-slow"
            override fun setAttenuationLimitDb(db: Float) {}
            override fun setPostFilterBeta(beta: Float) {}
            override fun processHop480(pcm: ShortArray, offset: Int): Float {
                clockNs += 12_000_000L // 12 ms per 10 ms hop -> 24 ms per 20 ms frame
                return 10f
            }
            override fun close() {}
        }
        val slowProcessor = DeepFilterNetProcessor(
            backendFactory = { slowBackend },
            maxFrameBudgetMs = 18.0,
            maxOverBudgetStreak = 3,
            nanoClock = { clockNs },
            onFallback = { slowNotice = it },
        )
        assertTrue(slowProcessor.isAvailable)
        repeat(3) {
            slowProcessor.processFrame20ms(passthrough)
        }
        assertFalse("Should disable DeepFilterNet after 3 consecutive slow frames", slowProcessor.isAvailable)
        assertTrue(slowProcessor.isDegraded)
        assertNotNull("Should report slow-device fallback reason", slowNotice)
        assertTrue(slowNotice!!.contains("too slow"))
        slowProcessor.close()
    }

    @Test
    fun `opus stereo and 128 kbps encode decode and interop with older mono clients`() {
        val stereo128 = OpusCodec(bitrate = 128_000, fec = true, dtx = false, sampleRate = 48_000, channels = 2)
        val legacyMono64 = OpusCodec(bitrate = 64_000, fec = true, dtx = false, sampleRate = 48_000, channels = 1)

        assertEquals(128_000, stereo128.effectiveBitrate)
        assertEquals(2, stereo128.effectiveChannels)

        val leftTone = VoicePcm.sine(hz = 440.0, frames = 5, amplitude = 0.4)
        val rightTone = VoicePcm.sine(hz = 880.0, frames = 5, amplitude = 0.3)
        val stereoPcm = ShortArray(960 * 2)
        for (f in 0 until 5) {
            for (i in 0 until 960) {
                stereoPcm[i * 2] = leftTone[f * 960 + i]
                stereoPcm[i * 2 + 1] = rightTone[f * 960 + i]
            }
            val packet = stereo128.encode(stereoPcm)
            assertNotNull(packet)
            assertTrue("128 kbps stereo packet (${packet!!.size} bytes) must fit inside Yv1Packet.MAX_PACKET payload (${OpusCodec.MAX_PAYLOAD_BYTES})", packet.size <= OpusCodec.MAX_PAYLOAD_BYTES)

            // v3.0.0 stereo decoder decodes 1920 interleaved stereo samples.
            val decodedStereo = stereo128.decode(packet)
            assertNotNull(decodedStereo)
            assertEquals(1920, decodedStereo!!.size)

            // Older v2.7–v2.9.9 mono decoder cleanly decodes stereo Opus packets into 960 mono samples!
            val decodedByLegacyMono = legacyMono64.decode(packet)
            assertNotNull(decodedByLegacyMono)
            assertEquals(960, decodedByLegacyMono!!.size)
        }

        // Conversely, v3.0.0 stereo decoder cleanly decodes mono packets from older clients into 1920 stereo samples!
        val monoFrame = leftTone.copyOfRange(0, 960)
        val monoPacket = legacyMono64.encode(monoFrame)
        assertNotNull(monoPacket)
        val monoDecodedAsStereo = stereo128.decode(monoPacket!!)
        assertNotNull(monoDecodedAsStereo)
        assertEquals(1920, monoDecodedAsStereo!!.size)
        assertTrue(VoicePcm.rms(monoDecodedAsStereo) > 500.0)
    }

    @Test
    fun `bitrate and stereo negotiation respects server caps and legacy relay limits`() {
        val discordDefault = VoiceMediaParams(
            host = "yarmiplay.tv",
            token = "0123456789abcdef",
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            provider = "discord-lobby",
            maxBitrate = 32_000, // Server default when unconfigured
        )
        assertEquals(64_000, VoiceMediaSession.opusBitrate(discordDefault))
        assertEquals(96_000, VoiceMediaSession.opusBitrate(discordDefault, 96_000))
        assertEquals(128_000, VoiceMediaSession.opusBitrate(discordDefault, 128_000))
        assertEquals(128_000, VoiceMediaSession.opusBitrate(discordDefault, 256_000))
        assertEquals(1, VoiceMediaSession.opusChannels(discordDefault, preferredStereo = false))
        assertEquals(2, VoiceMediaSession.opusChannels(discordDefault, preferredStereo = true))

        // Explicit server policy cap (e.g. host set maxBitrate = 48_000 or 96_000): client must not exceed it.
        val discordCapped48 = discordDefault.copy(maxBitrate = 48_000)
        assertEquals(48_000, VoiceMediaSession.opusBitrate(discordCapped48, 128_000))
        assertEquals(32_000, VoiceMediaSession.opusBitrate(discordCapped48, 32_000))

        // Legacy Syncplay relay path: never exceeds 64 kbps or seat.maxBitrate, and never forces stereo.
        val legacyRelay32 = VoiceMediaParams(
            host = "yarmiplay.tv",
            token = "0123456789abcdef",
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            provider = "yv1",
            maxBitrate = 32_000,
            channels = 1,
        )
        assertEquals(32_000, VoiceMediaSession.opusBitrate(legacyRelay32, 128_000))
        assertEquals(1, VoiceMediaSession.opusChannels(legacyRelay32, preferredStereo = true))
    }

    /** Harmonic stack with slow amplitude modulation — closer to voiced speech than a pure tone. */
    private fun voicedHarmonic(frames: Int): ShortArray {
        val n = frames * 960
        val out = ShortArray(n)
        val weights = doubleArrayOf(1.0, 0.5, 0.25, 0.12)
        for (i in 0 until n) {
            val t = i / 48_000.0
            val am = 0.5 + 0.5 * kotlin.math.sin(2.0 * Math.PI * 4.0 * t)
            var y = 0.0
            for (h in weights.indices) {
                y += weights[h] * kotlin.math.sin(2.0 * Math.PI * 140.0 * (h + 1) * t)
            }
            y = y / 1.87 * 0.3 * am
            out[i] = (y * 32767.0).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }
}
