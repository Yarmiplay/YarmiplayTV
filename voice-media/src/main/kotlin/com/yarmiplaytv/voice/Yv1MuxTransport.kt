package com.yarmiplaytv.voice

import com.yarmiplaytv.syncplay.SyncplayVoiceMux
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Native `yv1` media on the Syncplay connection (VOICE_PROTOCOL §6.0.1):
 * each packet is written as `0x1E` + big-endian u16 length + yv1 bytes on the same
 * TCP/TLS stream as Syncplay JSON (or as a binary WebSocket message on `/yarmiplay/ws`).
 */
class Yv1MuxTransport(
    private val mux: SyncplayVoiceMux,
    private val token: Long,
    private val key: ByteArray,
    private val scope: CoroutineScope,
    private val onAudio: (sender: Long, opus: ByteArray, sequence: Int, timestamp: Int) -> Unit,
) {
    private val seq = AtomicInteger((Math.random() * Int.MAX_VALUE).toInt())
    private val timestampSamples = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val receivedAny = AtomicBoolean(false)
    private val sendLock = Mutex()
    private var keepaliveJob: Job? = null

    val receivedInbound: Boolean get() = receivedAny.get()

    fun start(): Boolean {
        if (closed.get()) return false
        mux.setOnPacket { packet -> handlePacket(packet) }
        keepaliveJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(5_000)
                runCatching { sendKeepalive() }
            }
        }
        println("Yv1MuxTransport open ok (Syncplay mux)")
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        keepaliveJob?.cancel()
        keepaliveJob = null
        mux.setOnPacket(null)
        runCatching { sendBye() }
    }

    suspend fun sendAudio(opus: ByteArray, frameSamples: Int = 960) {
        if (opus.isEmpty() || closed.get()) return
        val ts = timestampSamples.getAndAdd(frameSamples)
        send(Yv1Packet.TYPE_AUDIO, opus, ts)
    }

    private suspend fun sendKeepalive() {
        send(Yv1Packet.TYPE_KEEPALIVE, byteArrayOf(0x00), timestampSamples.get())
    }

    private fun sendBye() {
        val packet = Yv1Packet.seal(
            key,
            Yv1Packet.TYPE_BYE,
            token,
            0L,
            seq.getAndIncrement(),
            timestampSamples.get(),
            ByteArray(0),
        ) ?: return
        mux.send(packet)
    }

    private suspend fun send(type: Byte, plain: ByteArray, timestamp: Int) {
        val packet = Yv1Packet.seal(key, type, token, 0L, seq.getAndIncrement(), timestamp, plain) ?: return
        sendLock.withLock {
            if (closed.get()) return@withLock
            mux.send(packet)
        }
    }

    private fun handlePacket(raw: ByteArray) {
        if (closed.get()) return
        val opened = Yv1Packet.open(key, raw) ?: return
        receivedAny.set(true)
        if (opened.type == Yv1Packet.TYPE_AUDIO && opened.plain.isNotEmpty()) {
            onAudio(opened.sender, opened.plain, opened.sequence, opened.timestamp)
        }
    }
}
