package com.yarmiplaytv.tracking

import com.yarmiplaytv.media.anilist.AniListClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Single-use localhost HTTP callback server on `127.0.0.1:47822` for desktop AniList Implicit Grant sign-in.
 * Step 1: serves a tiny HTML page that reads `window.location.hash` (`#access_token=...`), clears it from browser
 * history with `history.replaceState`, and POSTs the fragment back to `/anilist-token` on localhost.
 * Step 2: receives the POSTed fragment and closes the socket immediately.
 */
object AniListCallbackServer {
    suspend fun awaitFragment(timeoutMs: Long = 180_000L): String? = withContext(Dispatchers.IO) {
        val server = ServerSocket(AniListClient.CALLBACK_PORT, 4, InetAddress.getByName("127.0.0.1"))
        val deadline = System.currentTimeMillis() + timeoutMs
        server.use { ss ->
            while (System.currentTimeMillis() < deadline) {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
                ss.soTimeout = left
                val socket = try {
                    ss.accept()
                } catch (_: SocketTimeoutException) {
                    return@withContext null
                }
                socket.use { s ->
                    s.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return@use
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val out = s.getOutputStream()
                    if (requestLine.startsWith("POST /anilist-token")) {
                        val buf = CharArray(contentLength.coerceIn(0, 8192))
                        var read = 0
                        while (read < buf.size) {
                            val n = reader.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        val fragment = String(buf, 0, read)
                        writeHttp(out, "200 OK", "text/plain", "ok")
                        if (fragment.contains("access_token=")) return@withContext fragment
                    } else {
                        writeHttp(out, "200 OK", "text/html; charset=utf-8", CALLBACK_HTML)
                    }
                }
            }
            null
        }
    }

    private fun writeHttp(out: java.io.OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private const val CALLBACK_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><title>YarmiplayTV · AniList</title></head>
<body style="font-family:system-ui;background:#0E1116;color:#E6E9EF;padding:2.5em">
<h2 id="h">Finishing AniList sign-in…</h2>
<p id="p">You can close this tab once done.</p>
<script>
const h = location.hash || "";
if (history.replaceState) history.replaceState(null, "", location.pathname);
fetch("/anilist-token", { method: "POST", body: h }).then(() => {
  document.getElementById("h").textContent = h.includes("access_token=") ? "Signed in to AniList" : "Sign-in cancelled";
  document.getElementById("p").textContent = "You can close this tab and return to YarmiplayTV.";
}).catch(() => {
  document.getElementById("h").textContent = "Couldn't reach YarmiplayTV";
});
</script></body></html>"""
}
