package com.yarmiplaytv.syncplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/**
 * A YarmiplayServerTV's certificate: the [name] it's valid for, and the addresses ([lan], `ip:port`, IPv6 in
 * brackets) it answers on inside its own network, where the name may lead to the router instead.
 */
data class ServerTls(val name: String, val lan: List<String> = emptyList())

/**
 * How the current connection reached the server: the address [dialed], and the name its certificate was
 * [verifiedAs] (null without TLS). [atHome] when that's one of the server's own LAN addresses rather than what
 * the user typed.
 */
data class ConnectionRoute(val dialed: String, val verifiedAs: String?, val atHome: Boolean)

/** A YarmiplayServerTV that answered [SyncplayDiscovery] on the local network. */
data class LanServer(
    val serverId: String,
    val version: String,
    val port: Int,
    val tls: ServerTls?,
    /** Where the reply came from (an IP address). */
    val address: String,
) {
    /** What to type to join it: its certificate's name when it has one, else its address. */
    val host: String get() = tls?.name ?: address
}

/** Parsing of the `tls` field and host/address helpers shared by the client, discovery and the info request. */
object ServerTlsFormat {
    /** `{"name": "...", "lan": ["ip:port", ...]}`, or null for JSON null, a missing field or one without a name. */
    fun parse(element: JsonElement?): ServerTls? {
        val o = element as? JsonObject ?: return null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lan = (o["lan"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        return ServerTls(name, lan)
    }

    /** An IPv4 or IPv6 literal (not a name), with or without IPv6 brackets. */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (':' in h) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }

    /** `192.168.1.20:8999` or `[2a02::20]:8999` to host and port; null when it isn't one. */
    fun splitHostPort(address: String): Pair<String, Int>? {
        val a = address.trim()
        val host: String
        val portText: String
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0 || a.getOrNull(end + 1) != ':') return null
            host = a.substring(1, end)
            portText = a.substring(end + 2)
        } else {
            val colon = a.lastIndexOf(':')
            if (colon <= 0 || a.indexOf(':') != colon) return null
            host = a.substring(0, colon)
            portText = a.substring(colon + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    /** [host] for a URL: IPv6 literals in brackets. */
    fun urlHost(host: String): String = host.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
}

/**
 * Finds YarmiplayServerTVs on the local network: a UDP broadcast of `{"yarmiplay":"discover","protocol":1}` to the
 * Syncplay port, answered by each server with its id, port and certificate. Stock Syncplay servers don't answer.
 * Callers make sure the network is a local one (Wi-Fi or Ethernet, never mobile data).
 */
object SyncplayDiscovery {
    const val PROTOCOL = 1
    const val TIMEOUT_MS = 1_500
    /** Where the join form looks: Syncplay's default port and the public servers' range. */
    val COMMON_PORTS: Set<Int> = setOf(8999, 8995, 8996, 8997, 8998)
    private val json = Json { ignoreUnknownKeys = true }

    /** Replaces the network scan when set; tests use it for fixed results. */
    @Volatile
    var scanner: (suspend (ports: Set<Int>) -> List<LanServer>)? = null

    val request: ByteArray = buildJsonObject { put("yarmiplay", "discover"); put("protocol", PROTOCOL) }.toString().toByteArray()

    /** Servers answering on any of [ports] within [timeoutMs], one per server id. */
    suspend fun discover(ports: Set<Int>, timeoutMs: Int = TIMEOUT_MS): List<LanServer> =
        scanner?.invoke(ports) ?: scan(ports, timeoutMs)

    /** The servers that are [name] (by their certificate's name, ignoring case) or have [serverId]. */
    fun matching(servers: List<LanServer>, name: String, serverId: String?): List<LanServer> = servers.filter { s ->
        s.tls?.name?.equals(name.trim(), ignoreCase = true) == true || (!serverId.isNullOrEmpty() && s.serverId == serverId)
    }

    /** A reply from [source], or null for anything that isn't a YarmiplayServerTV's answer. */
    fun parseReply(text: String, source: String): LanServer? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((o["yarmiplay"] as? JsonPrimitive)?.contentOrNull != "server") return null
        val port = (o["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
        val tls = o["tls"]?.takeUnless { it is JsonNull }?.let(ServerTlsFormat::parse)
        return LanServer(
            serverId = (o["serverId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            version = (o["version"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            port = port,
            tls = tls,
            address = source,
        )
    }

    private suspend fun scan(ports: Set<Int>, timeoutMs: Int): List<LanServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LanServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                for (target in broadcastAddresses()) for (port in ports) {
                    runCatching { socket.send(DatagramPacket(request, request.size, target, port)) }
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
                    val source = packet.address.hostAddress.substringBefore('%')
                    parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8), source)
                        ?.let { found.putIfAbsent(it.serverId.ifEmpty { "$source:${it.port}" }, it) }
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(result) { it.broadcast }
        }
        return result.toList()
    }
}

/** `GET http://<host>:<port>/yarmiplay/info`: what a YarmiplayServerTV says about itself before any login. */
object ServerInfo {
    private val json = Json { ignoreUnknownKeys = true }

    /** The server's certificate, or null when it has none, isn't a YarmiplayServerTV, or doesn't answer in time. */
    suspend fun tls(host: String, port: Int, timeoutMs: Int = 2_000): ServerTls? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("http://${ServerTlsFormat.urlHost(host)}:$port/yarmiplay/info").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return@runCatching null
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                parse(text)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun parse(text: String): ServerTls? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.get("tls")?.let(ServerTlsFormat::parse)
}
