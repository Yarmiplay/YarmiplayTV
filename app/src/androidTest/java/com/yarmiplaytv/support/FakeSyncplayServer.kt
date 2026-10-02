package com.yarmiplaytv.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal Syncplay server for deterministic UI states: one room, scripted peers that aren't real
 * connections, and a room playstate the test controls. It sends no ping timestamps, so the client's
 * round-trip time stays 0 and isn't shown.
 */
class FakeSyncplayServer(val port: Int = PORT) : AutoCloseable {
    data class PeerFile(val name: String, val duration: Double, val size: Long)
    private data class Peer(val name: String, var file: PeerFile?, var ready: Boolean?)

    private inner class Connection(val socket: Socket) {
        val out = PrintWriter(socket.getOutputStream().bufferedWriter(), true)
        var username: String? = null
        var file: PeerFile? = null
        var ready: Boolean? = null

        fun send(message: JsonObject) = synchronized(out) { out.println(message.toString()) }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val server = bindWithRetry()

    /** The previous test class's server can still hold the port for a moment after closing. */
    private fun bindWithRetry(): ServerSocket {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val socket = ServerSocket().apply { reuseAddress = true }
            try {
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
                return socket
            } catch (e: BindException) {
                socket.close()
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(250)
            }
        }
    }
    private val connections = CopyOnWriteArrayList<Connection>()
    private val peers = LinkedHashMap<String, Peer>()
    @Volatile private var closed = false

    val room = ROOM
    @Volatile var paused = true
        private set
    @Volatile var position = 0.0
        private set
    private var setBy: String? = null
    private var playlist: List<String> = emptyList()
    private var playlistIndex: Int? = null

    val connectedUsers: List<String> get() = connections.mapNotNull { it.username }

