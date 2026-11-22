package com.yarmiplaytv.network

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves exactly one file at a time on 127.0.0.1, so the player can seek with HTTP Range requests.
 * Nothing else can connect, and a request that doesn't name the current token gets nothing.
 */
internal class LoopbackStream {
    private val server = ServerSocket()
    private val random = SecureRandom()
    private val current = AtomicReference<Published?>(null)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "network-stream").apply { isDaemon = true }
    }

    val port: Int

    init {
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        port = server.localPort
        check(server.inetAddress.isLoopbackAddress) { "The stream is not bound to localhost" }
        val accept = Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                workers.execute {
                    try {
                        handle(socket)
                    } catch (_: Exception) {
                        // A client that went away mid-read is the player seeking or stopping.
                    } finally {
                        socket.close()
                    }
                }
            }
        }, "network-stream-accept")
        accept.isDaemon = true
        accept.start()
    }

    fun publish(fileName: String, size: Long, reader: RemoteReader, release: () -> Unit = {}): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "video" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val previous = current.getAndSet(Published(token, safeName, size.coerceAtLeast(0L), reader, release))
        previous?.release?.invoke()
        return "http://127.0.0.1:$port/t/$token/$safeName"
    }

    fun release() {
        current.getAndSet(null)?.release?.invoke()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream()
        val header = readHeader(input) ?: return
        val lines = header.split("\r\n")
        val request = lines.firstOrNull()?.split(" ") ?: return
        if (request.size < 2) return
        val method = request[0]
        if (method != "GET" && method != "HEAD") {
            writeStatus(socket, 405, "Method Not Allowed", 0, null)
            return
        }
        val path = request[1].substringBefore('?')
        val published = current.get()
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (published == null || parts.size != 3 || parts[0] != "t" || parts[1] != published.token || parts[2] != published.name) {
            writeStatus(socket, 404, "Not Found", 0, null)
            return
        }
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val (start, end) = parseRange(range, published.size) ?: run {
            writeStatus(socket, 416, "Range Not Satisfiable", 0, "bytes */${published.size}")
            return
        }
        val length = end - start + 1
        val status = if (range == null) 200 else 206
        val contentRange = if (range == null) null else "bytes $start-$end/${published.size}"
        writeStatus(socket, status, if (status == 200) "OK" else "Partial Content", length, contentRange)
        if (method == "HEAD" || length == 0L) return
        val output = BufferedOutputStream(socket.getOutputStream())
        val buffer = ByteArray(64 * 1024)
        var offset = start
        var left = length
        while (left > 0) {
            val want = minOf(buffer.size.toLong(), left).toInt()
            val read = synchronized(published.reader) { published.reader.read(offset, buffer, want) }
            if (read <= 0) break
            output.write(buffer, 0, read)
            offset += read
            left -= read
        }
        output.flush()
    }

    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        if (size < 0) return null
        if (header == null) return 0L to (size - 1).coerceAtLeast(0L)
        val match = Regex("""bytes=(\d*)-(\d*)""").matchEntire(header) ?: return null
        val from = match.groupValues[1]
        val to = match.groupValues[2]
        val start = when {
            from.isNotEmpty() -> from.toLongOrNull() ?: return null
            to.isNotEmpty() -> (size - (to.toLongOrNull() ?: return null)).coerceAtLeast(0L)
            else -> return null
        }
        if (start >= size && size > 0) return null
        val end = if (from.isNotEmpty() && to.isNotEmpty()) to.toLong() else size - 1
        if (end < start) return null
        return start to end.coerceAtMost(size - 1)
    }

    private fun writeStatus(socket: Socket, code: Int, text: String, length: Long, contentRange: String?) {
        val range = if (contentRange == null) "" else "Content-Range: $contentRange\r\n"
        val body = "HTTP/1.1 $code $text\r\nAccept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\n${range}Connection: close\r\n\r\n"
        socket.getOutputStream().write(body.toByteArray(Charsets.ISO_8859_1))
    }

    private fun readHeader(input: InputStream): String? {
        val bytes = ArrayList<Byte>(256)
        while (bytes.size < MAX_HEADER) {
            val next = input.read()
            if (next < 0) return null
            bytes += next.toByte()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
                bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
            ) {
                return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }
        }
        return null
    }

    private class Published(
        val token: String,
        val name: String,
        val size: Long,
        val reader: RemoteReader,
        val release: () -> Unit,
    )

    private companion object {
        const val MAX_HEADER = 8 * 1024
    }
}
