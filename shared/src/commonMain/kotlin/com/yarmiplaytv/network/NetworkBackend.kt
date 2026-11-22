package com.yarmiplaytv.network

import java.io.File

/** Bytes of one remote file, read from any offset. Implementations must tolerate overlapping calls. */
fun interface RemoteReader {
    /** Reads up to [length] bytes at [offset] into the start of [buffer]. Zero means end of file. */
    fun read(offset: Long, buffer: ByteArray, length: Int): Int
}

/** A remote file opened for streaming, plus subtitle files small enough to keep beside it. */
class OpenedRemote(
    val size: Long,
    val reader: RemoteReader,
    val sidecars: List<NamedBytes>,
    private val release: () -> Unit = {},
) : AutoCloseable {
    override fun close() = release()
}

data class NamedBytes(val name: String, val bytes: ByteArray)

/**
 * Lists an FTP or SMB place and reads one file from it. [publish] serves those bytes on this machine only,
 * so the player can open them over HTTP without the remote password appearing in the address.
 */
interface NetworkBackend {
    suspend fun list(location: NetworkLocation, directory: String): List<NetworkEntry>

    suspend fun open(location: NetworkLocation, path: String): OpenedRemote

    /**
     * `http://127.0.0.1:<port>/t/<token>/<file name>`. Replaces the file published before it, and only then
     * runs that file's [release] (the remote connection has to stay open while the player is reading).
     */
    fun publish(fileName: String, size: Long, reader: RemoteReader, release: () -> Unit = {}): String

    /** Drops the file [publish] is serving, and closes its remote connection. */
    fun releasePublished()
}

expect fun networkBackend(cacheDir: File): NetworkBackend
