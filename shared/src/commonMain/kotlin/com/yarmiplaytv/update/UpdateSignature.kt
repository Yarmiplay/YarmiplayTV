package com.yarmiplaytv.update

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * version.json is signed: the Pages workflow signs it with the release key (ECDSA P-256 over SHA-256, the
 * `UPDATE_SIGNING_KEY` secret) and publishes the signature beside it as `version.json.sig`, base64 of the DER
 * signature (`openssl dgst -sha256 -sign key.pem version.json | base64 -w0`). The apps accept an update only when the
 * signature checks with this public key, so a compromised site or domain can't point them at another package with
 * a matching SHA-256. ECDSA P-256 rather than Ed25519, because every Android version and the desktop JVM have it.
 */
object UpdateSignature {
    /** The release key's public half (SubjectPublicKeyInfo, DER, base64). */
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ2sWkTOoxR+x5mSD4nwBGb2pTB2m+e5mvSrideR0HuSLMxMePazArdwIWKYXOtj9m0+mziTiJ6KZWTk1X7Kpvw=="

    /** The key updates are checked against; `-Dyarmiplaytv.updatePublicKey=` replaces it, to test against another site. */
    fun key(): PublicKey = parseKey(System.getProperty("yarmiplaytv.updatePublicKey") ?: PUBLIC_KEY)

    fun parseKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))

    /** Whether [signatureBase64] (the .sig file's text) is [key]'s signature of exactly [manifest]. */
    fun verifies(manifest: ByteArray, signatureBase64: String, key: PublicKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(manifest)
            verify(Base64.getMimeDecoder().decode(signatureBase64.trim()))
        }
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: java.security.GeneralSecurityException) {
        false
    }
}
