package com.yarmiplaytv.syncplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Secrets stay out of the log, and a misbehaving server can't make the client hang or fill its memory. */
class HardeningTest {
    // --- Log redaction ----------------------------------------------------------------------------

    @Test
    fun `redact hides every secret field and leaves the rest of the line as it was`() {
        val md5 = "5f4dcc3b5aa765d61d8327deb882cf99"
        assertEquals(
            """{"Hello":{"username":"ana","password":"…","room":{"name":"movie night"}}}""",
            SyncplayClient.redact("""{"Hello":{"username":"ana","password":"$md5","room":{"name":"movie night"}}}"""),
        )
        assertEquals(
            """{"Set":{"controllerAuth":{"room":"+night:ABCDEF012345","password":"…"}}}""",
            SyncplayClient.redact("""{"Set":{"controllerAuth":{"room":"+night:ABCDEF012345","password":"AB-123-456"}}}"""),
        )
        assertEquals(
            """{"Set":{"newControlledRoom":{"password":"…","roomName":"+night:ABCDEF012345"}}}""",
            SyncplayClient.redact("""{"Set":{"newControlledRoom":{"password":"AB-123-456","roomName":"+night:ABCDEF012345"}}}"""),
        )
        assertEquals(
            """{"yarmiplay":{"voiceJoined":{"participantId":"p1","token":"…","key":"…","lobbyId":"42","lobbySecret":"…"}}}""",
            SyncplayClient.redact(
                """{"yarmiplay":{"voiceJoined":{"participantId":"p1","token":"0123456789abcdef","key":"c2VjcmV0a2V5","lobbyId":"42","lobbySecret":"s3cr3t"}}}""",
            ),
        )
        assertEquals("""{"session":{"token" : "…"}}""", SyncplayClient.redact("""{"session":{"token" : "abc"}}"""))
    }

    @Test
    fun `redact hides a value with escaped quotes whole`() {
        val redacted = SyncplayClient.redact("""{"Set":{"controllerAuth":{"room":"r","password":"a\"b\\\"c"}}}""")
        assertEquals("""{"Set":{"controllerAuth":{"room":"r","password":"…"}}}""", redacted)
    }

    @Test
    fun `lines without secrets are logged unchanged`() {
        val lines = listOf(
            """{"State":{"ping":{"latencyCalculation":1.5},"playstate":{"position":12.0,"paused":false}}}""",
            """{"Chat":{"username":"ana","message":"the password is in the key"}}""",
            """{"Set":{"user":{"ana":{"room":{"name":"movie night"},"file":{"name":"a.mkv","size":1,"duration":2}}}}}""",
            """{"List":{"movie night":{"ana":{"position":0,"file":{},"controller":false}}}}""",
            "",
        )
        lines.forEach { assertEquals(it, SyncplayClient.redact(it)) }
    }

    // --- Reading lines ------------------------------------------------------------------------------

