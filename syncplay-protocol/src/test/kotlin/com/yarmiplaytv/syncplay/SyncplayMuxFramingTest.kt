package com.yarmiplaytv.syncplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end demux of JSON lines and `0x1E` voice records on a real TCP socket
 * (VOICE_PROTOCOL §6.0.1).
 */
class SyncplayMuxFramingTest {
    @Test
    fun interleavedJsonAndVoiceRecords() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        val accepted = pool.submit<Socket> { server.accept() }
        val client = SocketTransport("127.0.0.1", port, connectTimeoutMs = 3_000)
        client.connect()
        val peer = accepted.get(3, TimeUnit.SECONDS)
        try {
            val out = peer.getOutputStream()
            // JSON Hello line
            out.write("""{"Hello":{"username":"alice"}}""".toByteArray())
            out.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
            // Voice record: 56-byte packet
            val packet = ByteArray(56) { i -> when (i) {
                0 -> 0x59
                1 -> 0x56
                2 -> 0x01
                else -> (i and 0xff).toByte()
            } }
            out.write(VOICE_MUX_MARKER)
            out.write(0)
            out.write(56)
            out.write(packet)
            out.flush()
            // Another JSON line after binary (packet contains 0x0A-ish? we used sequential bytes — ok)
            out.write("""{"State":{}}""".toByteArray())
            out.write('\n'.code)

            when (val first = client.readInbound()) {
                is SyncplayInbound.Line -> assertTrue(first.text.contains("Hello"))
                else -> error("expected JSON line, got $first")
            }
            when (val second = client.readInbound()) {
                is SyncplayInbound.VoicePacket -> assertArrayEquals(packet, second.bytes)
                else -> error("expected voice packet, got $second")
            }
            when (val third = client.readInbound()) {
                is SyncplayInbound.Line -> assertTrue(third.text.contains("State"))
                else -> error("expected JSON line, got $third")
            }

            // Client → server voice write
            val sent = ByteArray(64) { 7 }
            // pad to min 56 — already 64
            client.writeVoicePacket(sent)
            val inp = peer.getInputStream()
            assertEquals(VOICE_MUX_MARKER, inp.read())
            val hi = inp.read()
            val lo = inp.read()
            assertEquals(64, (hi shl 8) or lo)
            val got = ByteArray(64)
            var off = 0
            while (off < 64) {
                val n = inp.read(got, off, 64 - off)
                require(n > 0)
                off += n
            }
            assertArrayEquals(sent, got)
        } finally {
            client.close()
            peer.close()
            server.close()
            pool.shutdownNow()
        }
    }
}
