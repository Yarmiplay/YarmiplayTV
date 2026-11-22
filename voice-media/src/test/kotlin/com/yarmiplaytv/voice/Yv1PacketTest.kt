package com.yarmiplaytv.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class Yv1PacketTest {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `seal and open roundtrip audio`() {
        val plain = byteArrayOf(1, 2, 3, 4, 5, 9)
        val packet = Yv1Packet.seal(key, Yv1Packet.TYPE_AUDIO, token = 0x0123456789abcdefL, sender = 0, sequence = 7, timestamp = 960, plain = plain)
        assertNotNull(packet)
        assertTrue(packet!!.size in Yv1Packet.MIN_PACKET..Yv1Packet.MAX_PACKET)
        assertEquals('Y'.code.toByte(), packet[0])
        assertEquals('V'.code.toByte(), packet[1])
        assertEquals(1.toByte(), packet[2])
        assertEquals(Yv1Packet.TYPE_AUDIO, packet[3])
        val opened = Yv1Packet.open(key, packet)!!
        assertEquals(Yv1Packet.TYPE_AUDIO, opened.type)
        assertEquals(0L, opened.sender)
        assertEquals(7, opened.sequence)
        assertEquals(960, opened.timestamp)
        assertArrayEquals(plain, opened.plain)
    }

    @Test
    fun `wrong key fails open`() {
        val packet = Yv1Packet.seal(key, Yv1Packet.TYPE_KEEPALIVE, 1L, 0L, 1, 0, byteArrayOf(0))!!
        val other = ByteArray(32) { 1 }
        assertNull(Yv1Packet.open(other, packet))
    }

    @Test
    fun `switching keys on one thread uses the right key each time`() {
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        repeat(5) { i ->
            val k = if (i % 2 == 0) key else other
            val wrong = if (i % 2 == 0) other else key
            val packet = Yv1Packet.seal(k, Yv1Packet.TYPE_AUDIO, 7L, 9L, i, i * 960, byteArrayOf(i.toByte(), 2, 3))!!
            assertNull(Yv1Packet.open(wrong, packet))
            val opened = Yv1Packet.open(k, packet)!!
            assertEquals(9L, opened.sender)
            assertEquals(i, opened.sequence)
            assertArrayEquals(byteArrayOf(i.toByte(), 2, 3), opened.plain)
        }
        // A key changed in place (same array) is a new key, not the cached one.
        val mutable = key.copyOf()
        val first = Yv1Packet.seal(mutable, Yv1Packet.TYPE_KEEPALIVE, 1L, 0L, 1, 0, byteArrayOf(0))!!
        mutable[0] = (mutable[0] + 1).toByte()
        assertNull(Yv1Packet.open(mutable, first))
        assertNotNull(Yv1Packet.open(key, first))
    }

    @Test
    fun `packets sealed on many threads open on others`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val sealed = (0 until 200).map { i ->
                pool.submit<ByteArray> { Yv1Packet.seal(key, Yv1Packet.TYPE_AUDIO, 3L, 0L, i, i, ByteArray(i % 100 + 1) { i.toByte() })!! }
            }.map { it.get() }
            val opened = sealed.mapIndexed { i, p -> pool.submit<Yv1Packet.Opened?> { Yv1Packet.open(key, p) } to i }
            for ((f, i) in opened) {
                val o = f.get()!!
                assertEquals(i, o.sequence)
                assertArrayEquals(ByteArray(i % 100 + 1) { i.toByte() }, o.plain)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the packet layout is header then ciphertext and tag`() {
        val plain = ByteArray(33) { it.toByte() }
        val packet = Yv1Packet.seal(key, Yv1Packet.TYPE_AUDIO, 0x0102030405060708L, 0L, 5, 6, plain)!!
        assertEquals(Yv1Packet.HEADER_LEN + plain.size + Yv1Packet.TAG_LEN, packet.size)
        // Decrypting by hand with the JDK gives the plaintext back: the wire format is plain AES-GCM.
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, packet.copyOfRange(28, 40)),
        )
        cipher.updateAAD(packet.copyOfRange(0, Yv1Packet.HEADER_LEN))
        assertArrayEquals(plain, cipher.doFinal(packet.copyOfRange(Yv1Packet.HEADER_LEN, packet.size)))
    }

    @Test
    fun `logged voice URLs leave out the token`() {
        assertEquals(
            "wss://example.org:8999/yarmiplay/voice?t=…",
            Yv1WsTransport.redactToken("wss://example.org:8999/yarmiplay/voice?t=0123456789abcdef"),
        )
        assertEquals("ws://[::1]:8999/v?x=1&t=…&y=2", Yv1WsTransport.redactToken("ws://[::1]:8999/v?x=1&t=0123456789abcdef&y=2"))
        assertEquals("ws://h:1/v", Yv1WsTransport.redactToken("ws://h:1/v"))
    }

    @Test
    fun `token hex parses as big-endian u64`() {
        assertEquals(0x0123456789abcdefL, Yv1Packet.parseTokenHex("0123456789abcdef"))
        assertNull(Yv1Packet.parseTokenHex("short"))
        assertNull(Yv1Packet.parseTokenHex("0123456789ABCDEF")) // must be lower-case
    }

    @Test
    fun `opus encode decode roundtrip`() {
        val codec = OpusCodec(bitrate = 32_000, fec = true, dtx = true)
        val pcm = ShortArray(960) { (kotlin.math.sin(it * 0.1) * 4000).toInt().toShort() }
        val frame = codec.encode(pcm)
        assertNotNull(frame)
        assertTrue(frame!!.isNotEmpty())
        val out = codec.decode(frame)
        assertNotNull(out)
        assertEquals(960, out!!.size)
    }
}
