package com.yarmiplaytv.syncplay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/**
 * Syncplay client: connects to a server, joins a room and keeps [player] in sync with it.
 *
 * All protocol and sync state is confined to a single-threaded dispatcher; public methods
 * can be called from any thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncplayClient(
    config: SyncplayConfig,
    private val player: PlayerAdapter,
    settings: SyncSettings = SyncSettings(),
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val transportFactory: (host: String, port: Int) -> Transport = { h, p -> SocketTransport(h, p) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    var config: SyncplayConfig = config
        private set

    private val _state = MutableStateFlow(RoomState(username = config.username, room = config.room))
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SyncplayEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SyncplayEvent> = _events.asSharedFlow()

    private val engine = SyncEngine(player, clock, settings, EngineHost())
    private val ping = PingService(clock)

    private var connectionJob: Job? = null
    private var pollJob: Job? = null
    private var transport: Transport? = null
    private var outgoing: Channel<String>? = null
    private var stopped = true
    private var fatalError: String? = null

    private var logged = false
    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0
    private var hadFirstPlaylistIndex = false
    /**
     * The server sends the room's playlist after a Hello or room change, naming whoever last set the room's play state,
     * and before answering the List request that follows.
     */
    private var awaitingRoomPlaylist = false
    private var lastMessageTime = 0.0

    private var currentFile: FileInfo? = null
    private var queuedFilename: String? = null
    private var lastPlaylistIndexChange = 0.0
    private var myReady: Boolean? = null
    private val users = LinkedHashMap<String, RoomUser>()

    var settings: SyncSettings
        get() = engine.settings
        set(value) { scope.launch { engine.settings = value } }

    // --- Public API -------------------------------------------------------------

    fun start() {
        scope.launch {
            if (!stopped) return@launch
            stopped = false
            fatalError = null
            connectionJob = launch { connectionLoop() }
            pollJob = launch {
                while (isActive) {
                    delay(Constants.PLAYER_ASK_DELAY_MS)
                    tick()
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            stopped = true
            transport?.close()
            connectionJob?.cancelAndJoin()
            pollJob?.cancelAndJoin()
            endSession()
            _state.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
        }
    }

    /** Stops the client and releases its coroutines. */
    fun close() {
        stopped = true
        transport?.close()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Report the file that just finished loading in the player (or null when nothing is loaded).
     * [resetPosition] should come from [SyncplayEvent.SwitchToPlaylistItem]; for files opened
     * directly by the user pass false so playback jumps to the room position.
     */
    fun fileLoaded(file: FileInfo?, resetPosition: Boolean = false) {
        scope.launch {
            currentFile = file
            queuedFilename = null
            if (file == null) {
                users[state.value.username]?.let { users[it.name] = it.copy(file = null) }
                publishUsers()
                return@launch
            }
            engine.onFileLoaded(resetPosition)
            users[state.value.username]?.let { users[it.name] = it.copy(file = file) }
            publishUsers()
            sendFile()
            changeToPlaylistIndexFromFilename(file.name)
        }
    }

    /** Call when the player starts opening a file; follow up with [fileLoaded] once it's ready. */
    fun fileLoading() {
        scope.launch { engine.onFileLoading() }
    }

    fun setReady(ready: Boolean) {
        scope.launch { changeReadyState(ready, manuallyInitiated = true) }
    }

    fun toggleReady() {
        scope.launch { changeReadyState(!(myReady ?: false), manuallyInitiated = true) }
    }

    fun sendChat(message: String) {
        scope.launch { if (logged && message.isNotBlank()) send(obj { put("Chat", message) }) }
    }

    fun changeRoom(room: String) {
        scope.launch {
            config = config.copy(room = room)
            _state.update { it.copy(room = room, playlist = emptyList(), playlistReceived = false, playlistIndex = null) }
            if (!logged) return@launch
            hadFirstPlaylistIndex = false
            awaitingRoomPlaylist = true
            users.clear()
            sendSet { putJsonObject("room") { put("name", room) } }
            sendList()
        }
    }

    /** Ask the room to switch to playlist entry [index]; the switch happens when the server echoes it. */
    fun selectPlaylistIndex(index: Int) {
        scope.launch { sendPlaylistIndex(index) }
    }

    fun setPlaylist(files: List<String>) {
        scope.launch { changePlaylist(files) }
    }

    fun addToPlaylist(filename: String) {
        scope.launch { changePlaylist(state.value.playlist + filename) }
    }

    fun removeFromPlaylist(index: Int) {
        scope.launch {
            val files = state.value.playlist.toMutableList()
            if (index in files.indices) {
                files.removeAt(index)
                changePlaylist(files)
            }
        }
    }

    fun movePlaylistItem(from: Int, to: Int) {
        scope.launch {
            val files = state.value.playlist.toMutableList()
            if (from !in files.indices || to !in files.indices || from == to) return@launch
            files.add(to, files.removeAt(from))
            changePlaylist(files)
        }
    }

    /** Add [filename] to the playlist if needed and make the room switch to it. */
    fun playInRoom(filename: String) {
        scope.launch {
            val files = state.value.playlist
            val existing = files.indexOfFirst { Filenames.same(it, filename) }
            if (existing >= 0) {
                sendPlaylistIndex(existing)
            } else {
                val newFiles = files + filename
                _state.update { it.copy(playlist = newFiles) }
                sendSet { putJsonObject("playlistChange") { putJsonArray("files") { newFiles.forEach { add(JsonPrimitive(it)) } } } }
                sendPlaylistIndex(newFiles.size - 1)
            }
        }
    }

    // --- Connection lifecycle --------------------------------------------------

    private suspend fun connectionLoop() {
        var attempt = 0
        while (!stopped) {
            _state.update { it.copy(status = if (attempt == 0) ConnectionStatus.CONNECTING else ConnectionStatus.RECONNECTING) }
            val error = runCatching { runSession() }.exceptionOrNull()
            val wasLogged = logged
            endSession()
            if (stopped) break
            val fatal = fatalError
            val reason = fatal ?: error?.message ?: "Connection closed"
            log("Disconnected: $reason")
            if (fatal != null || !config.autoReconnect) {
                emit(SyncplayEvent.Disconnected(reason, willReconnect = false))
                emit(SyncplayEvent.Notification(reason, isError = true))
                stopped = true
                break
            }
            emit(SyncplayEvent.Disconnected(reason, willReconnect = true))
            if (wasLogged) attempt = 0
            attempt++
            val waitSeconds = min(30.0, 2.0.pow(attempt - 1))
            _state.update { it.copy(status = ConnectionStatus.RECONNECTING) }
            delay((waitSeconds * 1000).toLong())
        }
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
    }

    private suspend fun runSession() = coroutineScope {
        val t = transportFactory(config.host, config.port)
        transport = t
        val out = Channel<String>(Channel.UNLIMITED)
        outgoing = out
        t.connect()
        lastMessageTime = clock.now()
        log("Connected to ${config.host}:${config.port}")

        if (config.useTls) negotiateTls(t)

        val writer = launch {
            for (line in out) {
                log(">> $line")
                t.writeLine(line)
            }
        }
        sendHello()
        try {
            while (isActive) {
                val line = t.readLine() ?: throw IOException("Server closed the connection")
                lastMessageTime = clock.now()
                if (line.isBlank()) continue
                log("<< $line")
                handleLine(line)
                if (fatalError != null) throw IOException(fatalError)
            }
        } finally {
            writer.cancel()
        }
    }

    private suspend fun negotiateTls(t: Transport) {
        t.writeLine(obj { putJsonObject("TLS") { put("startTLS", "send") } }.toString())
        val reply = withTimeoutOrNull(5_000) { t.readLine() } ?: run {
            log("No STARTTLS reply; continuing without TLS")
            return
        }
        val message = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return
        val answer = (message["TLS"] as? JsonObject)?.get("startTLS")?.jsonPrimitive?.content
        if (answer != null && "true" in answer) {
            t.startTls()
            log("TLS enabled")
        } else {
            log("Server does not support TLS")
        }
    }

    private fun endSession() {
        logged = false
        transport?.close()
        transport = null
        outgoing?.close()
        outgoing = null
        clientIgnoringOnTheFly = 0
        serverIgnoringOnTheFly = 0
        hadFirstPlaylistIndex = false
        engine.onDisconnected()
    }

    private fun tick() {
        if (!logged) return
        if (engine.hasGlobalState && clock.now() - lastMessageTime > Constants.PROTOCOL_TIMEOUT) {
            log("Server timed out")
            transport?.close()
            return
        }
        engine.poll()
    }

    // --- Incoming messages -----------------------------------------------------

    private fun handleLine(line: String) {
        val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
            log("Ignoring non-JSON line: $line")
            return
        }
        for ((command, value) in message) {
            when (command) {
                "Hello" -> handleHello(value.jsonObject)
                "Set" -> handleSet(value.jsonObject)
                "List" -> (value as? JsonObject)?.let { handleList(it) }
                "State" -> handleState(value.jsonObject)
                "Chat" -> handleChat(value)
                "Error" -> handleError(value)
                "TLS" -> Unit
                else -> log("Unknown command $command")
            }
        }
    }

    private fun handleHello(hello: JsonObject) {
        val username = hello.str("username") ?: config.username
        val room = (hello["room"] as? JsonObject)?.str("name") ?: config.room
        val version = hello.str("realversion") ?: hello.str("version")
        val motd = hello.str("motd")?.takeIf { it.isNotBlank() }
        val features = (hello["features"] as? JsonObject)?.mapValues { (_, v) -> v.toPlain() } ?: emptyMap()
        logged = true
        users.clear()
        users[username] = RoomUser(username, room, currentFile, myReady)
        _state.update {
            it.copy(
                status = ConnectionStatus.CONNECTED,
                username = username,
                room = room,
                serverVersion = version,
                motd = motd,
                tls = transport?.isTls == true,
                serverFeatures = features,
            )
        }
        publishUsers()
        emit(SyncplayEvent.Connected(username, room, motd, transport?.isTls == true))

        val ready = myReady ?: engine.settings.readyAtStart
        sendReady(ready, manuallyInitiated = false)
        sendFile()
        sendList()
    }

    private fun handleSet(set: JsonObject) {
        for ((command, value) in set) {
            when (command) {
                "room" -> (value as? JsonObject)?.str("name")?.let { room -> _state.update { it.copy(room = room) } }
                "user" -> (value as? JsonObject)?.let { handleSetUsers(it) }
                "ready" -> (value as? JsonObject)?.let { handleSetReady(it) }
                "playlistIndex" -> (value as? JsonObject)?.let { handlePlaylistIndex(it) }
                "playlistChange" -> (value as? JsonObject)?.let { handlePlaylistChange(it) }
                else -> Unit
            }
        }
    }

    private fun handleSetUsers(usersObj: JsonObject) {
        val myRoom = state.value.room
        for ((name, element) in usersObj) {
            val settings = element as? JsonObject ?: continue
            val room = (settings["room"] as? JsonObject)?.str("name")
            val file = parseFile(settings["file"])
            val event = settings["event"] as? JsonObject
            when {
                event?.containsKey("joined") == true -> {
                    if (room == myRoom) {
                        users[name] = RoomUser(name, room, file)
                        if (name != state.value.username) notify("$name joined the room")
                    }
                }
                event?.containsKey("left") == true -> {
                    if (users.remove(name) != null && name != state.value.username) notify("$name left")
                }
                else -> {
                    val existing = users[name]
                    if (room != null && room != myRoom) {
                        if (existing != null && name != state.value.username) notify("$name moved to room '$room'")
                        users.remove(name)
                    } else {
                        val updated = (existing ?: RoomUser(name, room ?: myRoom)).let { u -> if (file != null) u.copy(file = file) else u }
                        users[name] = updated
                        if (file != null && name != state.value.username && file != existing?.file) {
                            notify("$name is playing '${file.name}' (${formatTime(file.duration)})")
                        }
                    }
                }
            }
        }
        publishUsers()
    }

    private fun handleSetReady(ready: JsonObject) {
        val name = ready.str("username") ?: return
        val isReady = ready["isReady"]?.jsonPrimitive?.booleanOrNull
        if (name == state.value.username) {
            myReady = isReady
            _state.update { it.copy(isReady = isReady) }
        }
        users[name]?.let { users[name] = it.copy(isReady = isReady) }
        publishUsers()
    }

    private fun handleList(list: JsonObject) {
        // Servers that skip an empty room's playlist still answer the List sent after joining.
        if (awaitingRoomPlaylist) applyPlaylist(emptyList(), null)
        val myRoom = state.value.room
        val roomUsers = list[myRoom] as? JsonObject ?: return
        users.clear()
        for ((name, element) in roomUsers) {
            val u = element as? JsonObject ?: continue
            users[name] = RoomUser(
                name = name,
                room = myRoom,
                file = parseFile(u["file"]),
                isReady = u["isReady"]?.jsonPrimitive?.booleanOrNull,
                isController = u["controller"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        val me = state.value.username
        if (me !in users) users[me] = RoomUser(me, myRoom, currentFile, myReady)
        publishUsers()
    }

    private fun handleState(stateObj: JsonObject) {
        var position: Double? = null
        var paused: Boolean? = null
        var doSeek = false
        var setBy: String? = null
        var messageAge = 0.0
        var latencyCalculation: Double? = null

        (stateObj["ignoringOnTheFly"] as? JsonObject)?.let { ignore ->
            val server = ignore["server"]?.jsonPrimitive?.intOrNull
            val client = ignore["client"]?.jsonPrimitive?.intOrNull
            if (server != null) {
                serverIgnoringOnTheFly = server
                clientIgnoringOnTheFly = 0
            } else if (client != null && client == clientIgnoringOnTheFly) {
                clientIgnoringOnTheFly = 0
            }
        }
        (stateObj["playstate"] as? JsonObject)?.let { ps ->
            position = ps["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            paused = ps["paused"]?.jsonPrimitive?.booleanOrNull
            doSeek = ps["doSeek"]?.jsonPrimitive?.booleanOrNull ?: false
            setBy = ps["setBy"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }
        (stateObj["ping"] as? JsonObject)?.let { p ->
            latencyCalculation = p["latencyCalculation"]?.jsonPrimitive?.doubleOrNull
            if (p.containsKey("clientLatencyCalculation")) {
                val ts = p["clientLatencyCalculation"]?.jsonPrimitive?.doubleOrNull
                val serverRtt = p["serverRtt"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                ping.receiveMessage(ts, serverRtt)
            }
            messageAge = ping.getLastForwardDelay()
        }
        val pos = position
        val p = paused
        if (pos != null && p != null && clientIgnoringOnTheFly == 0) {
            engine.updateGlobalState(pos, p, doSeek, setBy, messageAge)
            _state.update { it.copy(globalPaused = p, globalPosition = pos, lastSetBy = setBy, rttMillis = ping.getRtt() * 1000) }
        }
        val local = engine.getLocalState()
        sendState(local?.position, local?.paused, local?.doSeek ?: false, latencyCalculation, local?.stateChange ?: false)
    }

    private fun handleChat(value: JsonElement) {
        val o = value as? JsonObject ?: return
        val user = o.str("username") ?: return
        val message = o.str("message") ?: return
        emit(SyncplayEvent.Chat(user, message))
    }

    private fun handleError(value: JsonElement) {
        val message = (value as? JsonObject)?.str("message") ?: value.toString()
        if ("startTLS" in message && !logged) return
        log("Server error: $message")
        fatalError = message
    }

    // --- Playlist ----------------------------------------------------------------

    private fun handlePlaylistChange(change: JsonObject) {
        val files = (change["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return
        applyPlaylist(files, change.str("user"))
    }

    private fun applyPlaylist(files: List<String>, setBy: String?) {
        val joining = awaitingRoomPlaylist
        awaitingRoomPlaylist = false
        val user = if (joining) null else setBy
        val previous = state.value.playlist
        // The room's list is announced even when it's empty, so listeners know it has arrived.
        if (files == previous && !joining) return
        _state.update { it.copy(playlist = files, playlistReceived = it.playlistReceived || joining) }
        emit(SyncplayEvent.PlaylistChanged(files, user, previous))
        if (user != null && user != state.value.username) notify("$user changed the playlist")
    }

    private fun handlePlaylistIndex(indexObj: JsonObject) {
        val user = indexObj.str("user")
        val resetPosition = hadFirstPlaylistIndex
        hadFirstPlaylistIndex = true
        val index = indexObj["index"]?.jsonPrimitive?.intOrNull ?: return
        val files = state.value.playlist
        if (files.isEmpty()) return
        lastPlaylistIndexChange = clock.now()
        _state.update { it.copy(playlistIndex = index) }
        if (!engine.settings.sharedPlaylists) return
        val filename = files.getOrNull(index) ?: return
        val current = currentFile
        val queued = queuedFilename
        if (current != null && Filenames.same(filename, current.name) && (queued == null || Filenames.same(queued, filename))) {
            return
        }
        if (user != null && user != state.value.username) notify("$user selected '$filename'")
        queuedFilename = filename
        emit(SyncplayEvent.SwitchToPlaylistItem(index, filename, user, resetPosition))
    }

    private fun changePlaylist(files: List<String>) {
        val old = state.value.playlist
        if (old == files) return
        val newIndex = validIndexFromNewPlaylist(old, state.value.playlistIndex, files)
        _state.update { it.copy(playlist = files) }
        if (!logged) return
        sendSet { putJsonObject("playlistChange") { putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } } } }
        if (files.isNotEmpty()) sendPlaylistIndex(newIndex)
    }

    private fun sendPlaylistIndex(index: Int) {
        if (!logged) return
        sendSet { putJsonObject("playlistIndex") { put("index", index) } }
    }

    private fun changeToPlaylistIndexFromFilename(filename: String) {
        if (!engine.settings.sharedPlaylists) return
        val index = state.value.playlist.indexOf(filename)
        if (index >= 0 && index != state.value.playlistIndex) sendPlaylistIndex(index)
    }

    private fun advancePlaylistCheck() {
        val s = state.value
        val index = s.playlistIndex ?: return
        val current = currentFile ?: return
        if (clock.now() - lastPlaylistIndexChange <= Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD) return
        if (s.playlist.getOrNull(index)?.let { Filenames.same(it, current.name) } != true) return
        val settings = engine.settings
        if (!settings.sharedPlaylists) return
        val next = nextPlaylistIndex(s.playlist.size, index, settings.loopPlaylist, settings.loopSingleFile) ?: return
        lastPlaylistIndexChange = clock.now()
        if (next == index) {
            // Not marked as an advance: the engine then sends the rewind and unpause to the room as a seek and play.
            // Like Syncplay, unpause again shortly after, because the room's echo of the end-of-file pause can
            // re-pause the player.
            player.seek(0.0)
            player.setPaused(false)
            scope.launch {
                delay(500)
                player.setPaused(false)
            }
            return
        }
        engine.markAdvanced()
        val filename = s.playlist[next]
        queuedFilename = filename
        emit(SyncplayEvent.AdvancePlaylist(next, filename))
    }

    // --- Outgoing messages -----------------------------------------------------

    private fun sendHello() {
        awaitingRoomPlaylist = true
        _state.update { it.copy(playlistReceived = false) }
        send(obj {
            putJsonObject("Hello") {
                put("username", config.username)
                config.password?.takeIf { it.isNotEmpty() }?.let { put("password", md5Hex(it)) }
                putJsonObject("room") { put("name", config.room) }
                put("version", Constants.CLIENT_VERSION)
                put("realversion", Constants.REAL_VERSION)
                putJsonObject("features") {
                    put("sharedPlaylists", true)
                    put("chat", true)
                    put("uiMode", "GUI")
                    put("featureList", true)
                    put("readiness", true)
                    put("managedRooms", true)
                    put("persistentRooms", true)
                    put("setOthersReadiness", true)
                }
            }
        })
    }

    private fun sendFile() {
        val file = currentFile ?: return
        if (!logged) return
        sendSet {
            putJsonObject("file") {
                put("duration", file.duration)
                put("name", file.name)
                put("size", file.size)
            }
        }
        sendList()
    }

    private fun sendList() {
        if (logged) send(obj { put("List", JsonNull) })
    }

    private fun changeReadyState(ready: Boolean, manuallyInitiated: Boolean) {
        myReady = ready
        _state.update { it.copy(isReady = ready) }
        users[state.value.username]?.let { users[it.name] = it.copy(isReady = ready) }
        publishUsers()
        sendReady(ready, manuallyInitiated)
    }

    private fun sendReady(ready: Boolean, manuallyInitiated: Boolean) {
        if (!logged) return
        sendSet { putJsonObject("ready") { put("isReady", ready); put("manuallyInitiated", manuallyInitiated) } }
    }

    private fun sendState(position: Double?, paused: Boolean?, doSeek: Boolean, latencyCalculation: Double?, stateChange: Boolean) {
        if (!logged) return
        val clientIgnoreIsNotSet = clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0
        if (stateChange) clientIgnoringOnTheFly += 1
        val message = obj {
            putJsonObject("State") {
                if (clientIgnoreIsNotSet && position != null && paused != null) {
                    putJsonObject("playstate") {
                        put("position", position)
                        put("paused", paused)
                        if (doSeek) put("doSeek", true)
                    }
                }
                putJsonObject("ping") {
                    if (latencyCalculation != null && latencyCalculation != 0.0) put("latencyCalculation", latencyCalculation)
                    put("clientLatencyCalculation", ping.newTimestamp())
                    put("clientRtt", ping.getRtt())
                }
                if (serverIgnoringOnTheFly != 0 || clientIgnoringOnTheFly != 0) {
                    putJsonObject("ignoringOnTheFly") {
                        if (serverIgnoringOnTheFly != 0) put("server", serverIgnoringOnTheFly)
                        if (clientIgnoringOnTheFly != 0) put("client", clientIgnoringOnTheFly)
                    }
                    serverIgnoringOnTheFly = 0
                }
            }
        }
        send(message)
    }

    private inline fun sendSet(crossinline block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        send(obj { putJsonObject("Set") { block() } })
    }

    private fun send(message: JsonObject) {
        outgoing?.trySend(message.toString())
    }

    // --- Helpers -------------------------------------------------------------------

    private fun publishUsers() {
        val me = state.value.username
        val sorted = users.values.sortedWith(compareBy<RoomUser> { it.name != me }.thenBy { it.name.lowercase() })
        _state.update { it.copy(users = sorted) }
    }

    private fun notify(message: String) = emit(SyncplayEvent.Notification(message))

    private fun emit(event: SyncplayEvent) {
        _events.tryEmit(event)
    }

    private inner class EngineHost : SyncEngine.Host {
        override val username: String get() = state.value.username
        override val readinessSupported: Boolean get() = state.value.serverFeatures["readiness"] != false
        override val isReady: Boolean? get() = myReady
        override val currentFileDuration: Double get() = currentFile?.duration ?: 0.0
        override fun areAllOtherUsersReady(): Boolean =
            users.values.filter { it.name != username && it.file != null }.all { it.isReady != false }
        override fun notJustChangedPlaylist(): Boolean =
            clock.now() - lastPlaylistIndexChange > Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD
        override fun sendState(position: Double?, paused: Boolean?, doSeek: Boolean, latencyCalculation: Double?, stateChange: Boolean) =
            this@SyncplayClient.sendState(position, paused, doSeek, latencyCalculation, stateChange)
        override fun changeReadyState(ready: Boolean, manuallyInitiated: Boolean) =
            this@SyncplayClient.changeReadyState(ready, manuallyInitiated)
        override fun notify(message: String) = this@SyncplayClient.notify(message)
        override fun advancePlaylistCheck() = this@SyncplayClient.advancePlaylistCheck()
    }

    companion object {
        /**
         * Port of Syncplay's _thereIsNextPlaylistIndex/_nextPlaylistIndex: the entry to play after [index] ends,
         * [index] itself to replay a single-entry playlist, or null to stop.
         */
        fun nextPlaylistIndex(size: Int, index: Int, loopPlaylist: Boolean, loopSingleFile: Boolean): Int? = when {
            size == 1 -> if (loopSingleFile) index else null
            index + 1 < size -> index + 1
            loopPlaylist -> 0
            else -> null
        }

        /** Port of Syncplay's _getValidIndexFromNewPlaylist: keep pointing at the same file if it survived the edit. */
        fun validIndexFromNewPlaylist(old: List<String>, oldIndex: Int?, new: List<String>): Int {
            if (oldIndex == null || new.size <= 1) return 0
            var i = oldIndex
            while (i <= old.size) {
                old.getOrNull(i)?.let { name -> new.indexOf(name).takeIf { it >= 0 }?.let { return it } }
                i++
            }
            i = oldIndex
            while (i > 0) {
                old.getOrNull(i)?.let { name ->
                    new.indexOf(name).takeIf { it >= 0 }?.let { return if (it < new.size - 1) it + 1 else it }
                }
                i--
            }
            return 0
        }

        private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private fun parseFile(element: JsonElement?): FileInfo? {
            val o = element as? JsonObject ?: return null
            if (o.isEmpty()) return null
            val name = o.str("name") ?: return null
            val duration = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val size = (o["size"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
            return FileInfo(name, duration, size)
        }

        private fun JsonElement.toPlain(): Any? = when (this) {
            is JsonNull -> null
            is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            is JsonObject -> mapValues { it.value.toPlain() }
            is JsonArray -> map { it.toPlain() }
        }
    }
}
