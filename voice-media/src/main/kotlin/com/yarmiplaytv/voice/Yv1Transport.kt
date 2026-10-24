package com.yarmiplaytv.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Native `yv1` media path: prefer UDP, fall back to fingerprint-pinned TLS over TCP.
 * Invokes [onAudio] with each decrypted Opus frame (sender = server connection id).
 */
class Yv1Transport(
    private val host: String,
    private val udpPort: Int,
    private val tcpPort: Int,
    private val token: Long,
    private val key: ByteArray,
    private val fingerprintHex: String,
    private val scope: CoroutineScope,
    private val forceTcp: Boolean = false,
    private val onAudio: (sender: Long, opus: ByteArray, sequence: Int, timestamp: Int) -> Unit,
) {
    private val seq = AtomicInteger((Math.random() * Int.MAX_VALUE).toInt())
    private val timestampSamples = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val receivedAny = AtomicBoolean(false)
    private val useTcp = AtomicBoolean(false)
    private val sendLock = Mutex()

    private var udp: DatagramSocket? = null
    private var udpAddress: InetAddress? = null
    private var tcpSocket: SSLSocket? = null
    private var tcpOut: DataOutputStream? = null
    private var jobs = mutableListOf<Job>()

    /** True once send/receive is on the TLS TCP path. */
    val usingTcp: Boolean get() = useTcp.get()

    /** True after any inbound media/keepalive packet was decrypted. */
    val receivedInbound: Boolean get() = receivedAny.get()

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext false
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext false
        udpAddress = addr
        if (forceTcp) {
            openTcpFallback()
            if (!useTcp.get()) return@withContext false
            jobs += scope.launch {
                while (isActive && !closed.get()) {
                    delay(5_000)
                    sendKeepalive()
                }
            }
            return@withContext true
        }
        val sock = DatagramSocket().also { it.soTimeout = 0 }
        udp = sock
        jobs += scope.launch(Dispatchers.IO) { udpReceiveLoop(sock) }
        sendKeepalive()
        jobs += scope.launch {
            while (isActive && !closed.get()) {
                delay(5_000)
                sendKeepalive()
            }
        }
        jobs += scope.launch {
            delay(UDP_FALLBACK_MS)
            if (!closed.get() && !receivedAny.get()) {
                openTcpFallback()
            }
        }
        true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { sendBye() }
        runCatching { udp?.close() }
        udp = null
        runCatching { tcpSocket?.close() }
        tcpSocket = null
        tcpOut = null
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
        // Best-effort; may run after coroutines are cancelled.
        val packet = Yv1Packet.seal(key, Yv1Packet.TYPE_BYE, token, 0L, seq.getAndIncrement(), timestampSamples.get(), ByteArray(0))
            ?: return
        if (useTcp.get()) {
            runCatching { writeTcp(packet) }
        } else {
            runCatching { writeUdp(packet) }
        }
    }

    private suspend fun send(type: Byte, plain: ByteArray, timestamp: Int) {
        val packet = Yv1Packet.seal(key, type, token, 0L, seq.getAndIncrement(), timestamp, plain) ?: return
        sendLock.withLock {
            if (closed.get()) return@withLock
            if (useTcp.get()) {
                withContext(Dispatchers.IO) { writeTcp(packet) }
            } else {
                withContext(Dispatchers.IO) { writeUdp(packet) }
            }
        }
    }

    private fun writeUdp(packet: ByteArray) {
        val sock = udp ?: return
        val addr = udpAddress ?: return
        sock.send(DatagramPacket(packet, packet.size, addr, udpPort))
    }

    private fun writeTcp(packet: ByteArray) {
        val out = tcpOut ?: return
        if (packet.size !in Yv1Packet.MIN_PACKET..Yv1Packet.MAX_PACKET) return
        out.writeShort(packet.size)
        out.write(packet)
        out.flush()
    }

    private fun udpReceiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(Yv1Packet.MAX_PACKET)
        while (!closed.get() && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                handlePacket(buf.copyOf(packet.length))
            } catch (_: Exception) {
                if (closed.get() || sock.isClosed) break
            }
        }
    }

    private fun handlePacket(raw: ByteArray) {
        val opened = Yv1Packet.open(key, raw) ?: return
        receivedAny.set(true)
        when (opened.type) {
            Yv1Packet.TYPE_AUDIO -> {
                if (opened.plain.isNotEmpty()) {
                    onAudio(opened.sender, opened.plain, opened.sequence, opened.timestamp)
                }
            }
            else -> Unit // keepalive/bye from server are not expected
        }
    }

    private suspend fun openTcpFallback() = withContext(Dispatchers.IO) {
        if (closed.get() || useTcp.get()) return@withContext
        val factory = pinnedSocketFactory(fingerprintHex) ?: return@withContext
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, tcpPort), 5_000)
            val ssl = factory.createSocket(raw, host, tcpPort, true) as SSLSocket
            // Pin by fingerprint; name on the cert is voice.local.
            ssl.useClientMode = true
            ssl.startHandshake()
            val peer = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            if (peer == null || !fingerprintMatches(peer, fingerprintHex)) {
                ssl.close()
                return@withContext
            }
            tcpSocket = ssl
            tcpOut = DataOutputStream(ssl.outputStream)
            useTcp.set(true)
            jobs += scope.launch(Dispatchers.IO) { tcpReceiveLoop(ssl) }
            sendKeepalive()
        } catch (_: Exception) {
            runCatching { raw.close() }
        }
    }

    private fun tcpReceiveLoop(ssl: SSLSocket) {
        val input = DataInputStream(ssl.inputStream)
        while (!closed.get() && !ssl.isClosed) {
            try {
                val len = input.readUnsignedShort()
                if (len !in Yv1Packet.MIN_PACKET..Yv1Packet.MAX_PACKET) break
                val buf = ByteArray(len)
                input.readFully(buf)
                handlePacket(buf)
            } catch (_: Exception) {
                break
            }
        }
    }

    companion object {
        /** Prefer UDP; open TLS TCP if nothing arrives within this window. */
        const val UDP_FALLBACK_MS = 3_000L

        fun fingerprintMatches(cert: X509Certificate, expectedHex: String): Boolean {
            val der = cert.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val hex = digest.joinToString("") { b -> "%02x".format(b) }
            return hex.equals(expectedHex.trim().lowercase(), ignoreCase = false) ||
                hex.equals(expectedHex.trim(), ignoreCase = true)
        }

        fun pinnedSocketFactory(expectedFingerprint: String): SSLSocketFactory? {
            if (expectedFingerprint.isBlank()) return null
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val cert = chain?.firstOrNull() ?: throw javax.net.ssl.SSLException("no certificate")
                    if (!fingerprintMatches(cert, expectedFingerprint)) {
                        throw javax.net.ssl.SSLException("voice certificate fingerprint mismatch")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), java.security.SecureRandom())
            return ctx.socketFactory
        }
    }
}
