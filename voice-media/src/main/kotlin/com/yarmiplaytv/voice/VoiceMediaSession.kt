package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared encode/decode + `yv1` transport. Platforms feed PCM via [offerCapturePcm] and mix
 * remote PCM from [pollMixedPcm] (or [onRemotePcm] if set).
 *
 * Capture path: single [VoiceNoiseSuppressor] stage (unless platform WebRTC NS is active) + Opus.
 * Receive path: per-sender [VoiceJitterBuffer] + per-sender [OpusCodec] decoder, float participant
 * summing without 1/N division, and a single final look-ahead soft [VoiceLimiter] (~-1 dBFS ceiling).
 */
class VoiceMediaSession(
    private val scope: CoroutineScope,
    private val onRemotePcm: ((sender: Long, pcm: ShortArray) -> Unit)? = null,
) {
    private val transmitting = AtomicBoolean(false)
    private val wantTransmit = AtomicBoolean(false)
    private val selfMuted = AtomicBoolean(false)
    private val deaf = AtomicBoolean(false)
    private val micTest = AtomicBoolean(false)
    private val standaloneLoopback = AtomicBoolean(false)
    /** Set from the mute thread; the capture thread drops any partial frame before the next encode. */
    private val dropTail = AtomicBoolean(false)
    /** Discord lobby capture: one soft noise stage, no hard silence drop, −60 dBFS speech. */
    private var discordCapture = false
    private var speechThreshold = LEGACY_SPEECH_RMS
    private var inputGain = 1f
    private var outputGain = 1f
    private val peerMuted = ConcurrentHashMap<Long, Boolean>()
    private val peerVolume = ConcurrentHashMap<Long, Float>()
    private val peerDecoders = ConcurrentHashMap<Long, OpusCodec>()
    private val lock = Mutex()
    private var codec: OpusCodec? = null
    private var loopbackCodec: OpusCodec? = null
    private var transport: Yv1Transport? = null
    private var wsTransport: Yv1WsTransport? = null
    private var muxTransport: Yv1MuxTransport? = null
    private var decodeJob: Job? = null
    private var playbackJob: Job? = null
    /** Bounded so a stalled ingest path cannot retain unbounded Opus copies. */
    private var inbound = boundedInbound()
    private val mixBuf = ShortArray(960)
    private val mixReady = AtomicBoolean(false)
    private val receivedFrames = AtomicBoolean(false)
    private val sentFramesCount = AtomicInteger(0)
    private val loopbackDecodedCount = AtomicInteger(0)
    private val lastRms = AtomicReference(0.0)
    private val captureRms = AtomicReference(0.0)
    private val loopbackRms = AtomicReference(0.0)
    /** Mic energy before the spectral gate, so the activity ring can show real pickup. */
    private val pickupRms = AtomicReference(0.0)
    private val lastSender = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    /** Senders with real (non-PLC) audible PCM this playback tick — no hangover. */
    private val audibleSenders = AtomicReference<Set<Long>>(emptySet())
    private val jitter = VoiceJitterBuffer()
    private val noise = VoiceNoiseSuppressor()
    private val limiter = VoiceLimiter()
    private val loopbackLimiter = VoiceLimiter()
    private val preOpus = VoicePcm.FrameAccumulator()

    val frameSamples: Int get() = codec?.frameSamples ?: 960
    val running: Boolean get() = transport != null || wsTransport != null || muxTransport != null || standaloneLoopback.get()
    val usingTcp: Boolean get() = transport?.usingTcp == true
    /** True when media is on Syncplay mux or the Syncplay-port `/yarmiplay/voice` WebSocket. */
    val usingSyncplayWs: Boolean get() = wsTransport != null || muxTransport != null
    /** True when media rides the Syncplay connection (TCP `0x1E` / binary `/yarmiplay/ws`). */
    val usingSyncplayMux: Boolean get() = muxTransport != null
    val receivedInbound: Boolean
        get() = transport?.receivedInbound == true ||
            wsTransport?.receivedInbound == true ||
            muxTransport?.receivedInbound == true
    val decodedAudio: Boolean get() = receivedFrames.get()
    val sentPacketsCount: Int get() = sentFramesCount.get()
    val loopbackPacketsCount: Int get() = loopbackDecodedCount.get()
    val isMicTestActive: Boolean get() = micTest.get()
    val isStandaloneLoopback: Boolean get() = standaloneLoopback.get()
    val encoder: OpusCodec? get() = codec
    val jitterBuffer: VoiceJitterBuffer get() = jitter
    val lastRemoteRms: Double get() = lastRms.get()
    /** 0…1 remote mix level for RX diagnostics (from last decoded frame RMS). */
    val receiveLevel: Float
        get() = (lastRms.get() / 2500.0).coerceIn(0.0, 1.0).toFloat()
    /** Post-NS capture RMS (0…~32768) while transmitting; decays when muted / not capturing. */
    val lastCaptureRms: Double get() = captureRms.get()
    /** 0…1 mic level meter while [isMicTestActive] is running. */
    val micTestLevel: Float
        get() = if (!micTest.get()) 0f else (loopbackRms.get() / 2500.0).coerceIn(0.0, 1.0).toFloat()
    /**
     * 0…1 mic activity for UI rings: post noise-suppression level while unmuted/transmitting.
     * Near-silence after VAD stays near 0.
     */
    val captureLevel: Float
        get() {
            if (micTest.get()) return micTestLevel
            if (!transmitting.get() || selfMuted.get() || deaf.get()) return 0f
            // Discord: pre-gate mic energy (real pickup). Relayed path: post-gate energy, unchanged.
            val rms = if (discordCapture) pickupRms.get() else captureRms.get()
            return (rms / 2500.0).coerceIn(0.0, 1.0).toFloat()
        }
    val lastRemoteSender: Long? get() = lastSender.get().takeUnless { it == Long.MIN_VALUE }

    /**
     * Remote senders whose audio we are actually playing right now (decoded packet with energy).
     * Empties the same 20 ms tick silence / PLC / local mute stops heard audio — no linger.
     */
    fun audibleSenderIds(): Set<Long> = if (deaf.get()) emptySet() else audibleSenders.get()

    /**
     * True while we are transmitting unmuted speech energy, for [sendVoiceActivity].
     * Always false during [isMicTestActive] so mic testing never broadcasts speaking state to the lobby.
     */
    fun capturingSpeech(): Boolean {
        if (micTest.get()) return false
        if (!transmitting.get() || selfMuted.get() || deaf.get()) return false
        // Post-suppressor energy. Discord's automatic VAD default is −60 dBFS; the relayed path
        // keeps the historical 120 RMS gate so speaking reports do not move.
        return captureRms.get() > speechThreshold
    }

    /** True when capture hardware should run (either live room transmission or local mic test). */
    fun shouldCaptureAudio(): Boolean = isTransmitting() || micTest.get()

    fun isSelfDeaf(): Boolean = deaf.get()

    fun resetReceiveStats() {
        receivedFrames.set(false)
        lastRms.set(0.0)
    }

    fun setNoiseSuppression(on: Boolean) {
        noise.setEnabled(on)
        if (!on) noise.reset()
    }

    fun isNoiseSuppressionEnabled(): Boolean = noise.isEnabled()

    /** Feed measured receiver packet-loss percentage (`0..100`) to the Opus encoder. */
    fun reportPacketLoss(lossPercent: Int) {
        codec?.updatePacketLoss(lossPercent)
    }

    /**
     * Start a standalone local loopback session (no network transport) for the settings Mic Test
     * when the user is not currently connected to a voice room.
     */
    fun startStandaloneMicTest(): Boolean {
        disconnect()
        discordCapture = true
        noise.setHardGate(false)
        speechThreshold = DISCORD_VAD_RMS
        val opus = OpusCodec(DISCORD_BITRATE, fec = true, dtx = false, DISCORD_CLOCK, DISCORD_CHANNELS)
        codec = opus
        loopbackCodec = OpusCodec(DISCORD_BITRATE, fec = true, dtx = false, DISCORD_CLOCK, DISCORD_CHANNELS)
        loopbackLimiter.reset()
        preOpus.clear()
        noise.reset()
        standaloneLoopback.set(true)
        micTest.set(true)
        return true
    }

    /**
     * Enable or disable local Mic Test loopback on an active session.
     * While enabled, [offerCapturePcm] runs the full send chain (input gain → noise suppression →
     * Opus encode → Opus decode → limiter → local playback) and suppresses all network sends.
     */
    fun setMicTest(active: Boolean) {
        val was = micTest.getAndSet(active)
        if (active && !was) {
            val opus = codec ?: OpusCodec(DISCORD_BITRATE, fec = true, dtx = false, DISCORD_CLOCK, DISCORD_CHANNELS).also {
                codec = it
            }
            loopbackCodec = OpusCodec(opus.baseBitrate, fec = true, dtx = false, opus.sampleRate, opus.channels)
            loopbackLimiter.reset()
            loopbackRms.set(0.0)
            preOpus.clear()
            noise.flushPending()
            noise.setHardGate(false)
        } else if (!active && was) {
            loopbackRms.set(0.0)
            preOpus.clear()
            noise.flushPending()
            noise.setHardGate(!discordCapture)
            if (standaloneLoopback.get()) {
                disconnect()
            }
        }
    }

    suspend fun connect(seat: VoiceMediaParams): Boolean {
        disconnect()
        receivedFrames.set(false)
        sentFramesCount.set(0)
        loopbackDecodedCount.set(0)
        lastRms.set(0.0)
        jitter.clear()
        peerDecoders.clear()
        limiter.reset()
        loopbackLimiter.reset()
        preOpus.clear()
        noise.reset()
        val token = Yv1Packet.parseTokenHex(seat.token) ?: return false
        val key = Yv1Packet.decodeKeyBase64(seat.key) ?: return false
        val discord = seat.isDiscordLobby
        discordCapture = discord
        noise.setHardGate(!discord)
        speechThreshold = if (discord) DISCORD_VAD_RMS else LEGACY_SPEECH_RMS
        val bitrate = opusBitrate(seat)
        val clock = opusClock(seat)
        val channels = opusChannels(seat)
        // DTX stays off. Silence gating on the relayed path is the noise suppressor; Discord
        // capture sends continuous 20 ms frames so the far end is not asked to conceal holes.
        val opus = OpusCodec(bitrate, fec = seat.fec, dtx = false, clock, channels)
        codec = opus
        loopbackCodec = OpusCodec(bitrate, fec = seat.fec, dtx = false, clock, channels)
        inbound = boundedInbound()
        val frames = inbound
        val onAudio: (Long, ByteArray, Int, Int) -> Unit =
            { sender, frame, sequence, _ -> frames.trySend(Triple(sender, sequence, frame)) }

        // Preferred (server 2.1.0+): yv1 on the Syncplay connection (mux pipe from SyncplayClient).
        val muxPipe = seat.syncplayMux
        if (muxPipe != null) {
            val mux = Yv1MuxTransport(muxPipe, token, key, scope, onAudio)
            if (mux.start()) {
                muxTransport = mux
                decodeJob = scope.launch { ingestLoop(frames) }
                playbackJob = scope.launch { playbackLoop(opus) }
                return true
            }
            mux.close()
            if (seat.syncplayMediaOnly) {
                println("VoiceMediaSession Syncplay mux failed (no legacy fallback)")
                codec = null
                frames.close()
                return false
            }
            println("VoiceMediaSession Syncplay mux failed; trying legacy paths")
        }

        // Fallback: dedicated `/yarmiplay/voice` WebSocket on the Syncplay port (server 2.0 browsers / older clients).
        if (seat.syncplayPort > 0) {
            val ws = Yv1WsTransport(
                host = seat.host,
                syncplayPort = seat.syncplayPort,
                secure = seat.secure,
                wsPath = seat.wsPath,
                tokenHex = seat.token,
                token = token,
                key = key,
                scope = scope,
                onAudio = onAudio,
            )
            if (ws.start()) {
                wsTransport = ws
                decodeJob = scope.launch { ingestLoop(frames) }
                playbackJob = scope.launch { playbackLoop(opus) }
                return true
            }
            ws.close()
            if (seat.syncplayMediaOnly) {
                println(
                    "VoiceMediaSession Syncplay-port media failed host=${seat.host} " +
                        "port=${seat.syncplayPort} secure=${seat.secure} (no legacy 9000 fallback)",
                )
                codec = null
                frames.close()
                return false
            }
            println(
                "VoiceMediaSession Syncplay-port media failed; falling back to legacy UDP/TCP " +
                    "${seat.udpPort}/${seat.tcpPort}",
            )
        }

        val t = Yv1Transport(
            host = seat.host,
            udpPort = seat.udpPort,
            tcpPort = seat.tcpPort,
            token = token,
            key = key,
            fingerprintHex = seat.fingerprint,
            scope = scope,
            forceTcp = seat.forceTcp,
            onAudio = onAudio,
        )
        if (!t.start()) {
            t.close()
            codec = null
            frames.close()
            println(
                "VoiceMediaSession legacy media open failed host=${seat.host} " +
                    "udp=${seat.udpPort} tcp=${seat.tcpPort}",
            )
            return false
        }
        transport = t
        decodeJob = scope.launch { ingestLoop(frames) }
        playbackJob = scope.launch { playbackLoop(opus) }
        return true
    }

    fun disconnect() {
        decodeJob?.cancel()
        decodeJob = null
        playbackJob?.cancel()
        playbackJob = null
        inbound.close()
        transport?.close()
        transport = null
        wsTransport?.close()
        wsTransport = null
        muxTransport?.close()
        muxTransport = null
        codec = null
        loopbackCodec = null
        transmitting.set(false)
        wantTransmit.set(false)
        deaf.set(false)
        micTest.set(false)
        standaloneLoopback.set(false)
        dropTail.set(false)
        discordCapture = false
        noise.setHardGate(true)
        speechThreshold = LEGACY_SPEECH_RMS
        captureRms.set(0.0)
        loopbackRms.set(0.0)
        pickupRms.set(0.0)
        audibleSenders.set(emptySet())
        peerMuted.clear()
        peerVolume.clear()
        peerDecoders.clear()
        jitter.clear()
        limiter.reset()
        loopbackLimiter.reset()
        preOpus.clear()
        noise.reset()
        synchronized(mixBuf) {
            mixBuf.fill(0)
            mixReady.set(false)
        }
    }

    fun setTransmitting(on: Boolean) {
        // A transmit request made while muted does not stick; unmute waits for another request.
        wantTransmit.set(on && !selfMuted.get())
        applyTransmit()
        if (!transmitting.get() && !micTest.get()) requestCaptureFlush()
    }

    fun isTransmitting(): Boolean = transmitting.get()

    fun setSelfMuted(muted: Boolean) {
        selfMuted.set(muted)
        if (muted) {
            wantTransmit.set(false)
            applyTransmit()
            if (!micTest.get()) requestCaptureFlush()
        }
    }

    /**
     * Discord deafen. Stops playout and blocks transmit without clearing the transmit request,
     * so undeafen resumes an open mic. An explicit mute still stays muted.
     */
    fun setSelfDeaf(deafened: Boolean) {
        deaf.set(deafened)
        applyTransmit()
        if (deafened) {
            audibleSenders.set(emptySet())
            lastRms.set(0.0)
        }
        if (!transmitting.get() && !micTest.get()) requestCaptureFlush()
    }

    private fun applyTransmit() {
        val on = wantTransmit.get() && !selfMuted.get() && !deaf.get()
        transmitting.set(on)
        if (!on) {
            captureRms.set(0.0)
            pickupRms.set(0.0)
        }
    }

    private fun requestCaptureFlush() {
        dropTail.set(true)
        captureRms.set(0.0)
        pickupRms.set(0.0)
    }

    /** Runs on the capture thread so it does not race the accumulator. */
    private fun consumeCaptureFlush() {
        if (!dropTail.getAndSet(false)) return
        preOpus.clear()
        noise.flushPending()
        captureRms.set(0.0)
        pickupRms.set(0.0)
    }

    fun isSelfMuted(): Boolean = selfMuted.get()

    fun setInputGain(gain: Float) {
        inputGain = gain.coerceIn(0f, 2f)
    }

    fun setOutputGain(gain: Float) {
        outputGain = gain.coerceIn(0f, 2f)
    }

    fun setPeerMuted(senderId: Long, muted: Boolean) {
        peerMuted[senderId] = muted
    }

    fun setPeerVolume(senderId: Long, volume: Float) {
        peerVolume[senderId] = volume.coerceIn(0f, 2f)
    }

    fun peerGainFor(senderId: Long): Float =
        peerVolume.getOrDefault(senderId, 1f) * outputGain

    /**
     * Sum multiple participant PCM frames in float (no 1/N division) and pass through the
     * single final look-ahead soft limiter (~-1 dBFS ceiling).
     */
    fun mixParticipantsAndLimit(tracks: List<Pair<ShortArray, Float>>): ShortArray =
        limiter.mixAndLimit(tracks, frameSamples)

    /**
     * Feed capture PCM (any length, ideally already 48 kHz mono). Applies noise suppression / VAD,
     * accumulates exact 20 ms Opus frames, encodes and either sends (when transmitting) or
     * loops back locally (when [isMicTestActive] is true, never sending to the lobby).
     */
    suspend fun offerCapturePcm(pcm: ShortArray): Boolean {
        val opus = codec ?: return false
        val testing = micTest.get()
        if (!testing && transport == null && wsTransport == null && muxTransport == null) return false
        if (pcm.isEmpty()) return true
        consumeCaptureFlush()
        if (!testing && (!transmitting.get() || selfMuted.get() || deaf.get())) {
            captureRms.set(0.0)
            pickupRms.set(0.0)
            return true
        }
        val useDiscordChain = discordCapture || testing
        // Discord applies the SDK input volume before the single noise stage, and never above
        // unity (the platform passes the capped amplitude). The relayed path still gains after NS.
        val source = if (useDiscordChain) applyInputGain(pcm) else pcm
        if (useDiscordChain) {
            val pre = VoicePcm.rms(source)
            pickupRms.set(pickupRms.get() * 0.55 + pre * 0.45)
        }
        val cleaned = noise.process(source)
        val rms = VoicePcm.rms(cleaned)
        val prev = captureRms.get()
        captureRms.set(prev * 0.55 + rms * 0.45)
        if (!useDiscordChain) pickupRms.set(captureRms.get())
        if (testing) {
            val meterRms = maxOf(pickupRms.get(), captureRms.get())
            loopbackRms.set(loopbackRms.get() * 0.5 + meterRms * 0.5)
        }
        for (frame in preOpus.push(cleaned)) {
            if (!micTest.get() && (!transmitting.get() || selfMuted.get() || deaf.get() || dropTail.get())) {
                consumeCaptureFlush()
                return true
            }
            val nowTesting = micTest.get()
            // Relayed path drops gated silence. Discord capture and mic test never drop frames.
            if (shouldDropGatedSilence(useDiscordChain, noise.isEnabled(), noise.lastSpeech, frame)) continue
            val gained = if (useDiscordChain || inputGain == 1f) frame.copyOf() else applyInputGain(frame)
            val encoded = opus.encode(gained) ?: continue
            if (nowTesting) {
                // Mic test isolation: decode locally through the receive limiter; NEVER send to lobby.
                val dec = (loopbackCodec ?: opus).decode(encoded) ?: continue
                val floatMix = FloatArray(dec.size)
                loopbackLimiter.accumulateFloat(floatMix, dec, outputGain)
                val limited = loopbackLimiter.limitToPcm16(floatMix, dec.size)
                val outRms = VoicePcm.rms(limited)
                loopbackRms.set(loopbackRms.get() * 0.45 + outRms * 0.55)
                loopbackDecodedCount.incrementAndGet()
                onRemotePcm?.invoke(0L, limited)
                if (onRemotePcm == null) {
                    synchronized(mixBuf) {
                        val n = minOf(mixBuf.size, limited.size)
                        System.arraycopy(limited, 0, mixBuf, 0, n)
                        mixReady.set(true)
                    }
                }
                continue
            }
            muxTransport?.sendAudio(encoded, opus.frameSamples)
                ?: wsTransport?.sendAudio(encoded, opus.frameSamples)
                ?: transport?.sendAudio(encoded, opus.frameSamples)
            sentFramesCount.incrementAndGet()
        }
        return true
    }

    /**
     * Poll the next 20 ms mixed + limited PCM frame for desktop playback.
     * Returns 0 if no new frame has been mixed since the previous poll so callers do not
     * stuff the hardware output buffer with silence.
     */
    fun pollMixedPcm(out: ShortArray): Int {
        val n = minOf(out.size, mixBuf.size)
        if (deaf.get() && !micTest.get()) {
            synchronized(mixBuf) {
                mixBuf.fill(0)
                mixReady.set(false)
            }
            return 0
        }
        synchronized(mixBuf) {
            if (!mixReady.getAndSet(false)) return 0
            System.arraycopy(mixBuf, 0, out, 0, n)
            mixBuf.fill(0)
        }
        return n
    }

    private fun applyInputGain(pcm: ShortArray): ShortArray {
        if (inputGain == 1f) return pcm
        return ShortArray(pcm.size) { i ->
            (pcm[i] * inputGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private suspend fun ingestLoop(frames: Channel<Triple<Long, Int, ByteArray>>) {
        while (scope.isActive) {
            val (sender, sequence, frame) = frames.receiveCatching().getOrNull() ?: break
            jitter.push(sender, sequence, frame)
        }
    }

    private fun decoderFor(sender: Long, template: OpusCodec): OpusCodec =
        peerDecoders.getOrPut(sender) {
            OpusCodec(
                bitrate = template.baseBitrate,
                fec = true,
                dtx = false,
                sampleRate = template.sampleRate,
                channels = template.channels,
            )
        }

    private suspend fun playbackLoop(opus: OpusCodec) {
        // Pace at 20 ms using a monotonic schedule so AudioTrack / desktop lines see a steady stream.
        val floatMix = FloatArray(opus.frameSamples)
        var lossFeedbackTick = 0
        var nextDeadlineNs = System.nanoTime()
        while (scope.isActive) {
            val now = System.nanoTime()
            if (now > nextDeadlineNs + 40_000_000L) {
                nextDeadlineNs = now
            }
            nextDeadlineNs += 20_000_000L

            val senders = jitter.primedSenders()
            if (senders.isEmpty()) {
                audibleSenders.set(emptySet())
                delay(4)
                nextDeadlineNs = System.nanoTime()
                continue
            }

            if (++lossFeedbackTick >= 10) {
                lossFeedbackTick = 0
                opus.updatePacketLoss(jitter.lossPercent())
            }

            if (deaf.get()) {
                // Keep the jitter buffer moving so undeafen is not a backlog, but play nothing.
                audibleSenders.set(emptySet())
                lastRms.set(0.0)
                for (sender in senders) {
                    val frame = jitter.poll(sender, decode = { null }, plc = { null }, fec = { null })
                    if (frame == null) {
                        peerMuted.remove(sender)
                        peerVolume.remove(sender)
                        peerDecoders.remove(sender)
                    }
                }
                val sleepMs = ((nextDeadlineNs - System.nanoTime()) / 1_000_000L).coerceIn(1L, 20L)
                delay(sleepMs)
                continue
            }

            val heard = LinkedHashSet<Long>()
            floatMix.fill(0f)
            var activeContributors = 0
            var activeSender = Long.MIN_VALUE

            for (sender in senders) {
                if (peerMuted[sender] == true) {
                    // Still advance/prune so a muted leaver slot does not linger forever.
                    if (jitter.poll(sender, decode = { null }, plc = { null }, fec = { null }) == null) {
                        peerMuted.remove(sender)
                        peerVolume.remove(sender)
                        peerDecoders.remove(sender)
                    }
                    continue
                }
                val peerCodec = decoderFor(sender, opus)
                val frame = jitter.poll(
                    sender,
                    decode = { peerCodec.decode(it) },
                    plc = { peerCodec.decodePlc() },
                    fec = { peerCodec.decodeFec(it) },
                )
                if (frame == null) {
                    peerMuted.remove(sender)
                    peerVolume.remove(sender)
                    peerDecoders.remove(sender)
                    continue
                }
                val pcm = frame.pcm
                lastSender.set(sender)
                activeSender = sender
                val vol = peerVolume.getOrDefault(sender, 1f) * outputGain
                var sumSq = 0.0
                if (vol > 0f) {
                    for (s in pcm) {
                        val v = s.toDouble() * vol
                        sumSq += v * v
                    }
                }
                val senderRms = sqrt(sumSq / pcm.size.coerceAtLeast(1))
                // Speaking UI: only real packets with energy count — PLC/silence drops the name this tick.
                if (!frame.concealed && senderRms >= AUDIBLE_RMS) heard += sender
                // Sum participant in float without dividing by participant count.
                limiter.accumulateFloat(floatMix, pcm, vol)
                activeContributors++
            }

            audibleSenders.set(heard)
            if (activeContributors > 0 && !micTest.get()) {
                // Single final look-ahead soft limiter (~-1 dBFS ceiling) on the summed float mix.
                val mixed = limiter.limitToPcm16(floatMix, opus.frameSamples)
                val mixRms = VoicePcm.rms(mixed)
                lastRms.set(mixRms)
                receivedFrames.set(true)
                onRemotePcm?.invoke(activeSender, mixed)
                if (onRemotePcm == null) {
                    lock.withLock {
                        synchronized(mixBuf) {
                            val n = minOf(mixBuf.size, mixed.size)
                            System.arraycopy(mixed, 0, mixBuf, 0, n)
                            mixReady.set(true)
                        }
                    }
                }
            }

            val sleepMs = ((nextDeadlineNs - System.nanoTime()) / 1_000_000L).coerceIn(1L, 20L)
            delay(sleepMs)
        }
    }

    companion object {
        /** Decoded PCM below this is treated as silence for the speaking-name overlay. */
        private const val AUDIBLE_RMS = 80.0

        /** Relayed-path speaking threshold (historical). Not used for Discord lobby capture. */
        const val LEGACY_SPEECH_RMS = 120.0

        /** Discord [Call::SetVADThreshold] automatic default, −60 dBFS on PCM16. */
        val DISCORD_VAD_RMS: Double = 32768.0 * Math.pow(10.0, -60.0 / 20.0)

        const val DISCORD_BITRATE = 64_000
        const val DISCORD_CLOCK = 48_000
        const val DISCORD_CHANNELS = 1

        /** Bitrate the encoder will use. Discord lobby voice is 64 kbps; the relay keeps the seat cap. */
        fun opusBitrate(seat: VoiceMediaParams): Int =
            if (seat.isDiscordLobby) DISCORD_BITRATE else seat.maxBitrate.coerceIn(24_000, 64_000)

        fun opusClock(seat: VoiceMediaParams): Int =
            if (seat.isDiscordLobby) DISCORD_CLOCK else seat.clockRate

        fun opusChannels(seat: VoiceMediaParams): Int =
            if (seat.isDiscordLobby) DISCORD_CHANNELS else seat.channels.coerceAtLeast(1)

        /** Cap inbound Opus frames (~1.28 s at 20 ms) — drop oldest if ingest stalls. */
        private const val INBOUND_CAP = 64

        private fun boundedInbound() =
            Channel<Triple<Long, Int, ByteArray>>(INBOUND_CAP, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
}

/** Relayed path only: drop a noise-gated near-silent Opus frame. Discord capture never drops. */
internal fun shouldDropGatedSilence(
    discordCapture: Boolean,
    noiseSuppressionEnabled: Boolean,
    speech: Boolean,
    frame: ShortArray,
): Boolean {
    if (discordCapture || !noiseSuppressionEnabled || speech) return false
    return frameIsNearSilence(frame)
}

internal fun frameIsNearSilence(frame: ShortArray): Boolean {
    if (frame.isEmpty()) return true
    var sum = 0.0
    for (s in frame) sum += s.toDouble() * s.toDouble()
    return sqrt(sum / frame.size) < 80.0
}

/** Map Syncplay `c17` participant id → numeric sender (connection id) when possible. */
fun participantSenderId(participantId: String): Long? {
    if (!participantId.startsWith('c')) return null
    return participantId.drop(1).toLongOrNull()
}

/** Map numeric yv1 sender id → Syncplay participant id (`c17`). */
fun senderParticipantId(senderId: Long): String = "c$senderId"