    init {
        thread(name = "fake-syncplay-accept", isDaemon = true) {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val c = Connection(socket)
                connections += c
                thread(name = "fake-syncplay-conn", isDaemon = true) { serve(c) }
            }
        }
        thread(name = "fake-syncplay-state", isDaemon = true) {
            while (!closed) {
                Thread.sleep(1000)
                connections.filter { it.username != null }.forEach { it.send(stateMessage()) }
            }
        }
    }

    // --- Scripted room ----------------------------------------------------------------

    @Synchronized
    fun addPeer(name: String, file: PeerFile? = null, ready: Boolean? = false) {
        peers[name] = Peer(name, file, ready)
        broadcast(set { putJsonObject("user") { putJsonObject(name) { roomObj(); put("event", buildJsonObject { put("joined", true) }) } } })
        if (file != null) broadcast(userFile(name, file))
        broadcast(set { putJsonObject("ready") { put("username", name); ready?.let { put("isReady", it) }; put("manuallyInitiated", true) } })
    }

    @Synchronized
    fun peerChat(name: String, message: String) = broadcast(buildJsonObject { putJsonObject("Chat") { put("username", name); put("message", message) } })

    @Synchronized
    fun peerSetPlaylist(name: String, files: List<String>, index: Int?) {
        playlist = files
        broadcast(playlistChange(name))
        if (index != null) {
            playlistIndex = index
            broadcast(playlistIndexMessage(name))
        }
    }

    @Synchronized
    fun setState(paused: Boolean, position: Double, by: String? = null) {
        this.paused = paused
        this.position = position
        setBy = by
        connections.filter { it.username != null }.forEach { it.send(stateMessage(doSeek = true)) }
    }

    /** Forgets the room's playlist, peers and playstate (between tests); connected clients are dropped. */
    @Synchronized
    fun reset() {
        connections.forEach { runCatching { it.socket.close() } }
        connections.clear()
        peers.clear()
        playlist = emptyList()
        playlistIndex = null
        paused = true
        position = 0.0
        setBy = null
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        connections.forEach { runCatching { it.socket.close() } }
    }

    // --- Protocol ---------------------------------------------------------------------

    private fun serve(c: Connection) {
        try {
            val reader = BufferedReader(InputStreamReader(c.socket.getInputStream()))
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                synchronized(this) { handle(c, message) }
            }
        } catch (_: Exception) {
        } finally {
            connections.remove(c)
            runCatching { c.socket.close() }
            c.username?.let { name ->
                broadcast(set { putJsonObject("user") { putJsonObject(name) { roomObj(); put("event", buildJsonObject { put("left", true) }) } } })
            }
        }
    }

    private fun handle(c: Connection, message: JsonObject) {
        for ((command, value) in message) {
            when (command) {
                "TLS" -> c.send(buildJsonObject { putJsonObject("TLS") { put("startTLS", "false") } })
                "Hello" -> hello(c, value.jsonObject)
                "Set" -> handleSet(c, value.jsonObject)
                "List" -> c.send(listMessage())
                "State" -> handleState(c, value.jsonObject)
                "Chat" -> {
                    val text = value.jsonPrimitive.contentOrNull ?: continue
                    broadcast(buildJsonObject { putJsonObject("Chat") { put("username", c.username ?: "?"); put("message", text) } })
                }
            }
        }
    }

    private fun hello(c: Connection, hello: JsonObject) {
        val name = hello["username"]?.jsonPrimitive?.contentOrNull ?: "user"
        c.username = name
        c.send(buildJsonObject {
            putJsonObject("Hello") {
                put("username", name)
                putJsonObject("room") { put("name", room) }
                put("version", "1.2.255")
                put("realversion", "1.7.3")
                put("motd", "")
                putJsonObject("features") {
                    put("isolateRooms", false)
                    put("readiness", true)
                    put("managedRooms", true)
                    put("persistentRooms", false)
                    put("chat", true)
                    put("maxChatMessageLength", 150)
                    put("maxUsernameLength", 16)
                    put("maxRoomNameLength", 35)
                    put("maxFilenameLength", 250)
                }
            }
        })
        broadcast(set { putJsonObject("user") { putJsonObject(name) { roomObj(); put("event", buildJsonObject { put("joined", true) }) } } }, except = c)
        if (playlist.isNotEmpty()) {
            c.send(playlistChange(null))
            if (playlistIndex != null) c.send(playlistIndexMessage(null))
        }
        c.send(stateMessage())
    }

    private fun handleSet(c: Connection, set: JsonObject) {
        val name = c.username ?: return
        for ((command, value) in set) {
            val o = value as? JsonObject ?: continue
            when (command) {
                "file" -> {
                    val file = PeerFile(
                        o["name"]?.jsonPrimitive?.contentOrNull ?: continue,
                        o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        o["size"]?.jsonPrimitive?.let { it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L,
                    )
                    c.file = file
                    broadcast(userFile(name, file))
                }
                "ready" -> {
                    c.ready = o["isReady"]?.jsonPrimitive?.booleanOrNull
                    broadcast(set {
                        putJsonObject("ready") {
                            put("username", name)
                            c.ready?.let { put("isReady", it) }
                            put("manuallyInitiated", o["manuallyInitiated"]?.jsonPrimitive?.booleanOrNull ?: false)
                        }
                    })
                }
                "playlistChange" -> {
                    playlist = (o["files"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: continue
                    broadcast(playlistChange(name))
                }
                "playlistIndex" -> {
                    playlistIndex = o["index"]?.jsonPrimitive?.intOrNull
                    broadcast(playlistIndexMessage(name))
                }
            }
        }
    }

    private fun handleState(c: Connection, state: JsonObject) {
        val ignoring = state["ignoringOnTheFly"] as? JsonObject
        val client = ignoring?.get("client")?.jsonPrimitive?.intOrNull ?: return
        // A user-initiated pause/seek: adopt it like the real server and confirm it back.
        (state["playstate"] as? JsonObject)?.let { ps ->
            paused = ps["paused"]?.jsonPrimitive?.booleanOrNull ?: paused
            position = ps["position"]?.jsonPrimitive?.doubleOrNull ?: position
            setBy = c.username
        }
        c.send(buildJsonObject {
            putJsonObject("State") {
                playstate(false)
                putJsonObject("ignoringOnTheFly") { put("client", client) }
            }
        })
        connections.filter { it !== c && it.username != null }.forEach { it.send(stateMessage(doSeek = true)) }
    }

    // --- Messages -----------------------------------------------------------------------

    private fun broadcast(message: JsonObject, except: Connection? = null) =
        connections.filter { it !== except && it.username != null }.forEach { it.send(message) }

    private fun set(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject { putJsonObject("Set") { block() } }

    private fun kotlinx.serialization.json.JsonObjectBuilder.roomObj() = putJsonObject("room") { put("name", room) }

    private fun userFile(name: String, file: PeerFile) = set {
        putJsonObject("user") { putJsonObject(name) { roomObj(); put("file", fileObj(file)) } }
    }

    private fun fileObj(file: PeerFile?): JsonElement = if (file == null) JsonObject(emptyMap()) else buildJsonObject {
        put("name", file.name)
        put("duration", file.duration)
        put("size", file.size)
    }

    private fun playlistChange(user: String?) = set {
        putJsonObject("playlistChange") {
            put("user", user?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("files") { playlist.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun playlistIndexMessage(user: String?) = set {
        putJsonObject("playlistIndex") {
            put("user", user?.let(::JsonPrimitive) ?: JsonNull)
            put("index", playlistIndex)
        }
    }

    private fun listMessage() = buildJsonObject {
        putJsonObject("List") {
            putJsonObject(room) {
                connections.forEach { c ->
                    val name = c.username ?: return@forEach
                    putJsonObject(name) { userEntry(c.file, c.ready) }
                }
                peers.values.forEach { p -> putJsonObject(p.name) { userEntry(p.file, p.ready) } }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.userEntry(file: PeerFile?, ready: Boolean?) {
        put("position", 0)
        put("file", fileObj(file))
        put("controller", false)
        put("isReady", ready?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("features") { put("sharedPlaylists", true); put("chat", true); put("readiness", true) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.playstate(doSeek: Boolean) = putJsonObject("playstate") {
        put("position", position)
        put("paused", paused)
        put("doSeek", doSeek)
        put("setBy", setBy?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun stateMessage(doSeek: Boolean = false) = buildJsonObject {
        putJsonObject("State") {
            playstate(doSeek)
            putJsonObject("ping") { put("latencyCalculation", System.currentTimeMillis() / 1000.0); put("serverRtt", 0) }
        }
    }

    companion object {
        const val PORT = 18999
        const val ROOM = "movie-night"
    }
}
