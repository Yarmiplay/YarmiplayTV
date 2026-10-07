package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves relayed files to the player from 127.0.0.1, so it can seek in them like any HTTP file while the bytes
 * come from the [RelayCache], waiting for blocks still on their way. Paths carry a random key per file.
 */
class RelayProxy(private val scope: CoroutineScope) {
    private val routes = ConcurrentHashMap<String, RelayTransfer>()
    private val random = SecureRandom()
    private var server: ServerSocket? = null

    /** The player URL for [transfer]. */
    @Synchronized
    fun url(transfer: RelayTransfer): String {
        val socket = server ?: start()
        val key = routes.entries.firstOrNull { it.value === transfer }?.key
            ?: ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { routes[it] = transfer }
        val name = URLEncoder.encode(transfer.name, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:${socket.localPort}/relay/$key/$name"
    }

    fun remove(transfer: RelayTransfer) {
        routes.entries.removeAll { it.value === transfer }
    }

    fun isProxyUrl(url: String?): Boolean = url != null && server?.let { url.startsWith("http://127.0.0.1:${it.localPort}/relay/") } == true

    @Synchronized
    fun close() {
        runCatching { server?.close() }
        server = null
        routes.clear()
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch(Dispatchers.IO) { client.use { serve(it) } }
            }
        }, "relay-proxy").apply { isDaemon = true }.start()
        return socket
    }

    private suspend fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val out = socket.getOutputStream()
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return respond(out, 405, "Method Not Allowed")
            val key = parts[1].removePrefix("/relay/").substringBefore('/')
            val transfer = routes[key] ?: return respond(out, 404, "Not Found")
            val size = transfer.size
            val range = parseRange(headers["range"], size)
                ?: return respond(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
            val (start, end) = range
            val partial = headers["range"] != null
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${contentType(transfer.name)}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ${end - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (parts[0] == "HEAD") return out.flush()
            transfer.demand(start)
            val buffer = ByteArray(BUFFER)
            var pos = start
            while (pos <= end) {
                val available = transfer.awaitAvailable(pos, WAIT_MS)
                if (available <= 0) {
                    Logger.w(TAG, "Relay data for ${transfer.name} didn't arrive in time")
                    break
                }
                val n = transfer.cache.read(pos, buffer, minOf(available, end + 1 - pos, BUFFER.toLong()).toInt())
                if (n <= 0) break
                out.write(buffer, 0, n)
                pos += n
                transfer.demand(pos)
            }
            out.flush()
        } catch (_: SocketException) {
            // The player closed the connection, e.g. to seek.
        } catch (e: IOException) {
            Logger.d(TAG, "Relay proxy request ended: ${e.message}")
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, extra: String = "") {
        out.write("HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8_192) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        private const val TAG = "RelayProxy"
        private const val BUFFER = 256 * 1024
        /** Longer than the server's own 60 s, so its error reaches us first. */
        private const val WAIT_MS = 90_000L

        /** `[start, end]` for a single `bytes=` range (or the whole file without one); null when unsatisfiable. */
        fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            if (header == null) return if (size > 0) 0L to size - 1 else null
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            val range = when {
                a.isEmpty() -> b.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, size - it) to size - 1 }
                else -> a.toLongOrNull()?.let { s -> s to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)) }
            } ?: return null
            return range.takeIf { it.first in 0 until size && it.second >= it.first }
        }

        private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }
}
