package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.VoiceMediaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Shared encode/decode + `yv1` transport. Platforms feed PCM via [offerCapturePcm] and mix
 * remote PCM from [pollMixedPcm] (or [onRemotePcm] if set).
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
    private var inbound = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
    private val mixBuf = ShortArray(960)

    val frameSamples: Int get() = codec?.frameSamples ?: 960
    val running: Boolean get() = transport != null

    suspend fun connect(seat: VoiceMediaParams): Boolean {
        disconnect()
        val token = Yv1Packet.parseTokenHex(seat.token) ?: return false
        val key = Yv1Packet.decodeKeyBase64(seat.key) ?: return false
        val opus = OpusCodec(seat.maxBitrate, seat.fec, seat.dtx, seat.clockRate, seat.channels)
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
            onAudio = { sender, frame, _, _ -> frames.trySend(sender to frame) },
        )
        if (!t.start()) {
            t.close()
            codec = null
            frames.close()
            return false
        }
        transport = t
        decodeJob = scope.launch { decodeLoop(opus, frames) }
        return true
    }

    fun disconnect() {
        decodeJob?.cancel()
        decodeJob = null
        inbound.close()
        transport?.close()
        transport = null
        codec = null
        transmitting.set(false)
    }

    fun setTransmitting(on: Boolean) {
        transmitting.set(on && !selfMuted.get())
    }

    fun setSelfMuted(muted: Boolean) {
        selfMuted.set(muted)
        if (muted) transmitting.set(false)
    }

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

    /**
     * Encode one 20 ms capture frame and send when transmitting.
     * Applies [inputGain]. Returns false if not connected or frame size wrong.
     */
    suspend fun offerCapturePcm(pcm: ShortArray): Boolean {
        val opus = codec ?: return false
        val t = transport ?: return false
        if (pcm.size < opus.frameSamples) return false
        if (!transmitting.get() || selfMuted.get()) return true
        val gained = if (inputGain == 1f) pcm else ShortArray(opus.frameSamples) { i ->
            (pcm[i] * inputGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val frame = opus.encode(gained) ?: return true
        t.sendAudio(frame, opus.frameSamples)
        return true
    }

    /**
     * Copy the latest mixed remote PCM into [out] (cleared first). Returns samples written.
     * Prefer [onRemotePcm] when the platform mixes per-peer itself.
     */
    fun pollMixedPcm(out: ShortArray): Int {
        val n = minOf(out.size, mixBuf.size)
        synchronized(mixBuf) {
            System.arraycopy(mixBuf, 0, out, 0, n)
            mixBuf.fill(0)
        }
        return n
    }

    private suspend fun decodeLoop(opus: OpusCodec, frames: Channel<Pair<Long, ByteArray>>) {
        while (scope.isActive) {
            val (sender, frame) = frames.receiveCatching().getOrNull() ?: break
            if (peerMuted[sender] == true) continue
            val pcm = opus.decode(frame) ?: continue
            val vol = (peerVolume[sender] ?: 1f) * outputGain
            val scaled = if (vol == 1f) pcm else ShortArray(pcm.size) { i ->
                (pcm[i] * vol).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
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
    }
}

/** Map Syncplay `c17` participant id → numeric sender (connection id) when possible. */
fun participantSenderId(participantId: String): Long? {
    if (!participantId.startsWith('c')) return null
    return participantId.drop(1).toLongOrNull()
}
