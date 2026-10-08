package com.yarmiplaytv.sync

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.device.FileDeviceKeyStore
import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.DeviceState
import com.yarmiplaytv.syncplay.ServerKind
import com.yarmiplaytv.syncplay.SyncplayConfig
import com.yarmiplaytv.syncplay.Yarmiplay
import com.yarmiplaytv.ui.shared.DeviceAccessPrompt
import com.yarmiplaytv.ui.shared.approvedOnly
import com.yarmiplaytv.ui.shared.closeDeviceAccess
import com.yarmiplaytv.ui.shared.deviceAccessPrompt
import com.yarmiplaytv.ui.shared.requestDeviceAccess
import com.yarmiplaytv.ui.shared.serverStatusLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * App containers with device keys and a relay cache against real servers: YARMIPLAY_TEST_SERVER=host:port is a
 * YarmiplayServerTV with the file relay on (`cargo run --example syncplay_server -- <port>` in that repo), and
 * SYNCPLAY_TEST_SERVER=host:port a stock syncplay-server (scripts/local-syncplay-server.ps1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YarmiplayServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val room = "yarmiplay-" + System.nanoTime()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var storeScope: CoroutineScope
    private val apps = mutableListOf<AppContainer>()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        onMain { apps.forEach { it.sync.disconnect(); it.relay?.close() } }
        apps.forEach { it.scope.cancel() }
        runBlocking { withTimeout(5_000) { apps.forEach { it.scope.coroutineContext.job.join() } } }
        storeScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun awaitTrue(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(timeoutMs) { while (!onMain(condition)) delay(50) } }
            .onFailure {
                val state = apps.joinToString("; ") { app ->
                    val r = app.sync.room.value
                    "${r.username}: ${r.status} ${r.yarmiplay.serverKind} session=${r.yarmiplay.session} files=${r.yarmiplay.relayFiles.map { it.name to it.sources }} " +
                        "status=${app.playlist.status.value} relay=${app.relay?.status?.value}"
                }
                throw AssertionError("Timed out waiting for $what ($state)")
            }
    }

    private fun join(server: String, name: String, player: FakePlayer = FakePlayer()): AppContainer {
        val dir = tmp.root.resolve(name)
        val app = AppContainer(
            settingsStore = desktopSettingsStore(dir.resolve("settings"), storeScope),
            deviceName = name,
            appVersion = "test",
            createPlayer = { player },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            deviceKeys = FileDeviceKeyStore(dir.resolve("keys")),
            cacheDir = dir.resolve("cache"),
        )
        apps += app
        val (host, port) = server.split(":").let { it[0] to it[1].toInt() }
        onMain { app.sync.connect(SyncplayConfig(host, port, name, room)) }
        awaitTrue("$name to join") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        return app
    }

    @Test
    fun `a viewer without the file plays it through the relay from one who has it`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val media = tmp.newFolder("media")
        val bytes = Random(7).nextBytes(6 * 1024 * 1024 + 4321)
        File(media, "relay test.mkv").writeBytes(bytes)

        val seeder = join(server, "seeder")
        val leecherPlayer = FakePlayer()
        val leecher = join(server, "leecher", leecherPlayer)
        awaitTrue("both to get an extension session") {
            listOf(seeder, leecher).all { it.sync.room.value.yarmiplay.relayActive && it.sync.session.value != null }
        }
        assertEquals(ServerKind.YARMIPLAY, leecher.sync.room.value.yarmiplay.serverKind)
        awaitTrue("the peers to see each other's extension") { leecher.sync.room.value.users.any { it.name == "seeder" && it.yarmiplay } }

        onMain { seeder.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the seeder to index its folder") { seeder.local.files.value.isNotEmpty() }
        onMain { seeder.playlist.shared.add(listOf("relay test.mkv")) }
        awaitTrue("the room to list the seeder's offer") {
            leecher.sync.room.value.yarmiplay.relayFiles.any { it.name == "relay test.mkv" && it.sources == 1 && it.size == bytes.size.toLong() }
        }
        onMain { seeder.playlist.selectIndex(0) }

        awaitTrue("the leecher to open the relayed file") { leecher.relay?.isRelayUrl(leecherPlayer.loaded) == true }
        val url = leecherPlayer.loaded!!
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val read = client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(bytes, read)
        val tail = client.newCall(Request.Builder().url(url).header("Range", "bytes=-1000").build()).execute().use { r ->
            assertEquals(206, r.code)
            r.body!!.bytes()
        }
        assertArrayEquals(bytes.copyOfRange(bytes.size - 1000, bytes.size), tail)
        awaitTrue("the leecher to show where the file comes from") {
            leecher.relay?.status?.value?.text?.contains("via the Syncplay server") == true
        }
    }

    @Test
    fun `a file added directly, outside any media folder, is relayed whole to a viewer`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val bytes = Random(11).nextBytes(5 * 1024 * 1024 + 377)
        val file = File(tmp.newFolder("elsewhere"), "picked directly.mp4").apply { writeBytes(bytes) }

        val seeder = join(server, "seeder")
        val leecherPlayer = FakePlayer()
        val leecher = join(server, "leecher", leecherPlayer)
        awaitTrue("both to get an extension session") { listOf(seeder, leecher).all { it.sync.room.value.yarmiplay.relayActive } }
        assertTrue(!seeder.local.hasFolders)

        onMain { seeder.playlist.addLocalFilesToRoomPlaylist(listOf(FileLocalLibrary.uriOf(file.toPath()))) }
        awaitTrue("the room to list the directly added file") {
            leecher.sync.room.value.yarmiplay.relayFiles.any { it.name == file.name && it.sources == 1 && it.size == bytes.size.toLong() }
        }
        onMain { seeder.playlist.selectIndex(0) }

        awaitTrue("the leecher to open the relayed file") { leecher.relay?.isRelayUrl(leecherPlayer.loaded) == true }
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val read = client.newCall(Request.Builder().url(leecherPlayer.loaded!!).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `a viewer who turned sharing off offers nothing and can withdraw an offer`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val media = tmp.newFolder("media")
        File(media, "private.mkv").writeBytes(Random(17).nextBytes(2 * 1024 * 1024 + 5))
        val app = join(server, "private")
        runBlocking { app.settingsStore.saveShareFiles(false) }
        awaitTrue("sharing to be off") { !app.settings.value.shareFiles }
        awaitTrue("an extension session") { app.sync.room.value.yarmiplay.relayActive }
        onMain { app.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the folder to be indexed") { app.local.files.value.isNotEmpty() }
        onMain { app.playlist.shared.add(listOf("private.mkv")) }
        awaitTrue("the playlist to come back") { app.sync.room.value.playlist == listOf("private.mkv") }
        runBlocking { delay(3_000) }
        assertTrue("offered with sharing off", onMain { app.sync.room.value.yarmiplay.relayFiles.none { it.sources > 0 } })

        runBlocking { app.settingsStore.saveShareFiles(true) }
        awaitTrue("the file to be offered once sharing is on") {
            app.sync.room.value.yarmiplay.relayFiles.any { it.name == "private.mkv" && it.sources == 1 }
        }
        runBlocking { app.settingsStore.saveShareFiles(false) }
        awaitTrue("the offer to be withdrawn") { app.sync.room.value.yarmiplay.relayFiles.none { it.sources > 0 } }
    }

    /** Runs YarmiplayServerTV's scripts/fake_peer.py (YARMIPLAY_FAKE_PEER) in our room, with its output kept for failures. */
    private fun fakePeer(server: String, name: String, script: String): Pair<Process, StringBuffer> {
        val peer = System.getenv("YARMIPLAY_FAKE_PEER").orEmpty()
        assumeTrue("YARMIPLAY_FAKE_PEER not set", File(peer).exists())
        val (host, port) = server.split(":")
        val process = ProcessBuilder(
            System.getenv("YARMIPLAY_FAKE_PEER_PYTHON") ?: "python", "-u", peer,
            "--host", host, "--port", port, "--room", room, "--name", name, "--yarmiplay", "--script", script,
        ).redirectErrorStream(true).start()
        val output = StringBuffer()
        Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true }.start()
        return process to output
    }

    @Test
    fun `plays a file the reference peer seeds`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val file = File(tmp.newFolder("peer"), "peer-seed.mkv")
        val bytes = Random(11).nextBytes(3 * 1024 * 1024 + 77)
        file.writeBytes(bytes)
        val player = FakePlayer()
        val leecher = join(server, "leecher", player)
        val (peer, output) = fakePeer(server, "seeder", "offer ${file.absolutePath}; playlist ${file.name}; select 0; wait 120")
        try {
            runCatching { awaitTrue("the relayed file to open") { leecher.relay?.isRelayUrl(player.loaded) == true } }
                .onFailure { throw AssertionError("${it.message}\nfake_peer said:\n$output") }
            val read = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(player.loaded!!).build()).execute().use { it.body!!.bytes() }
            assertArrayEquals(bytes, read)
        } finally {
            peer.destroyForcibly()
        }
    }

    @Test
    fun `the reference peer downloads a file we seed`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val media = tmp.newFolder("media")
        val bytes = Random(13).nextBytes(5 * 1024 * 1024 + 999)
        File(media, "our-seed.mkv").writeBytes(bytes)
        val seeder = join(server, "seeder")
        onMain { seeder.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the seeder to index its folder") { seeder.local.files.value.isNotEmpty() }
        onMain { seeder.playlist.shared.add(listOf("our-seed.mkv")) }
        awaitTrue("the seeder's offer to be listed") { seeder.sync.room.value.yarmiplay.relayFiles.any { it.name == "our-seed.mkv" && it.sources == 1 } }

        val out = File(tmp.root, "fetched.mkv")
        val (peer, output) = fakePeer(server, "leecher", "expect-files 1 within=30; fetch our-seed.mkv ${out.absolutePath} download; quit")
        try {
            assertTrue("fake_peer didn't finish:\n$output", peer.waitFor(90, TimeUnit.SECONDS))
            assertEquals("fake_peer failed:\n$output", 0, peer.exitValue())
            assertArrayEquals(bytes, out.readBytes())
        } finally {
            peer.destroyForcibly()
        }
    }

    /**
     * YarmiplayServerTV with device access on, which the stock example doesn't enable: a small binary that runs
     * `SyncplayServer` with a `DeviceStore`, approves pending devices named "...approve...", denies "...deny..." and
     * revokes approved devices whose user is "...revoke..." 3 seconds after they join.
     * YARMIPLAY_PASSWORD_SERVER=host:port:password runs it in password mode, YARMIPLAY_APPROVED_SERVER=host:port in
     * approved-devices-only mode.
     */
    private fun passwordServer(): Triple<String, Int, String> {
        val spec = System.getenv("YARMIPLAY_PASSWORD_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_PASSWORD_SERVER not set", spec.count { it == ':' } >= 2)
        val (host, port, password) = spec.split(":", limit = 3)
        return Triple(host, port.toInt(), password)
    }

    private fun approvedServer(): Pair<String, Int> {
        val spec = System.getenv("YARMIPLAY_APPROVED_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_APPROVED_SERVER not set", ':' in spec)
        val (host, port) = spec.split(":")
        return host to port.toInt()
    }

    private fun deviceOf(app: AppContainer) = onMain { app.sync.room.value.yarmiplay.device }

    private fun assertStaysDisconnected(app: AppContainer) {
        runBlocking { delay(3_000) }
        assertEquals("no reconnect after a refusal", ConnectionStatus.DISCONNECTED, onMain { app.sync.room.value.status })
    }

    private fun container(name: String): AppContainer {
        val dir = tmp.root.resolve(name)
        return AppContainer(
            settingsStore = desktopSettingsStore(dir.resolve("settings"), storeScope),
            deviceName = name,
            appVersion = "test",
            createPlayer = { FakePlayer() },
            createLocalLibrary = { s, scope, folders -> FileLocalLibrary(s, scope, folders, watchForChanges = false) },
            deviceKeys = FileDeviceKeyStore(dir.resolve("keys")),
            cacheDir = dir.resolve("cache"),
        ).also { app ->
            apps += app
            runBlocking { app.settingsStore.saveDeviceName(name) }
            awaitTrue("the device name to be saved") { app.deviceDisplayName == name }
        }
    }

    @Test
    fun `signs the device challenge and logs in with the password`() {
        val (host, port, password) = passwordServer()
        val app = container("pw")
        onMain { app.sync.connect(SyncplayConfig(host, port, "pw", room, password = password)) }
        awaitTrue("to join with the password") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        val info = onMain { app.sync.room.value.yarmiplay }
        assertEquals(ServerKind.YARMIPLAY, info.serverKind)
        assertEquals("password", info.access)
        assertTrue(info.serverId != null && Yarmiplay.isValidServerId(info.serverId!!))
        assertTrue(!info.approvedDevice)
        assertEquals("YarmiplayServerTV · password", serverStatusLine(info))
        awaitTrue("the server to be remembered") { app.settings.value.knownServers["$host:$port"]?.serverId == info.serverId }
        assertTrue(app.hasDeviceKey("$host:$port"))
    }

    @Test
    fun `a wrong password waits for the host, and the approved key skips the password next time`() {
        val (host, port, _) = passwordServer()
        val waiting = container("waiting room")
        onMain { waiting.sync.connect(SyncplayConfig(host, port, "waiting", room, password = "wrong")) }
        awaitTrue("to wait for the host's approval") { waiting.sync.room.value.yarmiplay.device is DeviceState.Pending }
        val pending = deviceOf(waiting) as DeviceState.Pending
        assertTrue(pending.fingerprint, pending.fingerprint.matches(Regex("[0-9A-F]{4}(-[0-9A-F]{4}){3}")))
        val prompt = onMain { deviceAccessPrompt(waiting.sync.room.value.yarmiplay, waiting.deviceDisplayName) }
        assertEquals(DeviceAccessPrompt.Waiting(pending.fingerprint, "waiting room"), prompt)
        onMain { closeDeviceAccess(waiting) }
        awaitTrue("closing the prompt to disconnect") { waiting.sync.room.value.status == ConnectionStatus.DISCONNECTED }
        assertStaysDisconnected(waiting)

        val app = container("approve me")
        onMain { app.sync.connect(SyncplayConfig(host, port, "approved", room, password = "wrong")) }
        awaitTrue("the host to approve the device") {
            app.sync.room.value.status == ConnectionStatus.CONNECTED && app.sync.room.value.yarmiplay.approvedDevice
        }
        assertEquals("YarmiplayServerTV · approved device", serverStatusLine(onMain { app.sync.room.value.yarmiplay }))
        onMain { app.sync.disconnect() }
        onMain { app.sync.connect(SyncplayConfig(host, port, "approved", room), requestAccess = false) }
        awaitTrue("to log in again with the key and no password") {
            app.sync.room.value.status == ConnectionStatus.CONNECTED && app.sync.room.value.yarmiplay.approvedDevice
        }
    }

    @Test
    fun `a wrong password without asking, or a host who says no, ends without reconnecting`() {
        val (host, port, _) = passwordServer()
        val quiet = container("quiet")
        onMain { quiet.sync.connect(SyncplayConfig(host, port, "quiet", room, password = "wrong"), requestAccess = false) }
        awaitTrue("the server to refuse") { quiet.sync.room.value.status == ConnectionStatus.DISCONNECTED && quiet.sync.feed.value.any { it.isError } }
        assertStaysDisconnected(quiet)

        val denied = container("deny me")
        onMain { denied.sync.connect(SyncplayConfig(host, port, "denied", room, password = "wrong")) }
        awaitTrue("the host to deny the device") { denied.sync.room.value.yarmiplay.device == DeviceState.Denied }
        val prompt = onMain { deviceAccessPrompt(denied.sync.room.value.yarmiplay, denied.deviceDisplayName) }
        assertTrue("$prompt", prompt is DeviceAccessPrompt.Refused)
        assertStaysDisconnected(denied)
    }

    @Test
    fun `an approved-devices server hides the password, and revoking or forgetting the key locks the device out`() {
        val (host, port) = approvedServer()
        val server = "$host:$port"
        val app = container("approve revocable")
        onMain { app.sync.connect(SyncplayConfig(host, port, "plain", room)) }
        awaitTrue("the host to approve the device") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        assertEquals("approved", onMain { app.sync.room.value.yarmiplay.access })
        awaitTrue("the server to be remembered as approved-only") { approvedOnly(app.settings.value.knownServers, host, "$port") }

        onMain { app.sync.disconnect() }
        onMain { app.forgetDeviceKey(server) }
        awaitTrue("the key to be forgotten") { !app.hasDeviceKey(server) }
        onMain { app.sync.connect(SyncplayConfig(host, port, "plain", room), requestAccess = false) }
        awaitTrue("a new key to need approval") { app.sync.room.value.yarmiplay.device == DeviceState.Required }
        assertEquals(DeviceAccessPrompt.NotApproved, onMain { deviceAccessPrompt(app.sync.room.value.yarmiplay, app.deviceDisplayName) })
        assertStaysDisconnected(app)

        onMain { requestDeviceAccess(app) }
        awaitTrue("the host to approve the new key") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        onMain { app.sync.disconnect() }
        onMain { app.sync.connect(SyncplayConfig(host, port, "revoke me", room)) }
        awaitTrue("to join before the host removes the device") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        awaitTrue("the host to remove the device") { app.sync.room.value.yarmiplay.device == DeviceState.Revoked }
        assertTrue(onMain { deviceAccessPrompt(app.sync.room.value.yarmiplay, app.deviceDisplayName) } is DeviceAccessPrompt.Refused)
        assertStaysDisconnected(app)
    }

    @Test
    fun `an open server lets the device in without a challenge`() {
        val server = System.getenv("YARMIPLAY_TEST_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_TEST_SERVER not set", server.isNotBlank())
        val app = join(server, "open")
        awaitTrue("the marker") { app.sync.room.value.yarmiplay.serverKind == ServerKind.YARMIPLAY }
        val info = onMain { app.sync.room.value.yarmiplay }
        assertEquals("open", info.access)
        assertNull(info.serverId)
        assertTrue(!info.approvedDevice && info.device == DeviceState.None)
        assertEquals("YarmiplayServerTV", serverStatusLine(info))
    }

    /**
     * A server that must look exactly like stock Syncplay: no device prompt, the password alone decides, and no
     * Yarmiplay command goes out (a playlist with a local file would make the relay offer it).
     */
    private fun assertPlainPasswordServer(spec: String) {
        val (host, port, password) = spec.split(":", limit = 3).let { Triple(it[0], it[1].toInt(), it[2]) }
        val media = tmp.newFolder("media")
        File(media, "plain.mkv").writeBytes(ByteArray(4096) { it.toByte() })
        val app = container("plain")
        onMain { app.sync.connect(SyncplayConfig(host, port, "plain", room, password = password)) }
        awaitTrue("to join with the password") { app.sync.room.value.status == ConnectionStatus.CONNECTED }
        onMain { app.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the folder to be indexed") { app.local.files.value.isNotEmpty() }
        onMain { app.playlist.shared.add(listOf("plain.mkv")) }
        awaitTrue("the playlist to come back") { app.sync.room.value.playlist == listOf("plain.mkv") }
        runBlocking { delay(3_000) }
        val room = onMain { app.sync.room.value }
        assertEquals(ConnectionStatus.CONNECTED, room.status)
        assertEquals(ServerKind.SYNCPLAY, room.yarmiplay.serverKind)
        assertTrue(room.yarmiplay.device == DeviceState.None && room.yarmiplay.serverId == null && !room.yarmiplay.session)
        assertNull(serverStatusLine(room.yarmiplay))
        assertTrue(!app.hasDeviceKey("$host:$port"))

        val wrong = container("wrong")
        onMain { wrong.sync.connect(SyncplayConfig(host, port, "wrong", room.room, password = "not-$password")) }
        awaitTrue("the password to be refused") {
            wrong.sync.room.value.status == ConnectionStatus.DISCONNECTED && wrong.sync.feed.value.any { it.isError && "password" in it.text.lowercase() }
        }
        assertEquals(DeviceState.None, deviceOf(wrong))
        assertNull(onMain { deviceAccessPrompt(wrong.sync.room.value.yarmiplay, wrong.deviceDisplayName) })
        assertStaysDisconnected(wrong)
    }

    /** YARMIPLAY_VANILLA_SERVER=host:port:password: scripts/yarmiplay-device-server in password mode with --vanilla. */
    @Test
    fun `YarmiplayServerTV in vanilla mode is treated as a stock server`() {
        val spec = System.getenv("YARMIPLAY_VANILLA_SERVER").orEmpty()
        assumeTrue("YARMIPLAY_VANILLA_SERVER not set", spec.count { it == ':' } >= 2)
        assertPlainPasswordServer(spec)
    }

    /** SYNCPLAY_PASSWORD_SERVER=host:port:password: scripts/local-syncplay-server.ps1 -Password. */
    @Test
    fun `a stock Syncplay server with a password works as before`() {
        val spec = System.getenv("SYNCPLAY_PASSWORD_SERVER").orEmpty()
        assumeTrue("SYNCPLAY_PASSWORD_SERVER not set", spec.count { it == ':' } >= 2)
        assertPlainPasswordServer(spec)
    }

    @Test
    fun `a stock Syncplay server sees nothing new`() {
        val server = System.getenv("SYNCPLAY_TEST_SERVER").orEmpty()
        assumeTrue("SYNCPLAY_TEST_SERVER not set", server.isNotBlank())
        val media = tmp.newFolder("media")
        File(media, "stock.mkv").writeBytes(ByteArray(4096) { it.toByte() })

        val app = join(server, "stock")
        onMain { app.local.addFolder(FileLocalLibrary.uriOf(media.toPath())) }
        awaitTrue("the folder to be indexed") { app.local.files.value.isNotEmpty() }
        onMain { app.playlist.shared.add(listOf("stock.mkv")) }
        awaitTrue("the playlist to come back") { app.sync.room.value.playlist == listOf("stock.mkv") }
        // A stock server drops the connection on any unknown command; give an offer time to (not) go out.
        runBlocking { delay(3_000) }
        val room = onMain { app.sync.room.value }
        assertEquals(ConnectionStatus.CONNECTED, room.status)
        assertEquals(ServerKind.SYNCPLAY, room.yarmiplay.serverKind)
        assertTrue(!room.yarmiplay.session && room.yarmiplay.relayFiles.isEmpty())
        assertNull(onMain { app.sync.session.value })
        assertNull(onMain { app.relay?.status?.value })
    }
}
