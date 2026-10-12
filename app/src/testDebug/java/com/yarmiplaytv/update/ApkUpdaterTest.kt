package com.yarmiplaytv.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ApkUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a download matching its checksum is kept, with progress up to 100 percent`() = runBlocking {
        val target = tmp.root.resolve("a.apk")
        val progress = mutableListOf<Float>()
        ApkUpdater.copyVerified(bytes.inputStream(), bytes.size.toLong(), sha.uppercase(), target) { progress += it }
        assertTrue(target.readBytes().contentEquals(bytes))
        assertEquals(1f, progress.last())
    }

    @Test
    fun `a damaged download is refused and deleted`() = runBlocking {
        val target = tmp.root.resolve("b.apk")
        val damaged = bytes.copyOf().also { it[1000] = 7 }
        val error = runCatching { ApkUpdater.copyVerified(damaged.inputStream(), -1, sha, target) {} }.exceptionOrNull()
        assertEquals("the download is damaged (checksum mismatch)", error?.message)
        assertFalse(target.exists())
    }
}
