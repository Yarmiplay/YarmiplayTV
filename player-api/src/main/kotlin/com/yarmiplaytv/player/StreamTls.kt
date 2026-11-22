package com.yarmiplaytv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the player checks a stream's HTTPS certificate. It does, except where the app put an address in place of
 * the URL's name ([StreamRoutes]: the certificate is for the name, so it can't match) and for hosts the user chose to
 * play anyway after their certificate couldn't be verified (for the rest of this run).
 */
object StreamTls {
    private val unverified = ConcurrentHashMap.newKeySet<String>()

    /** Plays [host]'s streams without checking its certificate until the app quits. */
    fun allowUnverified(host: String) {
        unverified += host.lowercase()
    }

    fun isAllowedUnverified(host: String): Boolean = host.lowercase() in unverified

    /** Whether [url], opened at [target] (what the player is given: [url] or its [StreamRoutes] rewrite), checks the certificate. */
    fun verifies(url: String, target: String): Boolean {
        val host = hostOf(url) ?: return true
        val targetHost = hostOf(target)
        if (targetHost != null && !targetHost.equals(host, ignoreCase = true)) return false
        return !isAllowedUnverified(host)
    }

    /** The host of an http(s) URL, without brackets around an IPv6 address; null for anything else. */
    fun hostOf(url: String): String? {
        val match = HOST.find(url) ?: return null
        return match.groupValues[1].removePrefix("[").removeSuffix("]").takeIf { it.isNotEmpty() }
    }

    /**
     * A player log line saying the server's certificate failed the check: mpv's libcurl stream (Windows build),
     * FFmpeg with mbedTLS (Android), GnuTLS or OpenSSL (Linux and macOS libmpv).
     */
    fun isCertificateFailure(line: String): Boolean {
        val l = line.lowercase()
        return CERTIFICATE_FAILURES.any { it in l }
    }

    /** A log line saying the player's own list of trusted certificates couldn't be used: no server is to blame. */
    fun isTrustStoreFailure(line: String): Boolean {
        val l = line.lowercase()
        return TRUST_STORE_FAILURES.any { it in l }
    }

    internal fun clear() = unverified.clear()

    private val HOST = Regex("^https?://(?:[^@/?#]*@)?(\\[[^]]*]|[^:/?#]*)", RegexOption.IGNORE_CASE)

    private val CERTIFICATE_FAILURES = listOf(
        // libcurl (CURLE_PEER_FAILED_VERIFICATION) and mpv's hint after it.
        "ssl peer certificate or ssh remote key was not ok",
        "tls certificate verification failed",
        // FFmpeg + mbedTLS: MBEDTLS_ERR_X509_CERT_VERIFY_FAILED, and the check after the handshake.
        "mbedtls_ssl_handshake returned -0x2700",
        "mbedtls_ssl_get_verify_result reported problems",
        // FFmpeg + GnuTLS.
        "unable to verify peer certificate",
        "peer certificate failed verification",
        "certificate's owner does not match hostname",
        // FFmpeg + OpenSSL ("error:0A000086:SSL routines::certificate verify failed").
        "certificate verify failed",
    )

    private val TRUST_STORE_FAILURES = listOf(
        "mbedtls_x509_crt_parse_file for ca cert returned",
        "no ca chain is set",
        "problem with the ssl ca cert",
    )
}

/**
 * One player's certificate checks for the file it's opening: [begin] says whether to check it, [onLog] notices a
 * failed check in mpv's log, and [rejectedHost] tells, once the file failed to open, whether the certificate was why.
 * [trustStoreBroken] turns the checks off for the rest of the run when the player's own trust store doesn't work.
 */
class TlsCheck(private val supported: () -> Boolean = { true }) {
    @Volatile private var host: String? = null
    @Volatile private var verifying = false
    @Volatile private var rejected = false
    @Volatile private var storeFailed = false

    /** The player's trusted certificates failed to load; nothing is checked until the app quits. */
    @Volatile var trustStoreBroken = false
        private set

    /** A new file: true when its certificate is checked (mpv's tls-verify). */
    fun begin(url: String, target: String): Boolean {
        host = StreamTls.hostOf(url)
        rejected = false
        storeFailed = false
        // Plain http:// too: a redirect can lead to HTTPS.
        verifying = !trustStoreBroken && supported() && StreamTls.verifies(url, target)
        return verifying
    }

    fun onLog(line: String) {
        if (!verifying) return
        if (StreamTls.isTrustStoreFailure(line)) {
            storeFailed = true
            trustStoreBroken = true
        } else if (StreamTls.isCertificateFailure(line)) {
            rejected = true
        }
    }

    /** After the file failed to open: the host whose certificate failed the check, or null when that wasn't why. */
    fun rejectedHost(): String? = host?.takeIf { verifying && rejected && !storeFailed }

    /** After the file failed to open: true when the player's own trust store was why, so it's opened again unchecked. */
    fun retryUnchecked(): Boolean = verifying && storeFailed
}
