package com.yarmiplaytv.voice

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Native `yv1` media on the Syncplay port: binary WebSocket messages to
 * `GET /yarmiplay/voice?t=<token>` (same path browsers use). No separate UDP/TCP 9000.
 */
class Yv1WsTransport(
    private val host: String,
    private val syncplayPort: Int,
    private val secure: Boolean,
    private val wsPath: String,
    private val tokenHex: String,
    private val token: Long,
    private val key: ByteArray,
    private val scope: CoroutineScope,
    private val onAudio: (sender: Long, opus: ByteArray, sequence: Int, timestamp: Int) -> Unit,
    private val client: OkHttpClient = defaultClient,
) {
    private val seq = AtomicInteger((Math.random() * Int.MAX_VALUE).toInt())
    private val timestampSamples = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val receivedAny = AtomicBoolean(false)
    private val sendLock = Mutex()
    private var socket: WebSocket? = null
    private var keepaliveJob: Job? = null

    val receivedInbound: Boolean get() = receivedAny.get()

    suspend fun start(): Boolean {
        if (closed.get() || syncplayPort <= 0) return false
        val path = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
        val scheme = if (secure) "wss" else "ws"
        val hostPart = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val url = "$scheme://$hostPart:$syncplayPort$path?t=$tokenHex"
        val opened = CompletableDeferred<Boolean>()
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!opened.isCompleted) opened.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handlePacket(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    println("Yv1WsTransport failure url=$url error=${t.message}")
                    if (!opened.isCompleted) opened.complete(false)
                    closed.set(true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.set(true)
                }
            },
        )
        socket = ws
        val ok = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } == true
        if (!ok || closed.get()) {
            runCatching { ws.cancel() }
            socket = null
            println("Yv1WsTransport open failed url=$url")
            return false
        }
        sendKeepalive()
        keepaliveJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(5_000)
                sendKeepalive()
            }
        }
        println("Yv1WsTransport open ok url=$url")
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        keepaliveJob?.cancel()
        keepaliveJob = null
        runCatching { sendBye() }
        runCatching { socket?.close(1000, null) }
        socket = null
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
        socket?.send(packet.toByteString())
    }

    private suspend fun send(type: Byte, plain: ByteArray, timestamp: Int) {
        val packet = Yv1Packet.seal(key, type, token, 0L, seq.getAndIncrement(), timestamp, plain) ?: return
        sendLock.withLock {
            if (closed.get()) return@withLock
            socket?.send(packet.toByteString())
        }
    }

    private fun handlePacket(raw: ByteArray) {
        val opened = Yv1Packet.open(key, raw) ?: return
        receivedAny.set(true)
        if (opened.type == Yv1Packet.TYPE_AUDIO && opened.plain.isNotEmpty()) {
            onAudio(opened.sender, opened.plain, opened.sequence, opened.timestamp)
        }
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WS
            .writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
