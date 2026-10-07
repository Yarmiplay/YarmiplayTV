package com.yarmiplaytv.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Non-exportable keys in the AndroidKeyStore, alias `yarmiplay-device-<serverId>`. */
class AndroidDeviceKeyStore : DeviceKeyStore {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private fun alias(serverId: String) = "yarmiplay-device-${DeviceKeyStore.checked(serverId)}"

    @Synchronized
    private fun entry(serverId: String): KeyStore.PrivateKeyEntry {
        val alias = alias(serverId)
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
        return keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    override fun publicKey(serverId: String): ByteArray = entry(serverId).certificate.publicKey.encoded

    override fun sign(serverId: String, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(entry(serverId).privateKey)
            update(message)
            sign()
        }

    @Synchronized
    override fun forget(serverId: String) {
        runCatching { keyStore.deleteEntry(alias(serverId)) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
