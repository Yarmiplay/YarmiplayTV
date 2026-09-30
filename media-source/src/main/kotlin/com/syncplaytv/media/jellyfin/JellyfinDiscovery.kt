package com.syncplaytv.media.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

@Serializable
data class DiscoveredServer(
    @SerialName("Address") val address: String,
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

/**
 * Jellyfin LAN auto-discovery (UDP broadcast on port 7359). Does not work inside the Android
 * emulator's NAT; on real devices the caller should hold a WifiManager.MulticastLock.
 */
object JellyfinDiscovery {
    private const val PORT = 7359
    private const val MESSAGE = "who is JellyfinServer?"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(timeoutMs: Int = 2500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val payload = MESSAGE.toByteArray()
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target, PORT)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    runCatching { json.decodeFromString(DiscoveredServer.serializer(), text) }
                        .getOrNull()?.let { found.putIfAbsent(it.id.ifEmpty { it.address }, it) }
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { if (it !in result) result += it }
        }
        return result
    }
}
