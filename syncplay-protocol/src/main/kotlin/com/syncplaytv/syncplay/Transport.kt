package com.syncplaytv.syncplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Line-oriented connection to a Syncplay server. */
interface Transport : Closeable {
    val isTls: Boolean
    suspend fun connect()
    /** Returns null when the connection is closed. */
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
    /** Upgrade the current connection to TLS (Syncplay's STARTTLS). */
    suspend fun startTls()
}

class SocketTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : Transport {
    @Volatile private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Mutex()

    override var isTls: Boolean = false
        private set

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        attach(s)
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
            val tls = sslSocketFactory.createSocket(plain, host, port, true) as SSLSocket
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
