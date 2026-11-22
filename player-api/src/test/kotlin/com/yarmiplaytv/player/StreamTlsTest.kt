package com.yarmiplaytv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTlsTest {
    @After
    fun tearDown() = StreamTls.clear()

    @Test
    fun `hosts of stream URLs`() {
        assertEquals("jf.example.com", StreamTls.hostOf("https://jf.example.com:8920/Videos/1/stream?api_key=x"))
        assertEquals("plex.example", StreamTls.hostOf("HTTPS://user@plex.example/library?X-Plex-Token=t"))
        assertEquals("::1", StreamTls.hostOf("https://[::1]:8096/x"))
        assertEquals("10.0.0.5", StreamTls.hostOf("http://10.0.0.5:8999/relay"))
        assertNull(StreamTls.hostOf("/storage/emulated/0/Movies/a.mkv"))
        assertNull(StreamTls.hostOf("fdclose://42"))
        assertNull(StreamTls.hostOf("content://media/external/video/1"))
    }

    @Test
    fun `certificates are checked unless the name was swapped for an address or the user allowed the host`() {
        val url = "https://home.example.org:8999/relay/file.mkv"
        assertTrue(StreamTls.verifies(url, url))
        assertFalse("StreamRoutes put the LAN address in", StreamTls.verifies(url, "https://192.168.1.20:8999/relay/file.mkv"))
        assertTrue("same host, other case", StreamTls.verifies(url, "https://HOME.example.org:8999/relay/file.mkv"))
        StreamTls.allowUnverified("Home.Example.org")
        assertFalse(StreamTls.verifies(url, url))
        assertTrue(StreamTls.verifies("https://other.example/x", "https://other.example/x"))
        assertTrue("local files", StreamTls.verifies("file:///a.mkv", "/a.mkv"))
    }

    @Test
    fun `certificate failures in the players' logs`() {
        // mpv's libcurl stream on Windows (seen with expired, self-signed and wrong-host badssl.com certificates).
        assertTrue(StreamTls.isCertificateFailure("error: SSL peer certificate or SSH remote key was not OK"))
        assertTrue(StreamTls.isCertificateFailure("TLS certificate verification failed."))
        // FFmpeg with mbedTLS (Android), GnuTLS and OpenSSL.
        assertTrue(StreamTls.isCertificateFailure("tls: mbedtls_ssl_handshake returned -0x2700"))
        assertTrue(StreamTls.isCertificateFailure("mbedtls_ssl_get_verify_result reported problems with the certificate verification, returned flags: 8"))
        assertTrue(StreamTls.isCertificateFailure("Peer certificate failed verification"))
        assertTrue(StreamTls.isCertificateFailure("The certificate's owner does not match hostname example.com"))
        assertTrue(StreamTls.isCertificateFailure("error:0A000086:SSL routines::certificate verify failed"))
        // Not about the certificate.
        assertFalse(StreamTls.isCertificateFailure("error: SSL connect error"))
        assertFalse(StreamTls.isCertificateFailure("Failed to open https://example.com/a.mkv."))
        assertFalse(StreamTls.isCertificateFailure("mbedtls_ssl_handshake returned -0x7780"))
        assertTrue(StreamTls.isTrustStoreFailure("mbedtls_x509_crt_parse_file for CA cert returned 2"))
        assertFalse(StreamTls.isCertificateFailure("mbedtls_x509_crt_parse_file for CA cert returned 2"))
    }

    @Test
    fun `a file that failed on its certificate names its host`() {
        val check = TlsCheck()
        val url = "https://self-signed.badssl.com/"
        assertTrue(check.begin(url, url))
        check.onLog("Opening https://self-signed.badssl.com/")
        check.onLog("error: SSL peer certificate or SSH remote key was not OK")
        assertEquals("self-signed.badssl.com", check.rejectedHost())
        assertFalse(check.retryUnchecked())

        // The next file starts clean.
        assertTrue(check.begin("https://example.com/a.mp4", "https://example.com/a.mp4"))
        check.onLog("error: SSL connect error")
        assertNull(check.rejectedHost())
    }

    @Test
    fun `an unchecked file never asks`() {
        val check = TlsCheck()
        assertFalse(check.begin("https://name.example/a", "https://10.0.0.2/a"))
        check.onLog("TLS certificate verification failed.")
        assertNull(check.rejectedHost())

        val unsupported = TlsCheck(supported = { false })
        assertFalse(unsupported.begin("https://name.example/a", "https://name.example/a"))
    }

    @Test
    fun `a broken trust store turns the checks off instead of asking`() {
        val check = TlsCheck()
        assertTrue(check.begin("https://a.example/x", "https://a.example/x"))
        check.onLog("mbedtls_x509_crt_parse_file for CA cert returned 1")
        check.onLog("mbedtls_ssl_handshake returned -0x2700")
        assertNull(check.rejectedHost())
        assertTrue(check.retryUnchecked())
        assertTrue(check.trustStoreBroken)
        assertFalse(check.begin("https://a.example/x", "https://a.example/x"))
    }
}
