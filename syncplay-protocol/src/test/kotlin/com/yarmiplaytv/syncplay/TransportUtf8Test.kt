package com.yarmiplaytv.syncplay

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Syncplay lines are UTF-8 on the wire; multi-byte characters must come out whole, not as Latin-1 per byte. */
class TransportUtf8Test {
    private fun read(bytes: ByteArray): String? {
        val inp = ByteArrayInputStream(bytes)
        return readJsonLine(inp, inp.read())
    }

    @Test
    fun `middle dot and ellipsis decode as UTF-8`() {
        val line = """{"Chat":{"message":"Ep 1 · Part 2…"}}"""
        assertEquals(line, read((line + "\r\n").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `four-byte characters and LF-only endings decode`() {
        val line = """{"Set":{"playlistChange":{"files":["Café – 🎬 ·.mkv"]}}}"""
        assertEquals(line, read((line + "\n").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `last line without newline is returned and a bare CR at end of stream is null`() {
        assertEquals("""{"a":"…"}""", read("""{"a":"…"}""".toByteArray(Charsets.UTF_8)))
        assertNull(read(byteArrayOf(0x0D)))
    }

    @Test
    fun `socket transport keeps non-ASCII intact across lines`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val accepted = pool.submit<Socket> { server.accept() }
        val client = SocketTransport("127.0.0.1", server.localPort, connectTimeoutMs = 3_000)
        client.connect()
        val peer = accepted.get(3, TimeUnit.SECONDS)
        try {
            val first = """{"Chat":{"username":"Noire","message":"watch <n> · queue <n…> · queue all · more for the next page"}}"""
            val second = """{"Set":{"playlistChange":{"files":["Tensei Kizoku · 30 …mkv"]}}}"""
            peer.getOutputStream().apply {
                write((first + "\r\n" + second + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            assertEquals(SyncplayInbound.Line(first), client.readInbound())
            assertEquals(SyncplayInbound.Line(second), client.readInbound())
        } finally {
            client.close()
            peer.close()
            server.close()
            pool.shutdownNow()
        }
    }
}
