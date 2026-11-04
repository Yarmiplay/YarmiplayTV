package com.yarmiplaytv.syncplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opens a connection to `host:port` whose TLS certificate is verified as `tlsName`. */
typealias TransportFactory = (host: String, port: Int, tlsName: String) -> Transport

/**
 * One record from the Syncplay byte stream (VOICE_PROTOCOL §6.0.1).
 * JSON lines and voice frames share the same TCP/TLS connection when the client opts in with `mux`.
 */
sealed class SyncplayInbound {
    data class Line(val text: String) : SyncplayInbound()
    data class VoicePacket(val bytes: ByteArray) : SyncplayInbound() {
        override fun equals(other: Any?): Boolean =
            other is VoicePacket && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** Marker byte for a Syncplay-mux voice record (`0x1E` Record Separator). */
const val VOICE_MUX_MARKER: Int = 0x1E
const val VOICE_MUX_MIN_PACKET: Int = 56
const val VOICE_MUX_MAX_PACKET: Int = 1200
const val SYNCPLAY_MAX_LINE: Int = 65_536

/** Line-oriented (and optionally muxed) connection to a Syncplay server. */
interface Transport : Closeable {
    val isTls: Boolean
    /** The IP address the connection reached, once connected (null when unknown, e.g. in tests). */
    val remoteAddress: String? get() = null
    suspend fun connect()
    /**
     * Next Syncplay record. Default wraps [readLine] for scripted tests.
     * Production [SocketTransport] demuxes JSON lines and `0x1E` voice frames.
     */
    suspend fun readInbound(): SyncplayInbound? = readLine()?.let { SyncplayInbound.Line(it) }
    /** Returns null when the connection is closed. Prefer [readInbound] when mux may be active. */
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
    /**
     * Write one yv1 packet as a Syncplay-mux voice record (`0x1E` + BE u16 length + packet).
     * Unsupported on transports that only speak JSON lines.
     */
    suspend fun writeVoicePacket(packet: ByteArray) {
        throw IOException("This Syncplay connection does not support voice mux frames")
    }
    /** Upgrade the current connection to TLS (Syncplay's STARTTLS). */
    suspend fun startTls()
}

/**
 * Dials [host] and, on STARTTLS, verifies the server's certificate as [tlsName] (SNI and hostname check). That's
 * [host] itself unless the server is reached at another address than the name on its certificate, e.g. its LAN
 * address at home. The trust store is always [sslSocketFactory]'s, the platform default outside tests.
 *
 * Reads demux JSON lines and Syncplay-mux voice records on the same byte stream (VOICE_PROTOCOL §6.0.1).
 */
class SocketTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val tlsName: String = host,
) : Transport {
    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Mutex()

    override var isTls: Boolean = false
        private set

    override val remoteAddress: String? get() = socket?.inetAddress?.hostAddress?.substringBefore('%')

    /**
     * Tries every address of [host], alternating IPv6 and IPv4: some servers (syncplay.pl among them) publish an
     * IPv6 address but refuse connections on it, and mobile networks resolve IPv6 first.
     */
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val addresses = interleaved(InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]")).toList())
            val timeoutMs = (connectTimeoutMs / addresses.size).coerceAtLeast(minOf(connectTimeoutMs, 3_000))
            var failure: IOException? = null
            for (address in addresses) {
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(address, port), timeoutMs)
                    attach(s)
                    return@withContext
                } catch (e: IOException) {
                    runCatching { s.close() }
                    failure?.let(e::addSuppressed)
                    failure = e
                }
            }
            throw failure ?: IOException("No address for $host")
        }
    }

    private fun interleaved(addresses: List<InetAddress>): List<InetAddress> {
        val preferV6 = addresses.firstOrNull() is Inet6Address
        val (first, second) = addresses.partition { (it is Inet6Address) == preferV6 }
        return List(maxOf(first.size, second.size)) { i -> listOfNotNull(first.getOrNull(i), second.getOrNull(i)) }.flatten()
    }

    private fun attach(s: Socket) {
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override suspend fun readLine(): String? {
        while (true) {
            when (val inbound = readInbound()) {
                null -> return null
                is SyncplayInbound.Line -> return inbound.text
                is SyncplayInbound.VoicePacket -> continue // drop unexpected voice when caller only wants lines
            }
        }
    }

    override suspend fun readInbound(): SyncplayInbound? = withContext(Dispatchers.IO) {
        val inp = input ?: return@withContext null
        while (true) {
            val first = readByteOrNull(inp) ?: return@withContext null
            when {
                first == VOICE_MUX_MARKER -> {
                    val hi = readByteOrNull(inp) ?: return@withContext null
                    val lo = readByteOrNull(inp) ?: return@withContext null
                    val n = (hi shl 8) or lo
                    if (n !in VOICE_MUX_MIN_PACKET..VOICE_MUX_MAX_PACKET) {
                        throw IOException("Invalid Syncplay voice frame length $n")
                    }
                    val packet = ByteArray(n)
                    readFully(inp, packet)
                    return@withContext SyncplayInbound.VoicePacket(packet)
                }
                first == '{'.code || first == 0x09 || first == 0x0A || first == 0x0D || first == 0x20 -> {
                    val line = readJsonLine(inp, first)
                    if (line == null) return@withContext null
                    if (line.isEmpty()) continue
                    if (line.length > SYNCPLAY_MAX_LINE) throw IOException("Syncplay line too long")
                    return@withContext SyncplayInbound.Line(line)
                }
                else -> throw IOException("Unexpected Syncplay byte 0x${first.toString(16)}")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun writeLine(line: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: error("Not connected")
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write(CRLF)
            out.flush()
        }
    }

    override suspend fun writeVoicePacket(packet: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: error("Not connected")
            if (packet.size !in VOICE_MUX_MIN_PACKET..VOICE_MUX_MAX_PACKET) {
                throw IOException("Voice packet size ${packet.size} out of range")
            }
            out.write(VOICE_MUX_MARKER)
            out.write((packet.size ushr 8) and 0xff)
            out.write(packet.size and 0xff)
            out.write(packet)
            out.flush()
        }
    }

    override suspend fun startTls() = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val plain = socket ?: error("Not connected")
            val tls = sslSocketFactory.createSocket(plain, tlsName, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.useClientMode = true
            tls.startHandshake()
            attach(tls)
            isTls = true
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        fun readByteOrNull(inp: InputStream): Int? {
            val b = inp.read()
            return if (b < 0) null else b
        }

        fun readFully(inp: InputStream, dest: ByteArray) {
            var off = 0
            while (off < dest.size) {
                val n = inp.read(dest, off, dest.size - off)
                if (n < 0) throw EOFException("Unexpected end of Syncplay stream")
                off += n
            }
        }
    }
}

/**
 * Read through the next LF, starting with the already-read byte [first]; return the trimmed line without the newline,
 * or null at end of stream with nothing read. Bytes are collected first and decoded once as UTF-8: a character outside
 * ASCII spans several bytes, so decoding byte by byte turns "·" into "Â·".
 */
internal fun readJsonLine(inp: InputStream, first: Int): String? {
    val buf = ByteArrayOutputStream()
    var c = first
    while (c != 0x0A) {
        if (c != 0x0D) buf.write(c)
        c = inp.read()
        if (c < 0) {
            if (buf.size() == 0) return null
            break
        }
    }
    return String(buf.toByteArray(), Charsets.UTF_8).trim()
}
