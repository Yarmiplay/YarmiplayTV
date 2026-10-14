package com.yarmiplaytv.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS servers found at a local address whose certificate is for a name (a Jellyfin at https://192.168.1.10:8920
 * with a certificate for myname.duckdns.org): requests go to the name, so the certificate verifies, and the name
 * resolves to the local address whenever that answers. At home the name often leads to the router instead, which
 * can't loop it back in; away from home the address doesn't answer and the name resolves as usual.
 */
object LanHosts {
    private const val RECHECK_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 800

    private class Hint(val address: String, val port: Int) {
        @Volatile var checkedAt = 0L
        @Volatile var answers = false
    }

    private val hints = ConcurrentHashMap<String, Hint>()

    /** Replaces the reachability check when set; tests use it. */
    @Volatile
    var probe: ((address: String, port: Int) -> Boolean)? = null

    /** [name] can be reached at [address] (an IP literal, IPv6 without brackets) on [port] on its local network. */
    fun add(name: String, address: String, port: Int) {
        if (name.isBlank() || address.isBlank() || isIpLiteral(name)) return
        val key = name.lowercase()
        val current = hints[key]
        if (current != null && current.address == address && current.port == port) return
        hints[key] = Hint(address, port)
    }

    /** The local address [add]ed for [url]'s host, or null. */
    fun hintFor(url: String): String? = url.toHttpUrlOrNull()?.let { hints[it.host.lowercase()]?.address }

    fun clear() = hints.clear()

    /**
     * The local address of [name] when it answers there, checked again every [RECHECK_MS]; null when [name] has
     * none or it doesn't answer. Blocks for the check, so call it off the main thread.
     */
    fun lookup(name: String): List<InetAddress>? {
        val hint = hints[name.lowercase()] ?: return null
        val now = System.currentTimeMillis()
        if (now - hint.checkedAt > RECHECK_MS) {
            hint.answers = (probe ?: ::answers)(hint.address, hint.port)
            hint.checkedAt = now
        }
        return if (hint.answers) listOf(InetAddress.getByName(hint.address)) else null
    }

    /** [url] on its host's local address when that answered at the last check, for players that can't be given a lookup. */
    fun direct(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val hint = hints[parsed.host.lowercase()]?.takeIf { it.answers } ?: return url
        return parsed.newBuilder().host(hint.address).build().toString()
    }

    /**
     * For an `https://` [url] on an IP address: the same URL on the name its certificate is for, with the address
     * kept as that name's [add]ed hint. The certificate has to be one this device trusts; otherwise, and for every
     * other URL, [url] itself.
     */
    suspend fun byCertificateName(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.isHttps || !isIpLiteral(parsed.host)) return url
        val name = certificateName(parsed.host, parsed.port) ?: return url
        add(name, parsed.host, parsed.port)
        hints[name.lowercase()]?.apply {
            answers = true
            checkedAt = System.currentTimeMillis()
        }
        return parsed.newBuilder().host(name).build().toString().let { if (url.endsWith('/')) it else it.trimEnd('/') }
    }

    /**
     * The DNS name on the certificate the server at [address]:[port] presents, when the certificate chains to a
     * root this device trusts (the handshake checks the chain but not the name, which the caller then uses).
     */
    suspend fun certificateName(address: String, port: Int, timeoutMs: Int = 3_000): String? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { plain ->
                plain.connect(InetSocketAddress(address, port), timeoutMs)
                plain.soTimeout = timeoutMs
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(plain, address, port, true) as SSLSocket).use { tls ->
                    tls.useClientMode = true
                    tls.startHandshake()
                    (tls.session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::dnsName)
                }
            }
        }.getOrNull()
    }

    /** The certificate's first DNS name that isn't a wildcard. */
    internal fun dnsName(cert: X509Certificate): String? =
        cert.subjectAlternativeNames.orEmpty()
            .filter { it.getOrNull(0) == 2 }
            .mapNotNull { it.getOrNull(1) as? String }
            .firstOrNull { it.isNotBlank() && !it.startsWith("*") && !isIpLiteral(it) }

    private fun answers(address: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    /** A private, link-local or unique-local IP literal: an address inside a home network. */
    fun isLocalAddress(address: String): Boolean {
        if (!isIpLiteral(address)) return false
        val ip = runCatching { InetAddress.getByName(address.trim().removePrefix("[").removeSuffix("]")) }.getOrNull() ?: return false
        return ip.isSiteLocalAddress || ip.isLinkLocalAddress || (ip is Inet6Address && (ip.address[0].toInt() and 0xfe) == 0xfc)
    }

    /** An IPv4 or IPv6 literal, with or without IPv6 brackets. */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (':' in h) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }
}
