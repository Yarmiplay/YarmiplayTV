package com.yarmiplaytv.voice

import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.FakePlayer
import com.yarmiplaytv.data.desktopSettingsStore
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.VoiceDescriptor
import com.yarmiplaytv.syncplay.YarmiplayCapabilities
import com.yarmiplaytv.syncplay.YarmiplayInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/** Leave ↔ Join flip, auto-join, mute capture gating, and mic hidden when out of voice. */
class VoiceControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var sync: SyncController
    private lateinit var voice: VoiceController
    private lateinit var store: com.yarmiplaytv.data.SettingsStore
    private lateinit var engine: CaptureTrackingVoiceEngine

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        store = desktopSettingsStore(tmp.newFolder("config"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        sync = SyncController(scope, FakePlayer())
        engine = CaptureTrackingVoiceEngine()
        voice = VoiceController(sync, store, scope, engine, AlwaysGrantedMicPermissions) { DeviceKind.PHONE }
    }

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun awaitTrue(what: String, millis: Long = 5_000, condition: () -> Boolean) = runBlocking {
        runCatching { withTimeout(millis) { while (!condition()) delay(20) } }
            .onFailure { throw AssertionError("Timed out waiting for $what") }
    }

    private fun roomWithVoice(available: Boolean = true): RoomState {
        val voiceDesc = VoiceDescriptor(version = 1, udpPort = 1, tcpPort = 1, maxParticipants = 8)
        val info = YarmiplayInfo(
            session = true,
            capabilities = YarmiplayCapabilities(voice = available),
            voice = if (available) voiceDesc else null,
        )
        return RoomState(status = ConnectionStatus.CONNECTED, username = "ana", room = "lounge", yarmiplay = info)
    }

    @Test
    fun `membership helpers flip leave and join and hide mic when out`() {
        val connected = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTED,
            capture = VoiceCaptureKind.OPEN,
        )
        assertTrue(connected.showVoiceMembershipButton)
        assertTrue(connected.voiceMembershipIsLeave)
        assertTrue(connected.showMicButton)

        val left = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.OFF,
            capture = VoiceCaptureKind.OPEN,
            statusLine = "Left voice",
        )
        assertTrue(left.showVoiceMembershipButton)
        assertFalse(left.voiceMembershipIsLeave)
        assertFalse(left.showMicButton)

        val connecting = VoiceUiState(
            available = true,
            enabledByClient = true,
            connection = VoiceConnection.CONNECTING,
            capture = VoiceCaptureKind.OPEN,
        )
        assertTrue(connecting.voiceMembershipIsLeave)
        assertFalse(connecting.showMicButton)
    }

    @Test
    fun `voiceAutoJoin defaults on and persists`() {
        runBlocking {
            assertTrue(store.current().voiceAutoJoin)
            store.saveVoiceAutoJoin(false)
        }
        awaitTrue("auto-join off") { runBlocking { !store.current().voiceAutoJoin } }
        runBlocking { store.saveVoiceAutoJoin(true) }
        awaitTrue("auto-join on") { runBlocking { store.current().voiceAutoJoin } }
    }

    @Test
    fun `auto-join off stays out of voice and leave flips to join`() {
        runBlocking { store.saveVoiceAutoJoin(false) }
        awaitTrue("setting applied") { runBlocking { !store.current().voiceAutoJoin } }
        onMain { sync.setTestRoom(roomWithVoice()) }
        awaitTrue("voice UI shown") { voice.ui.value.showUi }
        awaitTrue("stays off with Join") {
            val u = voice.ui.value
            u.connection == VoiceConnection.OFF && u.showVoiceMembershipButton && !u.voiceMembershipIsLeave && !u.showMicButton
        }
        onMain { voice.leave() }
        assertEquals(VoiceConnection.OFF, voice.ui.value.connection)
        assertFalse(voice.ui.value.voiceMembershipIsLeave)
        assertEquals("Join voice", if (voice.ui.value.voiceMembershipIsLeave) "Leave voice" else "Join voice")
    }

    @Test
    fun `self mute releases capture`() {
        onMain {
            engine.capturing = true
            engine.wantTransmit = true
            voice.setSelfMuted(true)
        }
        awaitTrue("capture stopped while muted") { !engine.capturing && !engine.wantTransmit }
        assertTrue(voice.ui.value.selfMuted)
    }

    @Test
    fun `open mic tap requests permission instead of muting when not talking`() {
        val mic = DeniedThenGrantMicPermissions()
        val deniedEngine = CaptureTrackingVoiceEngine(permission = false)
        val deniedVoice = VoiceController(sync, store, scope, deniedEngine, mic) { DeviceKind.PHONE }
        assertFalse(deniedVoice.ui.value.selfMuted)
        assertFalse(deniedVoice.ui.value.talking)
        onMain { deniedVoice.toggleSelfMuted() }
        awaitTrue("permission requested") { mic.requestCount >= 1 }
        assertFalse("must not flip into mute on first tap", deniedVoice.ui.value.selfMuted)
        onMain { mic.grant() }
        awaitTrue("permission applied on engine") { deniedEngine.hasMicPermission() }
        assertFalse(deniedVoice.ui.value.selfMuted)
    }
}

/** Mirrors platform engines: capture follows [setTransmitting] / [setSelfMuted]. */
private class CaptureTrackingVoiceEngine(
    private var permission: Boolean = true,
) : VoiceEngine {
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null
    @Volatile var capturing = false
    @Volatile var wantTransmit = false
    @Volatile private var selfMuted = false

    override suspend fun detectCapture(): VoiceCaptureKind = VoiceCaptureKind.OPEN
    override suspend fun connect(params: com.yarmiplaytv.syncplay.VoiceMediaParams): Boolean = true
    override fun disconnect() {
        capturing = false
        wantTransmit = false
    }

    override fun setTransmitting(on: Boolean) {
        wantTransmit = on && !selfMuted
        capturing = wantTransmit
    }

    override fun hasMicPermission(): Boolean = permission
    override fun onMicPermissionGranted() {
        permission = true
        if (wantTransmit) capturing = true
    }

    override fun setSelfMuted(muted: Boolean) {
        selfMuted = muted
        if (muted) {
            wantTransmit = false
            capturing = false
        }
    }

    override fun setInputGain(gain: Float) = Unit
    override fun setOutputGain(gain: Float) = Unit
    override fun setPeerMuted(participantId: String, muted: Boolean) = Unit
    override fun setPeerVolume(participantId: String, volume: Float) = Unit
    override fun setInputDeviceId(id: String?) = Unit
    override fun setOutputDeviceId(id: String?) = Unit
    override fun playSound(kind: VoiceSound, volume: Float) = Unit
}

private class DeniedThenGrantMicPermissions : MicPermissions {
    @Volatile var requestCount = 0
    @Volatile private var granted = false
    @Volatile private var pending: ((Boolean) -> Unit)? = null

    override fun granted(): Boolean = granted
    override fun request(onResult: (Boolean) -> Unit) {
        requestCount++
        if (granted) {
            onResult(true)
        } else {
            pending = onResult
        }
    }

    fun grant() {
        granted = true
        val cb = pending
        pending = null
        cb?.invoke(true)
    }

    override fun permanentlyDenied(): Boolean = false
    override fun openAppSettings() = Unit
}
