package com.yarmiplaytv.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** Anything on this device can talk to the MyAnimeList sign-in callback while it waits; bad requests don't end it. */
class MalCallbackServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }

    @After
    fun tearDown() = scope.cancel()

    /** Sends [request] as it is and returns the response's status code. */
    private fun send(request: String): Int {
        repeat(50) {
            runCatching {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 10_000
                    socket.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
                    val status = socket.getInputStream().bufferedReader().readLine() ?: error("no answer")
                    return status.split(' ')[1].toInt()
                }
            }
            Thread.sleep(50)
        }
        error("no listener")
    }

    private fun get(target: String, headers: String = "Host: localhost\r\n") = send("GET $target HTTP/1.1\r\n$headers\r\n")

    @Test
    fun `bad requests are answered and the sign-in keeps waiting for the code`() = runBlocking {
        val callback = MalCallbackServer(port)
        val code = scope.async { callback.await("good-state", timeoutMs = 20_000) }

        assertEquals("bad percent-encoding", 400, get("/mal-callback?%zz"))
        assertEquals("bad percent-encoding in a value", 400, get("/mal-callback?code=%e&state=good-state"))
        assertEquals("over-long request line", 400, get("/mal-callback?code=" + "x".repeat(9_000) + "&state=good-state"))
        val manyHeaders = (1..150).joinToString("") { "X-Header-$it: v\r\n" }
        assertEquals("too many headers", 400, get("/mal-callback?code=x&state=good-state", manyHeaders))
        assertEquals("over-long header line", 400, get("/mal-callback?code=x&state=good-state", "X-Long: ${"y".repeat(9_000)}\r\n"))
        assertEquals(404, get("/elsewhere?%zz"))
        assertEquals(400, get("/mal-callback?code=x&state=bad-state"))
        assertFalse("still waiting", code.isCompleted)

        assertEquals(200, get("/mal-callback?code=the%20code&state=good-state"))
        assertEquals("the code", code.await())
    }
}
