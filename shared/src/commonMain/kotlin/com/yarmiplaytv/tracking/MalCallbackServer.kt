package com.yarmiplaytv.tracking

import com.yarmiplaytv.media.mal.MalClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
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
            client.use { socket ->
                socket.soTimeout = 5_000
                val requestLine = runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val query = parseQuery(target.substringAfter('?', ""))
                val (status, page) = when {
                    !target.startsWith("/mal-callback") -> 404 to page("Not found")
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
                val body = page.toByteArray()
                val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                runCatching {
                    socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            }
        }
    }

    private fun page(message: String): String {
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>YarmiplayTV</title></head><body style="font-family:sans-serif;padding:2em;background:#111;color:#eee">""" +
            "<p>${message.replace("<", "&lt;")}</p></body></html>"
    }

    companion object {
        fun parseQuery(query: String): Map<String, String?> = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}
