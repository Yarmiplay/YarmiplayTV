package com.yarmiplaytv.network

import com.yarmiplaytv.media.subtitleSiblings
import java.io.File

internal class JvmNetworkBackend(@Suppress("UNUSED_PARAMETER") cacheDir: File) : NetworkBackend {
    private val stream = LoopbackStream()

    override suspend fun list(location: NetworkLocation, directory: String): List<NetworkEntry> =
        when (location.protocol) {
            NetworkLocations.FTP -> ftp(location).use { client ->
                client.list(directory).map { NetworkEntry(it.name, it.directory, it.size) }
            }
            NetworkLocations.SMB -> smb(location).use { files ->
                files.list(directory).map { NetworkEntry(it.name, it.directory, it.size) }
            }
            else -> throw IllegalArgumentException("Unknown network type")
        }

    override suspend fun open(location: NetworkLocation, path: String): OpenedRemote {
        val directory = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return when (location.protocol) {
            NetworkLocations.FTP -> {
                val client = ftp(location)
                val entries = runCatching { client.list(directory) }.getOrElse {
                    client.close()
                    throw it
                }
                val file = entries.firstOrNull { !it.directory && it.name == name }
                    ?: run { client.close(); throw java.io.IOException("That file isn't there") }
                val sidecars = sidecarBytes(entries.filter { !it.directory }.map { it.name }, name) { sidecar ->
                    client.readFully(join(directory, sidecar), MAX_SUBTITLE)
                }
                OpenedRemote(file.size, client.reader(path, file.size), sidecars) { client.close() }
            }
            NetworkLocations.SMB -> {
                val files = smb(location)
                val entries = runCatching { files.list(directory) }.getOrElse {
                    files.close()
                    throw it
                }
                val file = entries.firstOrNull { !it.directory && it.name == name }
                    ?: run { files.close(); throw java.io.IOException("That file isn't there") }
                val sidecars = sidecarBytes(entries.filter { !it.directory }.map { it.name }, name) { sidecar ->
                    files.readFully(join(directory, sidecar), MAX_SUBTITLE)
                }
                OpenedRemote(file.size, files.reader(path), sidecars) { files.close() }
            }
            else -> throw IllegalArgumentException("Unknown network type")
        }
    }

    override fun publish(fileName: String, size: Long, reader: RemoteReader, release: () -> Unit): String =
        stream.publish(fileName, size, reader, release)

    override fun releasePublished() = stream.release()

    private fun ftp(location: NetworkLocation) = FtpClient(
        location.host,
        location.port,
        location.username,
        location.password,
    )

    private fun smb(location: NetworkLocation) = SmbFiles(
        location.host,
        location.port,
        location.username,
        location.password,
        location.share,
    )

    private fun join(directory: String, name: String): String =
        if (directory.isEmpty()) name else "$directory/$name"

    private fun sidecarBytes(names: List<String>, videoName: String, read: (String) -> ByteArray): List<NamedBytes> {
        val indexes = subtitleSiblings(videoName, names)
        val bitmaps = indexes.mapNotNull { name ->
            if (!name.endsWith(".idx", ignoreCase = true)) return@mapNotNull null
            val sub = name.dropLast(3) + "sub"
            names.firstOrNull { it.equals(sub, ignoreCase = true) }
        }
        val matching = indexes + bitmaps
        return matching.mapNotNull { name ->
            runCatching { NamedBytes(name, read(name)) }.getOrNull()
        }
    }

    private companion object {
        const val MAX_SUBTITLE = 8L * 1024 * 1024
    }
}
