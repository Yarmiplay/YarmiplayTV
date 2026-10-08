package com.yarmiplaytv.device

import com.yarmiplaytv.syncplay.Yarmiplay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class FileDeviceKeyStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val serverA = "9b1f0c2e4d6a8b0c1e3f5a7b9c0d2e4f"
    private val serverB = "0123456789abcdef0123456789abcdef"

    private fun verifies(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(signature) }
    }

    @Test
    fun `a key is P-256 and its signatures check out with its own public key`() {
        val store = FileDeviceKeyStore(tmp.root.resolve("device-keys"))
        val spki = store.publicKey(serverA)
        assertTrue(spki.joinToString("") { "%02x".format(it) }.startsWith(Yarmiplay.P256_SPKI_PREFIX))
        val message = Yarmiplay.signedMessage(serverA, "q83vEjRWeJA0Vni8mY7cE3A2m9vLz5n0kQ1y8f3dXeM=")
        assertTrue(verifies(spki, message, store.sign(serverA, message)))
        assertFalse(verifies(spki, "something else".toByteArray(), store.sign(serverA, message)))
    }

    @Test
    fun `each server gets its own key, kept across restarts until it is forgotten`() {
        val dir = tmp.root.resolve("device-keys")
        val store = FileDeviceKeyStore(dir)
        val a = store.publicKey(serverA)
        val b = store.publicKey(serverB)
        assertFalse(a.contentEquals(b))
        assertTrue(dir.resolve("$serverA.p8").isFile)
        assertArrayEquals(a, FileDeviceKeyStore(dir).publicKey(serverA))

        store.forget(serverA)
        assertFalse(dir.resolve("$serverA.p8").exists())
        assertFalse(a.contentEquals(store.publicKey(serverA)))
        assertArrayEquals(b, store.publicKey(serverB))
    }

    @Test
    fun `only well-formed server ids reach the file system`() {
        val dir = tmp.root.resolve("device-keys")
        val store = FileDeviceKeyStore(dir)
        for (bad in listOf("../../etc/passwd", serverA.uppercase(), serverA.dropLast(1), "$serverA/x", "")) {
            assertTrue(bad, runCatching { store.publicKey(bad) }.isFailure)
            assertTrue(bad, runCatching { store.forget(bad) }.isFailure)
        }
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    @Test
    fun `key files are owner-only where the file system has permissions`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val dir = tmp.root.resolve("device-keys")
        FileDeviceKeyStore(dir).publicKey(serverA)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("$serverA.p8").toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
    }

    @Test
    fun `the device name the server sees is trimmed to 60 characters`() {
        val store = FileDeviceKeyStore(tmp.root)
        assertEquals("Living room TV", DeviceKeys(store) { "  Living room TV " }.deviceName)
        assertEquals(60, DeviceKeys(store) { "x".repeat(80) }.deviceName.length)
    }
}
