package com.yarmiplaytv.tracking

import com.yarmiplaytv.media.mal.MalClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Catches MyAnimeList's sign-in redirect on this device: `http://localhost:47821/mal-callback?code=…&state=…`, on
 * both loopback addresses (browsers try "localhost" as IPv6 and IPv4). Only this device can reach it, and it stops
 * after the first answer with the right [state][await].
 */
class MalCallbackServer(private val port: Int = MalClient.CALLBACK_PORT) {

    /**
     * Waits (up to [timeoutMs]) for the redirect with [state] and returns its code.
     */
    suspend fun await(state: String, timeoutMs: Long = 5 * 60_000L): String = withTimeout(timeoutMs) {
        val sockets = listOf("127.0.0.1", "::1").mapNotNull { host ->
            runCatching { ServerSocket(port, 8, InetAddress.getByName(host)).apply { soTimeout = 500 } }.getOrNull()
        }
        if (sockets.isEmpty()) throw IOException("Port $port is in use; close the other app that uses it and try again")
        val result = CompletableDeferred<String>()
        try {
            coroutineScope {
                val listeners = sockets.map { socket -> launch(Dispatchers.IO) { listen(socket, state, result) } }
                val code = result.await()
                listeners.forEach { it.cancel() }
                code
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun listen(server: ServerSocket, state: String, result: CompletableDeferred<String>) = coroutineScope {
        while (isActive && !result.isCompleted) {
            val client = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            // Anything on this device can connect (a web page too): one bad request is answered and forgotten, and
            // the server keeps waiting for MyAnimeList's.
            client.use { socket -> runCatching { handle(socket, state, result) } }
        }
    }

    private fun handle(socket: Socket, state: String, result: CompletableDeferred<String>) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        var unread = false
        val (status, page) = try {
            answer(readRequestTarget(input), state, result)
        } catch (_: BadRequest) {
            unread = true
            400 to page("Bad request")
        } catch (_: IllegalArgumentException) {
            // Malformed percent-encoding in the query.
            400 to page("Bad request")
        }
        val body = page.toByteArray()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        }
        // Closing with the rest of an oversized request unread would reset the connection before the answer is read.
        if (unread) runCatching { socket.shutdownOutput(); socket.soTimeout = 1_000; drain(input) }
    }

    /** Reads and drops what's left of a request, a little and briefly at most. */
    private fun drain(input: InputStream) {
        val buffer = ByteArray(8 * 1024)
        var left = 256 * 1024
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) break
            left -= n
        }
    }

    private fun answer(target: String, state: String, result: CompletableDeferred<String>): Pair<Int, String> {
        if (!target.startsWith("/mal-callback")) return 404 to page("Not found")
        val query = parseQuery(target.substringAfter('?', ""))
        return when {
            query["state"] != state -> 400 to page("This sign-in link is out of date. Start signing in again in YarmiplayTV.")
            query["error"] != null -> {
                result.completeExceptionally(IOException("MyAnimeList sign-in was cancelled (${query["error"]})"))
                200 to page("Sign-in cancelled. You can close this tab.")
            }
            query["code"].isNullOrEmpty() -> 400 to page("MyAnimeList didn't send a sign-in code. Try again.")
            else -> {
                result.complete(query.getValue("code")!!)
                200 to page("Signed in to MyAnimeList. You can close this tab and go back to YarmiplayTV.")
            }
        }
    }

    private class BadRequest : Exception()

    private fun page(message: String): String {
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>YarmiplayTV</title></head><body style="font-family:sans-serif;padding:2em;background:#111;color:#eee">""" +
            "<p>${message.replace("<", "&lt;")}</p></body></html>"
    }

    companion object {
        /** The longest request or header line read, in bytes. */
        private const val MAX_LINE = 8 * 1024
        /** The most header lines read after the request line. */
        private const val MAX_HEADERS = 100

        /**
         * The target of the request (`/mal-callback?…`) from its first line; the header lines after it are read and
         * dropped. Empty when the request can't be read; [BadRequest] for a line over [MAX_LINE] bytes or more than
         * [MAX_HEADERS] header lines.
         */
        private fun readRequestTarget(input: InputStream): String {
            val requestLine = try {
                readLine(input) ?: return ""
            } catch (_: IOException) {
                return ""
            }
            try {
                var headers = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (++headers > MAX_HEADERS) throw BadRequest()
                }
            } catch (_: IOException) {
                // The request line is all that's needed.
            }
            return requestLine.split(' ').getOrNull(1).orEmpty()
        }

        /** One line without its line break; null at the end of the stream; [BadRequest] when over [MAX_LINE] bytes. */
        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (line.size() >= MAX_LINE) throw BadRequest()
                line.write(b)
            }
            return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
        }

        fun parseQuery(query: String): Map<String, String?> = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}
