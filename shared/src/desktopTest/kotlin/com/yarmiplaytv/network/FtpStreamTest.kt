package com.yarmiplaytv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class FtpStreamTest {
    @Test
    fun listsAndReadsWithoutFollowingAPasvAddress() {
        val server = MiniFtp(mapOf("movie.mkv" to "0123456789".toByteArray(), "movie.srt" to "hi".toByteArray()))
        server.use {
            val client = FtpClient(it.host, it.port, "guest", "secret")
            client.use { ftp ->
                val names = ftp.list("").map { entry -> entry.name }
                assertTrue(names.contains("movie.mkv"))
                assertEquals("0123456789", String(ftp.readFully("movie.mkv", 1000)))
                val reader = ftp.reader("movie.mkv", 10)
                val buffer = ByteArray(4)
                assertEquals(4, reader.read(6, buffer, 4))
                assertEquals("6789", String(buffer))
            }
        }
    }

    @Test
    fun parseListLines() {
        val unix = FtpClient.parseListLine("-rw-r--r-- 1 owner group 12 Nov 19 16:31 movie.mkv")
        assertEquals(FtpListEntry("movie.mkv", false, 12), unix)
        val dir = FtpClient.parseListLine("drwxr-xr-x 2 owner group 4096 Nov 19 16:31 Season 1")
        assertEquals(FtpListEntry("Season 1", true, 4096), dir)
        val windows = FtpClient.parseListLine("01-02-23  03:04PM             1234 clip.mp4")
        assertEquals(FtpListEntry("clip.mp4", false, 1234), windows)
    }

    @Test
    fun loopbackServesOnlyThePublishedToken() {
        val stream = LoopbackStream()
        assertTrue(stream.port > 0)
        val url = stream.publish("clip.mkv", 5, RemoteReader { offset, buffer, length ->
            val data = "abcde".toByteArray()
            if (offset >= data.size) return@RemoteReader 0
            val n = minOf(length, data.size - offset.toInt())
            data.copyInto(buffer, 0, offset.toInt(), offset.toInt() + n)
            n
        })
        val body = http(url, "bytes=1-3")
        assertTrue(body.startsWith("HTTP/1.1 206"))
        assertTrue(body.contains("bcd"))
        val missing = http(url.replace("/t/", "/t/nope"), null)
        assertTrue(missing.startsWith("HTTP/1.1 404"))
    }

    private fun http(url: String, range: String?): String {
        val parsed = java.net.URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(parsed.host, parsed.port), 2000)
            val out = socket.getOutputStream().bufferedWriter()
            val rangeLine = if (range == null) "" else "Range: $range\r\n"
            out.write("GET ${parsed.path} HTTP/1.1\r\nHost: 127.0.0.1\r\n${rangeLine}Connection: close\r\n\r\n")
            out.flush()
            return socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        }
    }
}

/**
 * One-connection FTP server. PASV advertises 203.0.113.9, which is not this machine; a client that followed
 * that address would miss the transfer.
 */
private class MiniFtp(private val files: Map<String, ByteArray>) : AutoCloseable {
    private val control = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int = control.localPort
    val host: String = "127.0.0.1"
    private val thread = Thread {
        val socket = control.accept()
        socket.use { serve(it) }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val writer = socket.getOutputStream().bufferedWriter()
        reply(writer, "220 ready")
        var data: ServerSocket? = null
        var restart = 0
        while (true) {
            val line = reader.readLine() ?: break
            val command = line.substringBefore(' ').uppercase()
            val argument = line.substringAfter(' ', "")
            when (command) {
                "USER" -> reply(writer, "331 password")
                "PASS" -> reply(writer, "230 logged in")
                "TYPE" -> reply(writer, "200 type")
                "PASV" -> {
                    data?.close()
                    data = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    val p = data.localPort
                    reply(writer, "227 Entering Passive Mode (203,0,113,9,${p / 256},${p % 256})")
                }
                "LIST" -> {
                    val server = data ?: continue
                    reply(writer, "150 listing")
                    val accepted = server.accept()
                    accepted.use {
                        val listing = files.keys.joinToString("") { name -> "-rw-r--r-- 1 o g ${files.getValue(name).size} Nov 19 16:31 $name\r\n" }
                        it.getOutputStream().write(listing.toByteArray())
                    }
                    reply(writer, "226 done")
                }
                "SIZE" -> {
                    val bytes = files[argument]
                    if (bytes == null) reply(writer, "550 missing") else reply(writer, "213 ${bytes.size}")
                }
                "REST" -> {
                    restart = argument.toIntOrNull() ?: 0
                    reply(writer, "350 restart")
                }
                "RETR" -> {
                    val server = data ?: continue
                    val bytes = files[argument]
                    if (bytes == null) {
                        reply(writer, "550 missing")
                    } else {
                        reply(writer, "150 file")
                        val from = restart.coerceIn(0, bytes.size)
                        restart = 0
                        val accepted = server.accept()
                        accepted.use { it.getOutputStream().write(bytes, from, bytes.size - from) }
                        reply(writer, "226 done")
                    }
                }
                "QUIT" -> {
                    reply(writer, "221 bye")
                    break
                }
                else -> reply(writer, "502 no")
            }
        }
        data?.close()
    }

    private fun reply(writer: BufferedWriter, line: String) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    override fun close() {
        control.close()
        thread.join(2000)
    }
}
