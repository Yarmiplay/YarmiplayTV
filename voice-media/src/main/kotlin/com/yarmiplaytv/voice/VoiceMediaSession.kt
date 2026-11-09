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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared encode/decode + `yv1` transport. Platforms feed PCM via [offerCapturePcm] and mix
 * remote PCM from [pollMixedPcm] (or [onRemotePcm] if set).
 *
 * Capture path: optional [VoiceNoiseSuppressor] (RNNoise-framed spectral NS + VAD) before Opus
 * on the legacy relayed path; on Discord lobby voice, platform/WebRTC/DeepFilterNet handles NS.
 * Receive path: per-sender [VoiceJitterBuffer] (~40–80 ms) with FEC/PLC, paced at 20 ms.
 */
class VoiceMediaSession(
    private val scope: CoroutineScope,
    private val onRemotePcm: ((sender: Long, pcm: ShortArray) -> Unit)? = null,
) {
    private val transmitting = AtomicBoolean(false)
    private val wantTransmit = AtomicBoolean(false)
    private val selfMuted = AtomicBoolean(false)
    private val deaf = AtomicBoolean(false)
    /** Set from the mute thread; the capture thread drops any partial frame before the next encode. */
    private val dropTail = AtomicBoolean(false)
    /** Discord lobby capture: one soft noise stage, no hard silence drop, −60 dBFS speech. */
    private var discordCapture = false
    private var speechThreshold = LEGACY_SPEECH_RMS
    private var inputGain = 1f
    private var outputGain = 1f
    private var preferredBitrate = DISCORD_BITRATE
    private var preferredStereo = false
    private val peerMuted = ConcurrentHashMap<Long, Boolean>()
    private val peerVolume = ConcurrentHashMap<Long, Float>()
    private val lock = Mutex()
    private var codec: OpusCodec? = null
    private var transport: Yv1Transport? = null
    private var wsTransport: Yv1WsTransport? = null
    private var muxTransport: Yv1MuxTransport? = null
    private var decodeJob: Job? = null
    private var playbackJob: Job? = null
    /** Bounded so a stalled ingest path cannot retain unbounded Opus copies. */
    private var inbound = boundedInbound()
    private val mixBuf = ShortArray(VoicePcm.frameSamples * VoicePcm.STEREO_CHANNELS)
    private val receivedFrames = AtomicBoolean(false)
    private val lastRms = AtomicReference(0.0)
    private val captureRms = AtomicReference(0.0)
    /** Mic energy before the spectral gate, so the activity ring can show real pickup. */
    private val pickupRms = AtomicReference(0.0)
    private val lastSender = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    /** Senders with real (non-PLC) audible PCM this playback tick — no hangover. */
    private val audibleSenders = AtomicReference<Set<Long>>(emptySet())
    private val jitter = VoiceJitterBuffer()
    private val noise = VoiceNoiseSuppressor()
    private val preOpusMono = VoicePcm.FrameAccumulator(VoicePcm.frameSamples)
    private val preOpusStereo = VoicePcm.FrameAccumulator(VoicePcm.frameSamples * VoicePcm.STEREO_CHANNELS)

    val frameSamples: Int get() = codec?.frameSamples ?: 960
    val activeChannels: Int get() = codec?.effectiveChannels ?: 1
    val activeBitrate: Int get() = codec?.effectiveBitrate ?: preferredBitrate
    val running: Boolean get() = transport != null || wsTransport != null || muxTransport != null
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
    val lastRemoteRms: Double get() = lastRms.get()
    /** 0…1 remote mix level for RX diagnostics (from last decoded frame RMS). */
    val receiveLevel: Float
        get() = (lastRms.get() / 2500.0).coerceIn(0.0, 1.0).toFloat()
    /** Post-NS capture RMS (0…~32768) while transmitting; decays when muted / not capturing. */
    val lastCaptureRms: Double get() = captureRms.get()
    /**
     * 0…1 mic activity for UI rings: post noise-suppression level while unmuted/transmitting.
     * Near-silence after VAD stays near 0.
     */
    val captureLevel: Float
        get() {
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
     * False the moment capture RMS is near silence or muted — not tied to "mic open",
     * and not held by VAD hangover.
     */
    fun capturingSpeech(): Boolean {
        if (!transmitting.get() || selfMuted.get() || deaf.get()) return false
        // Post-suppressor energy. Discord's automatic VAD default is −60 dBFS; the relayed path
        // keeps the historical 120 RMS gate so speaking reports do not move.
        return captureRms.get() > speechThreshold
    }

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

    fun setPreferredBitrate(bitrateBps: Int) {
        preferredBitrate = bitrateBps.coerceIn(MIN_VOICE_BITRATE, MAX_VOICE_BITRATE)
    }

    fun getPreferredBitrate(): Int = preferredBitrate

    fun setPreferredStereo(stereo: Boolean) {
        preferredStereo = stereo
    }

    fun isPreferredStereo(): Boolean = preferredStereo

    suspend fun connect(seat: VoiceMediaParams): Boolean {
        disconnect()
        receivedFrames.set(false)
        lastRms.set(0.0)
        jitter.clear()
        preOpusMono.clear()
        preOpusStereo.clear()
        noise.reset()
        val token = Yv1Packet.parseTokenHex(seat.token) ?: return false
        val key = Yv1Packet.decodeKeyBase64(seat.key) ?: return false
        // DTX off: silence gating is handled by VoiceNoiseSuppressor VAD (smoother than Opus DTX).
        val discord = seat.isDiscordLobby
        discordCapture = discord
        noise.setHardGate(!discord)
        speechThreshold = if (discord) DISCORD_VAD_RMS else LEGACY_SPEECH_RMS
        val bitrate = opusBitrate(seat, preferredBitrate)
        val clock = opusClock(seat)
        val channels = opusChannels(seat, preferredStereo, bitrate)
        // DTX stays off. Silence gating on the relayed path is the noise suppressor; Discord
        // capture sends continuous 20 ms frames so the far end is not asked to conceal holes.
        val opus = OpusCodec(bitrate, fec = seat.fec, dtx = false, clock, channels)
        codec = opus
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
        transmitting.set(false)
        wantTransmit.set(false)
        deaf.set(false)
        dropTail.set(false)
        discordCapture = false
        noise.setHardGate(true)
        speechThreshold = LEGACY_SPEECH_RMS
        captureRms.set(0.0)
        pickupRms.set(0.0)
        audibleSenders.set(emptySet())
        peerMuted.clear()
        peerVolume.clear()
        jitter.clear()
        preOpusMono.clear()
        preOpusStereo.clear()
        noise.reset()
        synchronized(mixBuf) { mixBuf.fill(0) }
    }

    fun setTransmitting(on: Boolean) {
        // A transmit request made while muted does not stick; unmute waits for another request.
        wantTransmit.set(on && !selfMuted.get())
        applyTransmit()
        if (!transmitting.get()) requestCaptureFlush()
    }

    fun isTransmitting(): Boolean = transmitting.get()

    fun setSelfMuted(muted: Boolean) {
        selfMuted.set(muted)
        if (muted) {
            wantTransmit.set(false)
            applyTransmit()
            requestCaptureFlush()
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
        if (!transmitting.get()) requestCaptureFlush()
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
        preOpusMono.clear()
        preOpusStereo.clear()
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
     * Feed capture PCM (any length, ideally already 48 kHz). Applies noise suppression / VAD when
     * enabled, accumulates exact 20 ms Opus frames, encodes and sends when transmitting.
     */
    suspend fun offerCapturePcm(pcm: ShortArray, pcmChannels: Int = 1): Boolean {
        val opus = codec ?: return false
        if (transport == null && wsTransport == null && muxTransport == null) return false
        if (pcm.isEmpty()) return true
        consumeCaptureFlush()
        if (!transmitting.get() || selfMuted.get() || deaf.get()) {
            captureRms.set(0.0)
            pickupRms.set(0.0)
            return true
        }
        val inChannels = pcmChannels.coerceIn(1, 2)
        // Discord applies the SDK input volume before the single noise stage, and never above
        // unity (the platform passes the capped amplitude). The relayed path still gains after NS.
        val source = if (discordCapture) applyInputGain(pcm) else pcm
        if (discordCapture) {
            val pre = VoicePcm.rms(source)
            pickupRms.set(pickupRms.get() * 0.55 + pre * 0.45)
        }
        val cleaned: ShortArray
        val cleanedChannels: Int
        if (inChannels == 2 && !noise.isEnabled()) {
            if (opus.effectiveChannels == 2) {
                cleaned = source
                cleanedChannels = 2
            } else {
                cleaned = VoicePcm.stereoToMono(source)
                cleanedChannels = 1
            }
        } else {
            val monoSource = if (inChannels == 2) VoicePcm.stereoToMono(source) else source
            cleaned = noise.process(monoSource)
            cleanedChannels = 1
        }
        val rms = VoicePcm.rms(cleaned)
        val prev = captureRms.get()
        captureRms.set(prev * 0.55 + rms * 0.45)
        if (!discordCapture) pickupRms.set(captureRms.get())
        val accumulator = if (cleanedChannels == 2) preOpusStereo else preOpusMono
        for (frame in accumulator.push(cleaned)) {
            if (!transmitting.get() || selfMuted.get() || deaf.get() || dropTail.get()) {
                consumeCaptureFlush()
                return true
            }
            // Relayed path drops gated silence. Discord sends the attenuated frame so receivers
            // are not left concealing a hole.
            if (shouldDropGatedSilence(discordCapture, noise.isEnabled(), noise.lastSpeech, frame)) continue
            val gained = if (discordCapture || inputGain == 1f) frame.copyOf() else applyInputGain(frame)
            val encoded = opus.encode(gained) ?: continue
            muxTransport?.sendAudio(encoded, opus.frameSamples)
                ?: wsTransport?.sendAudio(encoded, opus.frameSamples)
                ?: transport?.sendAudio(encoded, opus.frameSamples)
        }
        return true
    }

    fun pollMixedPcm(out: ShortArray): Int {
        val ch = activeChannels
        val samplesPerChannel = frameSamples
        val mixSamples = samplesPerChannel * ch
        if (deaf.get()) {
            val n = minOf(out.size, mixSamples)
            out.fill(0, 0, n)
            synchronized(mixBuf) { mixBuf.fill(0) }
            return n
        }
        synchronized(mixBuf) {
            if (ch == 2 && out.size == samplesPerChannel) {
                // Caller requested a mono 20 ms frame while decoder is running stereo: downmix L+R.
                for (i in 0 until samplesPerChannel) {
                    val l = mixBuf[i * 2].toInt()
                    val r = mixBuf[i * 2 + 1].toInt()
                    out[i] = ((l + r) / 2).toShort()
                }
                mixBuf.fill(0, 0, mixSamples)
                return samplesPerChannel
            }
            val n = minOf(out.size, mixSamples)
            System.arraycopy(mixBuf, 0, out, 0, n)
            mixBuf.fill(0, 0, mixSamples)
            return n
        }
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

    private suspend fun playbackLoop(opus: OpusCodec) {
        // Pace at 20 ms so AudioTrack / desktop lines see a steady stream (not packet bursts).
        val mixSamples = opus.frameSamples * opus.effectiveChannels
        while (scope.isActive) {
            val start = System.nanoTime()
            val senders = jitter.primedSenders()
            if (senders.isEmpty()) {
                audibleSenders.set(emptySet())
                delay(5)
                continue
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
                    }
                }
                val elapsedDeaf = (System.nanoTime() - start) / 1_000_000L
                delay((20L - elapsedDeaf).coerceAtLeast(1L))
                continue
            }
            val heard = LinkedHashSet<Long>()
            for (sender in senders) {
                if (peerMuted[sender] == true) {
                    // Still advance/prune so a muted leaverslot does not linger forever.
                    if (jitter.poll(sender, decode = { null }, plc = { null }, fec = { null }) == null) {
                        peerMuted.remove(sender)
                        peerVolume.remove(sender)
                    }
                    continue
                }
                val frame = jitter.poll(
                    sender,
                    decode = { opus.decode(it) },
                    plc = { opus.decodePlc() },
                    fec = { opus.decodeFec(it) },
                )
                if (frame == null) {
                    peerMuted.remove(sender)
                    peerVolume.remove(sender)
                    continue
                }
                val pcm = frame.pcm
                lastSender.set(sender)
                val vol = peerVolume.getOrDefault(sender, 1f) * outputGain
                val scaled = when {
                    vol <= 0f -> ShortArray(pcm.size)
                    vol == 1f -> pcm
                    else -> ShortArray(pcm.size) { i ->
                        (pcm[i] * vol).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                var sumSq = 0.0
                for (s in scaled) sumSq += s.toDouble() * s.toDouble()
                val rms = sqrt(sumSq / scaled.size.coerceAtLeast(1))
                lastRms.set(rms)
                // Speaking UI: only real packets with energy count — PLC/silence drops the name this tick.
                if (!frame.concealed && rms >= AUDIBLE_RMS) heard += sender
                receivedFrames.set(true)
                onRemotePcm?.invoke(sender, scaled)
                if (onRemotePcm == null) {
                    lock.withLock {
                        synchronized(mixBuf) {
                            val n = minOf(mixSamples, minOf(mixBuf.size, scaled.size))
                            for (i in 0 until n) {
                                val sum = mixBuf[i].toInt() + scaled[i].toInt()
                                mixBuf[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                    }
                }
            }
            audibleSenders.set(heard)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            val sleep = (20L - elapsedMs).coerceAtLeast(1L)
            delay(sleep)
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
        const val DISCORD_STEREO_CHANNELS = 2
        const val MIN_VOICE_BITRATE = 24_000
        const val MAX_VOICE_BITRATE = 128_000
        const val LEGACY_MAX_BITRATE = 64_000
        const val SERVER_DEFAULT_MAX_BITRATE = 32_000
        const val STEREO_MIN_BITRATE = 32_000

        /** Bitrate the encoder will use. Discord lobby voice defaults to 64 kbps; the relay keeps the seat cap. */
        fun opusBitrate(seat: VoiceMediaParams): Int =
            opusBitrate(seat, DISCORD_BITRATE)

        /**
         * Negotiate encoder bitrate against the server/relay seat parameters:
         * - On the legacy Syncplay relay (`!seat.isDiscordLobby`), cap to `minOf(preferredBitrate, seat.maxBitrate)`
         *   clamped to `24_000..64_000` so older relays and the Syncplay-port relay are never exceeded.
         * - On the Discord lobby path (`seat.isDiscordLobby`), allow `24_000..128_000` (matching server
         *   `BITRATE_MAX = 128_000` and `MAX_PACKET = 1200`). If the server explicitly advertises a non-default
         *   cap (`seat.maxBitrate != 32_000`), honor `minOf(requested, seat.maxBitrate).coerceIn(24_000, 128_000)`.
         */
        fun opusBitrate(seat: VoiceMediaParams, preferredBitrate: Int): Int {
            val requested = preferredBitrate.coerceIn(MIN_VOICE_BITRATE, MAX_VOICE_BITRATE)
            return if (seat.isDiscordLobby) {
                val serverCap = if (seat.maxBitrate > 0 && seat.maxBitrate != SERVER_DEFAULT_MAX_BITRATE) {
                    seat.maxBitrate.coerceIn(MIN_VOICE_BITRATE, MAX_VOICE_BITRATE)
                } else {
                    MAX_VOICE_BITRATE
                }
                minOf(requested, serverCap).coerceIn(MIN_VOICE_BITRATE, MAX_VOICE_BITRATE)
            } else {
                val cap = if (seat.maxBitrate > 0) seat.maxBitrate else LEGACY_MAX_BITRATE
                minOf(requested, cap).coerceIn(MIN_VOICE_BITRATE, LEGACY_MAX_BITRATE)
            }
        }

        fun opusClock(seat: VoiceMediaParams): Int =
            if (seat.isDiscordLobby) DISCORD_CLOCK else seat.clockRate

        fun opusChannels(seat: VoiceMediaParams): Int =
            opusChannels(seat, preferredStereo = false)

        /**
         * Negotiate encoder/decoder channel count (1 = mono, 2 = stereo):
         * - Legacy Syncplay relay (`!seat.isDiscordLobby`) keeps `seat.channels.coerceAtLeast(1)` (mono) so older
         *   relays and clients are untouched.
         * - Discord lobby (`seat.isDiscordLobby`) uses stereo (`2`) when [preferredStereo] is enabled and the
         *   negotiated bitrate is at least 32 kbps; otherwise falls back to mono (`1`).
         */
        fun opusChannels(
            seat: VoiceMediaParams,
            preferredStereo: Boolean,
            negotiatedBitrate: Int = opusBitrate(seat, DISCORD_BITRATE),
        ): Int {
            if (!seat.isDiscordLobby) {
                return seat.channels.coerceAtLeast(1)
            }
            return if (preferredStereo && negotiatedBitrate >= STEREO_MIN_BITRATE) {
                DISCORD_STEREO_CHANNELS
            } else {
                DISCORD_CHANNELS
            }
        }

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
