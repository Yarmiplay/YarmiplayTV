package com.yarmiplaytv.device

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Keys as files in [dir]: `<serverId>.p8` (the private key, PKCS#8 DER) and `<serverId>.pub` (its
 * SubjectPublicKeyInfo), owner-only where the file system has POSIX permissions; on Windows [dir] is in the
 * user's own app data.
 */
class FileDeviceKeyStore(private val dir: File) : DeviceKeyStore {
    private fun files(serverId: String): Pair<Path, Path> {
        val id = DeviceKeyStore.checked(serverId)
        return dir.toPath().resolve("$id.p8") to dir.toPath().resolve("$id.pub")
    }

    @Synchronized
    private fun keys(serverId: String): Pair<PrivateKey, ByteArray> {
        val (private, public) = files(serverId)
        if (Files.isRegularFile(private) && Files.isRegularFile(public)) {
            val key = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(private))) }.getOrNull()
            if (key != null) return key to Files.readAllBytes(public)
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        createPrivateDir(dir.toPath())
        writeOwnerOnly(private, pair.private.encoded)
        writeOwnerOnly(public, pair.public.encoded)
        return pair.private to pair.public.encoded
    }

    override fun publicKey(serverId: String): ByteArray = keys(serverId).second

    override fun sign(serverId: String, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keys(serverId).first)
            update(message)
            sign()
        }

    @Synchronized
    override fun forget(serverId: String) {
        val (private, public) = files(serverId)
        Files.deleteIfExists(private)
        Files.deleteIfExists(public)
    }

    private companion object {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

        fun createPrivateDir(path: Path) {
            if (Files.isDirectory(path)) return
            if (posix) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else Files.createDirectories(path)
        }

        fun writeOwnerOnly(path: Path, bytes: ByteArray) {
            Files.deleteIfExists(path)
            if (posix) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }
}
