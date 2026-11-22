package com.yarmiplaytv.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal data class SmbListEntry(val name: String, val directory: Boolean, val size: Long)

/**
 * SMB 2/3 only (what current Windows and NAS shares speak). DFS referrals stay off, so a share can't send
 * the connection on to a different server. SMB 1 is not used.
 */
internal class SmbFiles(
    host: String,
    port: Int,
    username: String,
    password: String,
    private val shareName: String,
) : AutoCloseable {
    private val client: SMBClient
    private val connection: com.hierynomus.smbj.connection.Connection
    private val session: com.hierynomus.smbj.session.Session
    private val share: DiskShare

    init {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(20, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            .build()
        client = SMBClient(config)
        try {
            connection = client.connect(host, port)
            val auth = if (username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                val domain = username.substringBefore('\\', "").takeIf { '\\' in username }.orEmpty()
                val user = username.substringAfter('\\')
                AuthenticationContext(user, password.toCharArray(), domain)
            }
            session = connection.authenticate(auth)
            val connected = session.connectShare(shareName)
            share = connected as? DiskShare ?: throw java.io.IOException("That SMB share isn't a folder")
        } catch (e: java.io.IOException) {
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
            throw java.io.IOException(e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "Couldn't open the SMB share", e)
        }
    }

    fun list(directory: String): List<SmbListEntry> {
        val path = toSmb(directory)
        val entries = try {
            share.list(path)
        } catch (e: Exception) {
            throw java.io.IOException("SMB can't list that folder", e)
        }
        return entries.mapNotNull(::entry).filter { it.name != "." && it.name != ".." }.take(MAX_LIST)
    }

    fun size(path: String): Long = try {
        share.getFileInformation(toSmb(path)).standardInformation.endOfFile
    } catch (e: Exception) {
        throw java.io.IOException("SMB can't tell how big that file is", e)
    }

    fun readFully(path: String, maxBytes: Long): ByteArray {
        val known = size(path)
        if (known > maxBytes) throw java.io.IOException("Subtitle is too large")
        val file = open(path)
        file.use {
            val out = ByteArray(known.toInt())
            var offset = 0
            while (offset < out.size) {
                val n = it.read(out, offset.toLong(), offset, out.size - offset)
                if (n <= 0) return out.copyOf(offset)
                offset += n
            }
            return out
        }
    }

    fun reader(path: String): RemoteReader {
        val file = open(path)
        return RemoteReader { offset, buffer, length ->
            val read = file.read(buffer, offset, 0, length)
            if (read <= 0) 0 else read
        }
    }

    private fun open(path: String) = share.openFile(
        toSmb(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
        SMB2CreateDisposition.FILE_OPEN,
        emptySet(),
    )

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private fun entry(info: FileIdBothDirectoryInformation): SmbListEntry? {
        val name = info.fileName ?: return null
        if (name.isEmpty()) return null
        val directory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return SmbListEntry(name, directory, if (directory) 0L else info.endOfFile)
    }

    private fun toSmb(relative: String): String = relative.replace('/', '\\')

    private companion object {
        const val MAX_LIST = 5_000
    }
}