    @Test
    fun `a line that never ends stops being read past the limit`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'a'.code
            }
        }
        try {
            readJsonLine(endless, '{'.code)
            fail("an endless line should be refused")
        } catch (e: IOException) {
            assertEquals("Syncplay line too long", e.message)
        }
        assertTrue("read $served bytes", served <= 4L * SYNCPLAY_MAX_LINE + 1)
    }

    @Test
    fun `a long but allowed line still reads`() {
        val line = "{\"Chat\":{\"message\":\"" + "x".repeat(SYNCPLAY_MAX_LINE - 40) + "\"}}"
        val inp = ByteArrayInputStream((line + "\n").toByteArray(Charsets.UTF_8))
        assertEquals(line, readJsonLine(inp, inp.read()))
    }

    // --- Socket transport ----------------------------------------------------------------------------

    private fun <T> withPeer(block: suspend (SocketTransport, Socket) -> T): T {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val accepted = pool.submit<Socket> { server.accept() }
        val client = SocketTransport("127.0.0.1", server.localPort, connectTimeoutMs = 3_000)
        return try {
            runBlocking { client.connect() }
            val peer = accepted.get(3, TimeUnit.SECONDS)
            try {
                runBlocking { block(client, peer) }
            } finally {
                peer.close()
            }
        } finally {
            client.close()
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a read with a timeout gives up when the server says nothing, and the connection still works`() = withPeer { client, peer ->
        val started = System.nanoTime()
        assertNull(client.readLine(300))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 250..3_000)
        // The socket's own timeout is back to none: a later read waits as long as it takes.
        val line = """{"Hello":{"username":"ana"}}"""
        Thread {
            Thread.sleep(500)
            peer.getOutputStream().apply {
                write((line + "\r\n").toByteArray())
                flush()
            }
        }.start()
        assertEquals(line, client.readLine())
    }

    @Test
    fun `a read with a timeout returns the line that comes in time`() = withPeer { client, peer ->
        val line = """{"TLS":{"startTLS":"false"}}"""
        peer.getOutputStream().apply {
            write((line + "\r\n").toByteArray())
            flush()
        }
        assertEquals(line, client.readLine(5_000))
    }

    @Test
    fun `a server that dribbles bytes can't stretch the timeout`() = withPeer { client, peer ->
        val dribble = Thread {
            runCatching {
                val out = peer.getOutputStream()
                out.write('{'.code)
                repeat(50) {
                    Thread.sleep(100)
                    out.write('a'.code)
                    out.flush()
                }
            }
        }.apply { start() }
        val started = System.nanoTime()
        assertNull(client.readLine(400))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 2_000)
        dribble.interrupt()
    }

    @Test
    fun `lines and voice frames come through the buffer intact`() = withPeer { client, peer ->
        val packet = ByteArray(VOICE_MUX_MIN_PACKET) { it.toByte() }
        val bytes = java.io.ByteArrayOutputStream().apply {
            write("{\"a\":1}\r\n".toByteArray())
            write(VOICE_MUX_MARKER)
            write(0)
            write(packet.size)
            write(packet)
            write("{\"b\":\"·\"}\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        peer.getOutputStream().apply {
            write(bytes)
            flush()
        }
        assertEquals(SyncplayInbound.Line("{\"a\":1}"), client.readInbound())
        assertEquals(SyncplayInbound.VoicePacket(packet), client.readInbound())
        assertEquals(SyncplayInbound.Line("{\"b\":\"·\"}"), client.readInbound())
    }

    @Test
    fun `bytes the server sent before the TLS handshake aren't silently dropped`() = withPeer { client, peer ->
        peer.getOutputStream().apply {
            write("{\"TLS\":{\"startTLS\":\"true\"}}\r\nGARBAGE".toByteArray())
            flush()
        }
        assertEquals("""{"TLS":{"startTLS":"true"}}""", client.readLine(5_000))
        // Let the extra bytes land in the buffer.
        Thread.sleep(200)
        try {
            client.startTls()
            fail("the handshake shouldn't start over unread bytes")
        } catch (e: SSLException) {
            assertTrue(e.message!!, "before the TLS handshake" in e.message!!)
        }
        assertFalse(client.isTls)
    }

    // --- Server info ----------------------------------------------------------------------------------

    @Test
    fun `server info is read only up to its limit`() {
        val small = """{"tls":null}"""
        assertEquals(small, ServerInfo.readCapped(ByteArrayInputStream(small.toByteArray()), ServerInfo.MAX_INFO_BYTES))
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 'x'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('x'.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        assertNull(ServerInfo.readCapped(endless, ServerInfo.MAX_INFO_BYTES))
        assertTrue("read $served bytes", served <= ServerInfo.MAX_INFO_BYTES + 8_192)
    }

    // --- Filenames ---------------------------------------------------------------------------------------

    @Test
    fun `comparing prepared names answers like comparing the names`() {
        val names = listOf(
            "Movie (2020).mkv", "movie2020.MKV", "Movie_2020.mkv", "https://example.org/files/Movie%20(2020).mkv",
            "https://example.org/a/movie2020.mkv", Filenames.HIDDEN, Filenames.hash("Movie (2020).mkv"),
            Filenames.hash("https://example.org/files/Movie%20(2020).mkv"), "Other.mkv", "other.mkv", "a+b.mkv",
            "a%2Bb.mkv", "Ep 1 · Part 2…", "ep1·part2…", "", "abcdef012345", "%zz broken.mkv",
        )
        for (a in names) for (b in names) {
            assertEquals("'$a' vs '$b'", Filenames.same(a, b), Filenames.same(Filenames.Key(a), Filenames.Key(b)))
        }
    }
}
