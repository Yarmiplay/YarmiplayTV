package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared encode/decode + `yv1` transport. Platforms feed PCM via [offerCapturePcm] and mix
 * remote PCM from [pollMixedPcm] (or [onRemotePcm] if set).
 *
 * Capture path: optional [VoiceNoiseSuppressor] (RNNoise-framed spectral NS + VAD) before Opus.
 * Receive path: per-sender [VoiceJitterBuffer] (~40–80 ms) with FEC/PLC, paced at 20 ms.
 */
class VoiceMediaSession(
    private val scope: CoroutineScope,
    private val onRemotePcm: ((sender: Long, pcm: ShortArray) -> Unit)? = null,
) {
    private val transmitting = AtomicBoolean(false)
    private val selfMuted = AtomicBoolean(false)
    private var inputGain = 1f
    private var outputGain = 1f
    private val peerMuted = ConcurrentHashMap<Long, Boolean>()
    private val peerVolume = ConcurrentHashMap<Long, Float>()
    private val lock = Mutex()
    private var codec: OpusCodec? = null
    private var transport: Yv1Transport? = null
    private var decodeJob: Job? = null
    private var playbackJob: Job? = null
    private var inbound = Channel<Triple<Long, Int, ByteArray>>(Channel.UNLIMITED)
    private val mixBuf = ShortArray(960)
    private val receivedFrames = AtomicBoolean(false)
    private val lastRms = java.util.concurrent.atomic.AtomicReference(0.0)
    private val lastSender = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val jitter = VoiceJitterBuffer()
    private val noise = VoiceNoiseSuppressor()
    private val preOpus = VoicePcm.FrameAccumulator()

    val frameSamples: Int get() = codec?.frameSamples ?: 960
    val running: Boolean get() = transport != null
    val usingTcp: Boolean get() = transport?.usingTcp == true
    val receivedInbound: Boolean get() = transport?.receivedInbound == true
    val decodedAudio: Boolean get() = receivedFrames.get()
    val lastRemoteRms: Double get() = lastRms.get()
    val lastRemoteSender: Long? get() = lastSender.get().takeUnless { it == Long.MIN_VALUE }

    fun resetReceiveStats() {
        receivedFrames.set(false)
        lastRms.set(0.0)
    }

    fun setNoiseSuppression(on: Boolean) {
        noise.setEnabled(on)
        if (!on) noise.reset()
    }

    fun isNoiseSuppressionEnabled(): Boolean = noise.isEnabled()

    suspend fun connect(seat: VoiceMediaParams): Boolean {
        disconnect()
        receivedFrames.set(false)
        lastRms.set(0.0)
        jitter.clear()
        preOpus.clear()
        noise.reset()
        val token = Yv1Packet.parseTokenHex(seat.token) ?: return false
        val key = Yv1Packet.decodeKeyBase64(seat.key) ?: return false
        // DTX off: silence gating is handled by VoiceNoiseSuppressor VAD (smoother than Opus DTX).
        val bitrate = seat.maxBitrate.coerceIn(24_000, 64_000)
        val opus = OpusCodec(bitrate, fec = seat.fec, dtx = false, seat.clockRate, seat.channels)
        codec = opus
        inbound = Channel(Channel.UNLIMITED)
        val frames = inbound
        val t = Yv1Transport(
            host = seat.host,
            udpPort = seat.udpPort,
            tcpPort = seat.tcpPort,
            token = token,
            key = key,
            fingerprintHex = seat.fingerprint,
            scope = scope,
            forceTcp = seat.forceTcp,
            onAudio = { sender, frame, sequence, _ -> frames.trySend(Triple(sender, sequence, frame)) },
        )
        if (!t.start()) {
            t.close()
            codec = null
            frames.close()
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
        codec = null
        transmitting.set(false)
        jitter.clear()
        preOpus.clear()
    }

    fun setTransmitting(on: Boolean) {
        transmitting.set(on && !selfMuted.get())
    }

    fun isTransmitting(): Boolean = transmitting.get()

    fun setSelfMuted(muted: Boolean) {
        selfMuted.set(muted)
        if (muted) transmitting.set(false)
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
     * Feed capture PCM (any length, ideally already 48 kHz mono). Applies noise suppression / VAD,
     * accumulates exact 20 ms Opus frames, encodes and sends when transmitting.
     */
    suspend fun offerCapturePcm(pcm: ShortArray): Boolean {
        val opus = codec ?: return false
        val t = transport ?: return false
        if (pcm.isEmpty()) return true
        if (!transmitting.get() || selfMuted.get()) return true
        val cleaned = noise.process(pcm)
        for (frame in preOpus.push(cleaned)) {
            // Skip frames the VAD gated to (near) silence when NS is on — already zeroed.
            if (noise.isEnabled() && !noise.lastSpeech && isNearSilence(frame)) continue
            val gained = if (inputGain == 1f) frame.copyOf() else ShortArray(opus.frameSamples) { i ->
                (frame[i] * inputGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            val encoded = opus.encode(gained) ?: continue
            t.sendAudio(encoded, opus.frameSamples)
        }
        return true
    }

    fun pollMixedPcm(out: ShortArray): Int {
        val n = minOf(out.size, mixBuf.size)
        synchronized(mixBuf) {
            System.arraycopy(mixBuf, 0, out, 0, n)
            mixBuf.fill(0)
        }
        return n
    }

    private suspend fun ingestLoop(frames: Channel<Triple<Long, Int, ByteArray>>) {
        while (scope.isActive) {
            val (sender, sequence, frame) = frames.receiveCatching().getOrNull() ?: break
            jitter.push(sender, sequence, frame)
        }
    }

    private suspend fun playbackLoop(opus: OpusCodec) {
        // Pace at 20 ms so AudioTrack / desktop lines see a steady stream (not packet bursts).
        while (scope.isActive) {
            val start = System.nanoTime()
            val senders = jitter.primedSenders()
            if (senders.isEmpty()) {
                delay(5)
                continue
            }
            for (sender in senders) {
                if (peerMuted[sender] == true) {
                    jitter.poll(sender, decode = { null }, plc = { null }, fec = { null })
                    continue
                }
                val pcm = jitter.poll(
                    sender,
                    decode = { opus.decode(it) },
                    plc = { opus.decodePlc() },
                    fec = { opus.decodeFec(it) },
                ) ?: continue
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
                lastRms.set(sqrt(sumSq / scaled.size.coerceAtLeast(1)))
                receivedFrames.set(true)
                onRemotePcm?.invoke(sender, scaled)
                if (onRemotePcm == null) {
                    lock.withLock {
                        synchronized(mixBuf) {
                            val n = minOf(mixBuf.size, scaled.size)
                            for (i in 0 until n) {
                                val sum = mixBuf[i].toInt() + scaled[i].toInt()
                                mixBuf[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            val sleep = (20L - elapsedMs).coerceAtLeast(1L)
            delay(sleep)
        }
    }

    private fun isNearSilence(frame: ShortArray): Boolean {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size) < 80.0
    }
}

/** Map Syncplay `c17` participant id → numeric sender (connection id) when possible. */
fun participantSenderId(participantId: String): Long? {
    if (!participantId.startsWith('c')) return null
    return participantId.drop(1).toLongOrNull()
}
