package com.yarmiplaytv.player

import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The Android player's FFmpeg checks certificates with mbedTLS, which has no trust store of its own: it's given the
 * system's trusted certificates as a PEM file (mpv's tls-ca-file). FFmpeg rejects the whole file when mbedTLS can't
 * read one certificate in it, so only the kinds it reads are written: RSA keys, and EC keys on P-256, P-384 or P-521,
 * signed with RSA PKCS#1 or ECDSA and SHA-1 or SHA-2.
 */
object TrustedCertificates {
    fun pem(certificates: Iterable<X509Certificate>): String = buildString {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        certificates.filter(::readableByMbedTls).distinctBy { it.encoded.contentHashCode() }.forEach { cert ->
            append("-----BEGIN CERTIFICATE-----\n")
            append(encoder.encodeToString(cert.encoded))
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    fun readableByMbedTls(cert: X509Certificate): Boolean {
        if (cert.sigAlgOID !in SIGNATURES) return false
        return when (val key = cert.publicKey) {
            is RSAPublicKey -> key.modulus.bitLength() in 1024..8192
            is ECPublicKey -> key.params.curve.field.fieldSize in setOf(256, 384, 521)
            else -> false
        }
    }

    private val SIGNATURES = setOf(
        "1.2.840.113549.1.1.5", // sha1WithRSAEncryption
        "1.2.840.113549.1.1.14", // sha224WithRSAEncryption
        "1.2.840.113549.1.1.11", // sha256WithRSAEncryption
        "1.2.840.113549.1.1.12", // sha384WithRSAEncryption
        "1.2.840.113549.1.1.13", // sha512WithRSAEncryption
        "1.2.840.10045.4.1", // ecdsa-with-SHA1
        "1.2.840.10045.4.3.1", // ecdsa-with-SHA224
        "1.2.840.10045.4.3.2", // ecdsa-with-SHA256
        "1.2.840.10045.4.3.3", // ecdsa-with-SHA384
        "1.2.840.10045.4.3.4", // ecdsa-with-SHA512
    )
}
