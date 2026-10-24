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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            val ct = cipher.doFinal(plain)
            ByteArray(HEADER_LEN + ct.size).also {
                System.arraycopy(header, 0, it, 0, HEADER_LEN)
                System.arraycopy(ct, 0, it, HEADER_LEN, ct.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun open(key: ByteArray, packet: ByteArray): Opened? {
        if (key.size != 32 || packet.size < MIN_PACKET || packet.size > MAX_PACKET) return null
        if (packet[0] != MAGIC[0] || packet[1] != MAGIC[1] || packet[2] != VERSION) return null
        val header = packet.copyOfRange(0, HEADER_LEN)
        val ct = packet.copyOfRange(HEADER_LEN, packet.size)
        val nonce = header.copyOfRange(28, 40)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(ct)
            val buf = ByteBuffer.wrap(header)
            buf.position(12)
            Opened(
                type = header[3],
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
