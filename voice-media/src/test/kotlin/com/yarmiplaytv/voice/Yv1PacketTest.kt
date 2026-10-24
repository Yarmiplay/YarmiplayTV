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
