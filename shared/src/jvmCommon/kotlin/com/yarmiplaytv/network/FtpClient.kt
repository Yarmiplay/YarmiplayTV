package com.yarmiplaytv.network

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

internal data class FtpListEntry(val name: String, val directory: Boolean, val size: Long)

/**
 * A small FTP client. Data connections go back to the same host as the control connection: the address in a
 * PASV reply is ignored, so a server can't point the transfer at some other machine.
 */
internal class FtpClient(
    private val host: String,
    private val port: Int,
    username: String,
    password: String,
    private val timeoutMs: Int = 15_000,
) : Closeable {
    private val control = Socket()
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private val lock = Any()

    init {
        control.connect(InetSocketAddress(host, port), timeoutMs)
        control.soTimeout = timeoutMs
        reader = control.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        writer = control.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
        val greet = readReply()
        if (greet.code != 220) throw java.io.IOException("FTP server refused the connection")
        val user = command("USER ${sanitize(username.ifBlank { "anonymous" })}")
        if (user.code == 331) {
            val pass = command("PASS ${sanitize(password.ifBlank { "yarmiplaytv" })}")
            if (pass.code != 230) throw java.io.IOException("FTP login failed")
        } else if (user.code != 230) {
            throw java.io.IOException("FTP login failed")
        }
        command("TYPE I")
    }

    fun list(path: String): List<FtpListEntry> = synchronized(lock) {
        val data = openData()
        val listed = command(if (path.isEmpty()) "LIST" else "LIST ${sanitize(path)}")
        if (listed.code != 150 && listed.code != 125) {
            data.close()
            throw java.io.IOException("FTP can't list that folder")
        }
        val text = data.use { it.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText() }
        finishTransfer()
        text.lineSequence().mapNotNull(::parseListLine).filter { it.name != "." && it.name != ".." }.take(MAX_LIST).toList()
    }

    fun readFully(path: String, maxBytes: Long): ByteArray = synchronized(lock) {
        val known = size(path)
        if (known > maxBytes) throw java.io.IOException("Subtitle is too large")
        val data = openData()
        val reply = command("RETR ${sanitize(path)}")
        if (reply.code != 150 && reply.code != 125) {
            data.close()
            throw java.io.IOException("FTP can't read that file")
        }
        val bytes = data.use { it.getInputStream().readBytes() }
        finishTransfer()
        if (bytes.size > maxBytes) throw java.io.IOException("Subtitle is too large")
        bytes
    }

    fun size(path: String): Long = synchronized(lock) {
        val reply = command("SIZE ${sanitize(path)}")
        if (reply.code != 213) throw java.io.IOException("FTP can't tell how big that file is")
        reply.message.trim().substringBefore(' ').toLongOrNull() ?: throw java.io.IOException("FTP can't tell how big that file is")
    }

    /**
     * Reads [length] bytes at [offset]. Keeps one transfer open while the player reads straight through,
     * and starts a new one when it seeks.
     */
    fun reader(path: String, size: Long): RemoteReader {
        val client = this
        return object : RemoteReader {
            private var stream: InputStream? = null
            private var position = -1L

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(lock) {
                if (offset >= size) return 0
                val current = stream
                if (current == null || offset != position) {
                    if (current != null) {
                        current.close()
                        abandonTransfer()
                    }
                    stream = openRetr(path, offset)
                    position = offset
                }
                val input = stream ?: return 0
                var got = 0
                while (got < length) {
                    val n = input.read(buffer, got, length - got)
                    if (n < 0) {
                        input.close()
                        stream = null
                        break
                    }
                    got += n
                }
                position += got
                got
            }

            private fun openRetr(path: String, offset: Long): InputStream {
                val data = client.openData()
                if (offset > 0) {
                    val rest = client.command("REST $offset")
                    if (rest.code != 350) {
                        data.close()
                        throw java.io.IOException("FTP server can't resume")
                    }
                }
                val reply = client.command("RETR ${sanitize(path)}")
                if (reply.code != 150 && reply.code != 125) {
                    data.close()
                    throw java.io.IOException("FTP can't read that file")
                }
                return data.getInputStream()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { command("QUIT") }
            control.close()
        }
    }

    private fun openData(): Socket {
        val reply = command("PASV")
        if (reply.code != 227) throw java.io.IOException("FTP server has no passive transfer")
        val numbers = Regex("""\(([\d,]+)\)""").find(reply.message)?.groupValues?.get(1)?.split(',')
            ?: throw java.io.IOException("FTP server has no passive transfer")
        if (numbers.size != 6) throw java.io.IOException("FTP server has no passive transfer")
        val dataPort = numbers[4].toInt() * 256 + numbers[5].toInt()
        if (dataPort !in 1..65535) throw java.io.IOException("FTP server has no passive transfer")
        return Socket().apply {
            connect(InetSocketAddress(host, dataPort), timeoutMs)
            soTimeout = timeoutMs
        }
    }

    private fun finishTransfer() {
        val done = readReply()
        if (done.code != 226 && done.code != 250) throw java.io.IOException("FTP transfer didn't finish")
    }

    /** The server answers 426, sometimes followed by 226, when the data connection closes before the file ends. */
    private fun abandonTransfer() {
        val done = readReply()
        if (done.code == 426) {
            val next = readReply()
            if (next.code != 226 && next.code != 250) throw java.io.IOException("FTP transfer didn't finish")
        }
    }

    private fun command(line: String): Reply {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
        return readReply()
    }

    private fun readReply(): Reply {
        val first = reader.readLine() ?: throw java.io.IOException("FTP server closed the connection")
        if (first.length < 4) throw java.io.IOException("FTP server sent a reply that can't be read")
        val code = first.take(3).toIntOrNull() ?: throw java.io.IOException("FTP server sent a reply that can't be read")
        if (first[3] != '-') return Reply(code, first.substring(4))
        val lines = StringBuilder(first.substring(4))
        while (true) {
            val line = reader.readLine() ?: throw java.io.IOException("FTP server closed the connection")
            if (line.startsWith("$code ")) return Reply(code, lines.toString())
            lines.append('\n').append(line.removePrefix("$code-"))
        }
    }

    private data class Reply(val code: Int, val message: String)

    companion object {
        private const val MAX_LIST = 5_000

        /** A command argument with no line break, so it can't smuggle a second command. */
        fun sanitize(value: String): String {
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) throw java.io.IOException("That name can't be used")
            return value
        }

        fun parseListLine(line: String): FtpListEntry? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) return null
            parseUnix(trimmed)?.let { return it }
            return parseWindows(trimmed)
        }

        private fun parseUnix(line: String): FtpListEntry? {
            val match = Regex("""^([dl\-])[A-Za-z\-]+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(.+)$""").matchEntire(line)
                ?: return null
            val name = match.groupValues[3].substringAfter(" -> ").trim()
            if (name.isEmpty()) return null
            return FtpListEntry(name, match.groupValues[1] == "d", match.groupValues[2].toLongOrNull() ?: 0L)
        }

        private fun parseWindows(line: String): FtpListEntry? {
            val match = Regex("""^\d{2}-\d{2}-\d{2}\s+\d{2}:\d{2}(?:AM|PM)\s+(<DIR>|\d+)\s+(.+)$""", RegexOption.IGNORE_CASE)
                .matchEntire(line) ?: return null
            val marker = match.groupValues[1]
            val directory = marker.equals("<DIR>", ignoreCase = true)
            return FtpListEntry(match.groupValues[2].trim(), directory, if (directory) 0L else marker.toLongOrNull() ?: 0L)
        }
    }
}
