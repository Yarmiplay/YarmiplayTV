package com.yarmiplaytv.sync

import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** A Syncplay room with one client: the playlist [files] with the first selected; selections are echoed back. */
internal class ScriptedRoom(private val files: List<String>) : AutoCloseable {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    val selections: MutableList<Int> = Collections.synchronizedList(ArrayList())
    @Volatile private var out: PrintWriter? = null

    init {
        thread(isDaemon = true) {
            runCatching {
                val client: Socket = socket.accept()
                val reader: BufferedReader = client.getInputStream().bufferedReader()
                val writer = PrintWriter(client.getOutputStream().writer(), true)
                out = writer
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.contains("\"Hello\"") -> {
                            send(
                                """{"Hello":{"username":"ana","room":{"name":"movie night"},"version":"1.2.255","realversion":"1.7.0",""" +
                                    """"features":{"sharedPlaylists":true,"chat":true,"readiness":true},"motd":""}}""",
                            )
                            val list = files.joinToString(",") { "\"$it\"" }
                            send("""{"Set":{"playlistChange":{"user":"bob","files":[$list]}}}""")
                            select(0, by = "bob")
                        }
                        line.contains("\"playlistIndex\"") -> {
                            val index = Regex("\"index\":\\s*(\\d+)").find(line)!!.groupValues[1].toInt()
                            selections += index
                            select(index, by = "ana")
                        }
                    }
                }
            }
        }
    }

    fun select(index: Int, by: String) = send("""{"Set":{"playlistIndex":{"user":"$by","index":$index}}}""")

    @Synchronized
    private fun send(json: String) {
        out?.apply { print(json + "\r\n"); flush() }
    }

    override fun close() = socket.close()
}
