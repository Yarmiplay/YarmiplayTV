package com.yarmiplaytv.voice

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `yv1` media packet (VOICE_PROTOCOL §6.1): 40-byte header + AES-256-GCM ciphertext+tag.
 * GCM additional data is the header; plaintext is one Opus frame, keepalive `0x00`, or bye.
 */
object Yv1Packet {
    const val VERSION: Byte = 1
    const val TYPE_AUDIO: Byte = 1
    const val TYPE_KEEPALIVE: Byte = 2
    const val TYPE_BYE: Byte = 3
    const val HEADER_LEN = 40
    const val TAG_LEN = 16
    const val NONCE_LEN = 12
    const val MIN_PACKET = HEADER_LEN + TAG_LEN
    const val MAX_PACKET = 1200
    private val MAGIC = byteArrayOf(0x59, 0x56) // "YV"
    private val random = SecureRandom()

    /**
     * One AES-GCM cipher per thread, and the key object of the key it last used: packets come 50 times a second per
     * speaker, and looking up a cipher and building a key object each time is most of the cost. GCM is re-initialised
     * with each packet's own nonce, so reuse doesn't change what goes on the wire.
     */
    private class Crypto {
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var keyBytes: ByteArray? = null
        private var keySpec: SecretKeySpec? = null

        fun key(key: ByteArray): SecretKeySpec {
            val cached = keySpec
            if (cached != null && keyBytes.contentEquals(key)) return cached
            return SecretKeySpec(key, "AES").also {
                keyBytes = key.copyOf()
                keySpec = it
            }
        }
    }

    private val crypto = object : ThreadLocal<Crypto>() {
        override fun initialValue() = Crypto()
    }

    data class Opened(
        val type: Byte,
        val sender: Long,
        val sequence: Int,
        val timestamp: Int,
        val plain: ByteArray,
    )

    fun seal(
        key: ByteArray,
        type: Byte,
        token: Long,
        sender: Long,
        sequence: Int,
        timestamp: Int,
        plain: ByteArray,
    ): ByteArray? {
        if (key.size != 32) return null
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val header = header(type, token, sender, sequence, timestamp, nonce)
        return try {
            val c = crypto.get()
            val cipher = c.cipher
            cipher.init(Cipher.ENCRYPT_MODE, c.key(key), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            // Header, then ciphertext and tag written straight after it.
            val out = ByteArray(HEADER_LEN + plain.size + TAG_LEN)
            System.arraycopy(header, 0, out, 0, HEADER_LEN)
            val n = cipher.doFinal(plain, 0, plain.size, out, HEADER_LEN)
            if (HEADER_LEN + n == out.size) out else out.copyOf(HEADER_LEN + n)
        } catch (_: Exception) {
            null
        }
    }

    fun open(key: ByteArray, packet: ByteArray): Opened? {
        if (key.size != 32 || packet.size < MIN_PACKET || packet.size > MAX_PACKET) return null
        if (packet[0] != MAGIC[0] || packet[1] != MAGIC[1] || packet[2] != VERSION) return null
        return try {
            val c = crypto.get()
            val cipher = c.cipher
            // The nonce is the header's last 12 bytes; the header is the additional data, the rest the ciphertext.
            cipher.init(Cipher.DECRYPT_MODE, c.key(key), GCMParameterSpec(TAG_LEN * 8, packet, 28, NONCE_LEN))
            cipher.updateAAD(packet, 0, HEADER_LEN)
            val plain = cipher.doFinal(packet, HEADER_LEN, packet.size - HEADER_LEN)
            val buf = ByteBuffer.wrap(packet, 0, HEADER_LEN)
            buf.position(12)
            Opened(
                type = packet[3],
                sender = buf.long,
                sequence = buf.int,
                timestamp = buf.int,
                plain = plain,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseTokenHex(hex: String): Long? {
        if (hex.length != 16 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex.toULong(16).toLong()
    }

    fun decodeKeyBase64(b64: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(b64)?.takeIf { it.size == 32 }
    } catch (_: Exception) {
        null
    }

    private fun header(
        type: Byte,
        token: Long,
        sender: Long,
        sequence: Int,
        timestamp: Int,
        nonce: ByteArray,
    ): ByteArray {
        val h = ByteArray(HEADER_LEN)
        h[0] = MAGIC[0]
        h[1] = MAGIC[1]
        h[2] = VERSION
        h[3] = type
        ByteBuffer.wrap(h, 4, 8).putLong(token)
        ByteBuffer.wrap(h, 12, 8).putLong(sender)
        ByteBuffer.wrap(h, 20, 4).putInt(sequence)
        ByteBuffer.wrap(h, 24, 4).putInt(timestamp)
        System.arraycopy(nonce, 0, h, 28, NONCE_LEN)
        return h
    }
}
