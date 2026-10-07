package com.yarmiplaytv.syncplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The YarmiplayServerTV extensions to Syncplay 1.7 (extension protocol 1): device access, the file relay and
 * Jellyfin sharing. A stock Syncplay server, or YarmiplayServerTV in vanilla mode, never sees any of it beyond
 * the `yarmiplay` entry in the Hello's features.
 */
object Yarmiplay {
    const val PROTOCOL = 1
    const val SERVER_NAME = "YarmiplayServerTV"
    const val SIGNED_PREFIX = "YarmiplayServerTV-device-auth-v1"
    const val MAX_OFFERED_FILES = 200

    /** The DER header of a P-256 SubjectPublicKeyInfo, before the uncompressed point. */
    const val P256_SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"

    private val serverIdPattern = Regex("[0-9a-f]{32}")

    fun isValidServerId(id: String): Boolean = serverIdPattern.matches(id)

    /** What a device signs to answer a challenge. */
    fun signedMessage(serverId: String, nonce: String): ByteArray = "$SIGNED_PREFIX\n$serverId\n$nonce".toByteArray(Charsets.UTF_8)

    /** SHA-256 of the SubjectPublicKeyInfo DER: the first 16 hex digits in uppercase, in groups of four. */
    fun fingerprint(spki: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spki).take(8)
            .joinToString("") { "%02X".format(it) }
            .chunked(4).joinToString("-")
}

/** Who the connected server is, as far as this connection can tell. */
enum class ServerKind {
    /** The server hasn't answered yet. */
    UNKNOWN,

    /** A stock Syncplay server, or YarmiplayServerTV in vanilla mode: no extensions. */
    SYNCPLAY,
    YARMIPLAY,
}

/** Where this device stands with a YarmiplayServerTV that admits approved devices. */
sealed interface DeviceState {
    data object None : DeviceState
    data class Pending(val fingerprint: String) : DeviceState
    data object Approved : DeviceState
    data object Denied : DeviceState
    data object Expired : DeviceState
    /** The key isn't approved and this connection didn't ask for access. */
    data object Required : DeviceState
    /** The host removed this device while it was in a room. */
    data object Revoked : DeviceState

    /** States after which the client stops instead of reconnecting. */
    val refused: Boolean get() = this is Denied || this is Expired || this is Required || this is Revoked
}

/**
 * This device's key for one server. The private key never leaves the implementation; [publicKey] and [sign] are
 * all the protocol needs.
 */
interface DeviceAuth {
    /** Shown to the host; the server keeps 60 characters. */
    val deviceName: String

    /** The SubjectPublicKeyInfo DER of the P-256 key for [serverId], created on first use. */
    fun publicKey(serverId: String): ByteArray

    /** A DER ECDSA (SHA256withECDSA) signature over [message] with [serverId]'s key. */
    fun sign(serverId: String, message: ByteArray): ByteArray
}

data class YarmiplayCapabilities(
    val fileRelay: Boolean = false,
    val jellyfin: Boolean = false,
    val https: Boolean = false,
) {
    /** Vanilla mode: the host switched every extension off, and the session token is revoked. */
    val allOff: Boolean get() = !fileRelay && !jellyfin && !https
}

/** The host's Jellyfin, as the server shares it. */
data class SharedJellyfin(
    val available: Boolean = false,
    val serverId: String = "",
    val serverName: String = "",
    /** Jellyfin also answers on the Syncplay host and port. */
    val proxy: Boolean = false,
    val addresses: List<String> = emptyList(),
)

/** A file another viewer in the room can serve through the server. */
data class RelayFile(
    /** The file's id in this room, for `/yarmiplay/files/<id>`. */
    val id: String,
    val name: String,
    val size: Long,
    val duration: Double,
    val quickHash: String,
    /** Connected viewers offering it; with 0 the entry is only what the server has cached. */
    val sources: Int,
    val cachedBytes: Long,
    /** Bytes per second the server currently receives from seeders for this file. */
    val rate: Long,
) {
    /** Whether all of the file can be read: someone seeds it, or the server has every byte. */
    val readable: Boolean get() = sources > 0 || (size > 0 && cachedBytes >= size)
}

/** A local file this device can serve to the room. */
data class RelayOffer(val name: String, val size: Long, val duration: Double, val quickHash: String)

/** The server asks a seeder for bytes `[offset, offset + length)` of the file matching [size] and [quickHash]. */
data class UploadRequest(
    val id: String,
    val file: String,
    val size: Long,
    val quickHash: String,
    val offset: Long,
    val length: Long,
)

/** The extension state of the current connection (no secrets: the token is in [YarmiplaySession]). */
data class YarmiplayInfo(
    val serverKind: ServerKind = ServerKind.UNKNOWN,
    val serverVersion: String? = null,
    /** The negotiated extension protocol, 0 without a session. */
    val protocol: Int = 0,
    /** The server's id from its device challenge (32 hex digits), when it sent one. */
    val serverId: String? = null,
    /** `open`, `password` or `approved`, from the Hello's marker. */
    val access: String? = null,
    /** Whether this login used an approved device key. */
    val approvedDevice: Boolean = false,
    val device: DeviceState = DeviceState.None,
    val capabilities: YarmiplayCapabilities = YarmiplayCapabilities(),
    val jellyfin: SharedJellyfin = SharedJellyfin(),
    /** The room's relay list; empty without a session or with the relay off. */
    val relayFiles: List<RelayFile> = emptyList(),
    /** Whether this connection has a live session token. */
    val session: Boolean = false,
) {
    val relayActive: Boolean get() = session && capabilities.fileRelay
}

/**
 * The HTTP side of an extension session: base URL on the Syncplay host and port, and the bearer token. Valid
 * only while the connection that got it stays logged in.
 */
class YarmiplaySession(val token: String, val protocol: Int, val baseUrl: String) {
    val authorization: String get() = "Bearer $token"

    override fun toString(): String = "YarmiplaySession(protocol=$protocol, baseUrl=$baseUrl, token=…)"
}

/**
 * SHA-256 over the first `min(1 MiB, size)` bytes, the last `min(1 MiB, size)` bytes, and [size] as an unsigned
 * 64-bit little-endian integer, in lowercase hex; files under 1 MiB go in twice. [read] fills its buffer from the
 * given offset.
 */
object QuickHash {
    private const val MIB = 1 shl 20

    fun of(size: Long, read: (offset: Long, buffer: ByteArray) -> Unit): String {
        require(size > 0) { "Empty file" }
        val n = minOf(MIB.toLong(), size).toInt()
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(n)
        read(0, buf)
        sha.update(buf)
        read(size - n, buf)
        sha.update(buf)
        sha.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
