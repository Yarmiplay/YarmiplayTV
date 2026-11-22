package com.yarmiplaytv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TrustedCertificatesTest {
    /** The JDK's trusted roots stand in for Android's: the same public CAs. */
    private val roots: List<X509Certificate> =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().single().acceptedIssuers.toList()

    @Test
    fun `the public roots are written as PEM that reads back`() {
        val pem = TrustedCertificates.pem(roots)
        val read = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
        val kept = roots.filter(TrustedCertificates::readableByMbedTls).distinctBy { it.encoded.contentHashCode() }
        assertEquals(kept.size, read.size)
        assertTrue("most public roots are RSA or ECDSA on NIST curves: ${kept.size} of ${roots.size}", kept.size > roots.size * 3 / 4)
        assertTrue(pem.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }.all { it.length <= 64 })
    }

    @Test
    fun `nothing is written twice`() {
        val once = TrustedCertificates.pem(roots)
        assertEquals(once, TrustedCertificates.pem(roots + roots))
    }
}
