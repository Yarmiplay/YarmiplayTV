package com.yarmiplaytv.syncplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opens a connection to `host:port` whose TLS certificate is verified as `tlsName`. */
typealias TransportFactory = (host: String, port: Int, tlsName: String) -> Transport

/** Line-oriented connection to a Syncplay server. */
interface Transport : Closeable {
    val isTls: Boolean
    /** The IP address the connection reached, once connected (null when unknown, e.g. in tests). */
    val remoteAddress: String? get() = null
    suspend fun connect()
    /** Returns null when the connection is closed. */
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
    /** Upgrade the current connection to TLS (Syncplay's STARTTLS). */
    suspend fun startTls()
}

/**
 * Dials [host] and, on STARTTLS, verifies the server's certificate as [tlsName] (SNI and hostname check). That's
 * [host] itself unless the server is reached at another address than the name on its certificate, e.g. its LAN
 * address at home. The trust store is always [sslSocketFactory]'s, the platform default outside tests.
 */
class SocketTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val tlsName: String = host,
) : Transport {
    @Volatile private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
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
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
    }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        reader?.readLine()
    }

    override suspend fun writeLine(line: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val w = writer ?: error("Not connected")
            w.write(line)
            w.write("\r\n")
            w.flush()
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
    }
}
