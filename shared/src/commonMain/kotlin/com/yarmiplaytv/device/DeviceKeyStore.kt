package com.yarmiplaytv.device

import com.yarmiplaytv.syncplay.DeviceAuth
import com.yarmiplaytv.syncplay.Yarmiplay

/**
 * One P-256 key pair per YarmiplayServerTV (by its server id), proving to that server's host that this is the
 * device they approved. Private keys never leave the store.
 */
interface DeviceKeyStore {
    /** The SubjectPublicKeyInfo DER of [serverId]'s key, created on first use. */
    fun publicKey(serverId: String): ByteArray

    /** A DER ECDSA signature (SHA256withECDSA) over [message] with [serverId]'s key. */
    fun sign(serverId: String, message: ByteArray): ByteArray

    /** Deletes [serverId]'s key; the next connection makes a new one, which the host has to approve again. */
    fun forget(serverId: String)

    companion object {
        /** Server ids end up in key aliases and file names, so only well-formed ones are used. */
        fun checked(serverId: String): String {
            require(Yarmiplay.isValidServerId(serverId)) { "Invalid server id" }
            return serverId
        }
    }
}

/** [DeviceAuth] for the protocol, with the device name the user chose (or the platform's). */
class DeviceKeys(private val store: DeviceKeyStore, private val name: () -> String) : DeviceAuth {
    override val deviceName: String get() = name().trim().take(60)
    override fun publicKey(serverId: String): ByteArray = store.publicKey(serverId)
    override fun sign(serverId: String, message: ByteArray): ByteArray = store.sign(serverId, message)
}
