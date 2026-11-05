package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.JvmVoiceDevices
import com.yarmiplaytv.voice.VoiceAudioDevice
import com.yarmiplaytv.voice.VoiceCaptureKind
import com.yarmiplaytv.voice.VoiceEngine
import com.yarmiplaytv.voice.VoiceMediaSession
import com.yarmiplaytv.voice.DiscordVoiceEngine
import com.yarmiplaytv.voice.VoiceSound
import com.yarmiplaytv.voice.participantSenderId
import com.yarmiplaytv.voice.senderParticipantId
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Desktop/macOS capture + `yv1` Opus media (UDP, TLS TCP fallback).
 * Lists real Java Sound mixers and switches mic/speaker live without rejoining.
 */
class DesktopVoiceEngine : VoiceEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clip: Clip? = null
    private val session = AtomicReference<VoiceMediaSession?>(null)
    private val discordEngine = DiscordVoiceEngine(scope)
    @Volatile private var activeDiscord = false
    private var captureThread: Thread? = null
    private var captureLine: TargetDataLine? = null
    private var playbackLine: SourceDataLine? = null
    private var inputDeviceId: String? = null
    private var outputDeviceId: String? = null
    private var playbackJob: Job? = null
    private var watchJob: Job? = null
    private var frameSamples: Int = 960
    override var onDeviceLost: ((input: Boolean) -> Unit)? = null

    override suspend fun detectCapture(): VoiceCaptureKind {
        return if (JvmVoiceDevices.listInputs().any { it.id.isNotEmpty() } ||
            runCatching {
                val line = JvmVoiceDevices.openCapture(null, 960)
                line?.close()
                line != null
            }.getOrDefault(false)
        ) {
            VoiceCaptureKind.OPEN
        } else {
            VoiceCaptureKind.NONE
        }
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnect()
        if (params.isDiscordLobby) {
            val ok = discordEngine.connect(params)
            if (!ok) return false
            activeDiscord = true
        } else {
            activeDiscord = false
        }
        val media = VoiceMediaSession(scope)
        if (!media.connect(params)) {
            if (activeDiscord) {
                discordEngine.disconnect()
                activeDiscord = false
            }
            return false
        }
        // Default on; VoiceController reapplies the saved preference immediately after connect.
        media.setNoiseSuppression(true)
        session.set(media)
        frameSamples = media.frameSamples
        openPlayback()
        // Capture only while transmitting so mute/mic-off releases the input device.
        startMixPump(media)
        startDeviceWatch()
        return true
    }

    override fun disconnect() {
        if (activeDiscord) {
            discordEngine.disconnect()
            activeDiscord = false
        }
        watchJob?.cancel()
        watchJob = null
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        stopCapture()
        closePlaybackLine()
        runCatching { clip?.close() }
        clip = null
    }

    override fun setTransmitting(on: Boolean) {
        if (activeDiscord) {
            discordEngine.setTransmitting(on)
        }
        val media = session.get()
        media?.setTransmitting(on)
        syncCapture(media)
    }

    override fun setSelfMuted(muted: Boolean) {
        if (activeDiscord) {
            discordEngine.setSelfMuted(muted)
        }
        val media = session.get()
        media?.setSelfMuted(muted)
        // Mute stops capture here; unmute waits for [setTransmitting] so the UI isn't blocked twice.
        if (muted) syncCapture(media)
    }

    override fun captureLevel(): Float {
        val s = session.get()
        if (s != null) {
            val level = s.captureLevel
            if (level > 0f) return level
        }
        return if (activeDiscord) discordEngine.captureLevel() else 0f
    }

    override fun audibleParticipantIds(): Set<String> {
        val mediaSenders = session.get()?.audibleSenderIds()?.mapTo(linkedSetOf()) { senderParticipantId(it) } ?: emptySet()
        val discordSenders = if (activeDiscord) discordEngine.audibleParticipantIds() else emptySet()
        return mediaSenders + discordSenders
    }

    override fun capturingSpeech(): Boolean =
        session.get()?.capturingSpeech() == true || (activeDiscord && discordEngine.capturingSpeech())

    private fun syncCapture(media: VoiceMediaSession?) {
        if (media == null || session.get() !== media) {
            stopCapture()
            return
        }
        if (media.isTransmitting()) {
            if (captureThread?.isAlive != true) startCapture(media)
        } else {
            stopCapture()
        }
    }

    private fun stopCapture() {
        val t = captureThread
        captureThread = null
        t?.interrupt()
        closeCaptureLine()
        // Join so a rapid unmute doesn't open a second TargetDataLine while Windows still holds the first.
        if (t != null && t.isAlive) {
            runCatching { t.join(750) }
        }
    }

    override fun setInputGain(gain: Float) {
        if (activeDiscord) discordEngine.setInputGain(gain)
        session.get()?.setInputGain(gain)
    }

    override fun setOutputGain(gain: Float) {
        if (activeDiscord) discordEngine.setOutputGain(gain)
        session.get()?.setOutputGain(gain)
    }

    override fun setNoiseSuppression(on: Boolean) {
        if (activeDiscord) discordEngine.setNoiseSuppression(on)
        session.get()?.setNoiseSuppression(on)
    }

    override fun setPeerMuted(participantId: String, muted: Boolean) {
        if (activeDiscord) discordEngine.setPeerMuted(participantId, muted)
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerMuted(id, muted)
    }

    override fun setPeerVolume(participantId: String, volume: Float) {
        if (activeDiscord) discordEngine.setPeerVolume(participantId, volume)
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerVolume(id, volume)
    }

    override fun listInputDevices(): List<VoiceAudioDevice> =
        JvmVoiceDevices.listInputs().map { VoiceAudioDevice(it.id, it.label, it.id.isEmpty()) }

    override fun listOutputDevices(): List<VoiceAudioDevice> =
        JvmVoiceDevices.listOutputs().map { VoiceAudioDevice(it.id, it.label, it.id.isEmpty()) }

    override fun setInputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (inputDeviceId == next) return
        inputDeviceId = next
        val media = session.get() ?: return
        stopCapture()
        syncCapture(media)
    }

    override fun setOutputDeviceId(id: String?) {
        val next = id?.takeIf { it.isNotEmpty() }
        if (outputDeviceId == next) return
        outputDeviceId = next
        if (session.get() != null) openPlayback()
    }

    override fun playSound(kind: VoiceSound, volume: Float) {
        val name = when (kind) {
            VoiceSound.JOIN -> "voice_join.wav"
            VoiceSound.LEAVE -> "voice_leave.wav"
            VoiceSound.MIC_ON -> "voice_mic_on.wav"
            VoiceSound.MIC_OFF -> "voice_mic_off.wav"
        }
        // Packaged at the jar root by desktop processResources (copied from shared composeResources/files).
        val stream = listOf(
            "/$name",
            "/composeResources/com.yarmiplaytv.shared.generated.resources/files/$name",
            "/files/$name",
        ).firstNotNullOfOrNull { path -> javaClass.getResourceAsStream(path) } ?: run {
            println("DesktopVoice missing sound resource: $name")
            return
        }
        runCatching {
            clip?.close()
            val audio = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
            clip = AudioSystem.getClip().also { c ->
                c.open(audio)
                runCatching {
                    val ctrl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    val vol = volume.coerceIn(0.05f, 1f)
                    ctrl.value = (Math.log10(vol.toDouble()) * 20.0).toFloat().coerceIn(ctrl.minimum, ctrl.maximum)
                }
                c.start()
            }
        }.onFailure { println("DesktopVoice playSound $name failed: ${it.message}") }
    }

    private fun openPlayback() {
        closePlaybackLine()
        var id = outputDeviceId
        var line = JvmVoiceDevices.openPlayback(id, frameSamples)
        if (line == null && !id.isNullOrEmpty()) {
            outputDeviceId = null
            onDeviceLost?.invoke(false)
            id = null
            line = JvmVoiceDevices.openPlayback(null, frameSamples)
        }
        if (line == null) {
            println("DesktopVoice playback open failed device=${id ?: "system default"} (need 48 kHz mono PCM)")
        }
        playbackLine = line
    }

    private fun startMixPump(media: VoiceMediaSession) {
        val out = ShortArray(media.frameSamples)
        val bytes = ByteArray(out.size * 2)
        var loggedFormat = false
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() === media) {
                val n = media.pollMixedPcm(out)
                val line = playbackLine
                if (n > 0 && line != null) {
                    if (!loggedFormat) {
                        loggedFormat = true
                        println(
                            "DesktopVoice playback format=PCM16LE rate=48000 ch=1 " +
                                "frameSamples=${media.frameSamples} bytesPerFrame=${media.frameSamples * 2} " +
                                "firstRemoteRms=${media.lastRemoteRms}",
                        )
                    }
                    for (i in 0 until n) {
                        val s = out[i].toInt()
                        bytes[i * 2] = (s and 0xff).toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    runCatching { line.write(bytes, 0, n * 2) }
                } else {
                    bytes.fill(0)
                    runCatching { line?.write(bytes, 0, minOf(bytes.size, 480)) }
                    Thread.sleep(5)
                }
            }
        }
    }

    private fun startCapture(media: VoiceMediaSession) {
        stopCapture()
        val preferredId = inputDeviceId
        val frameBytes = media.frameSamples * 2
        val buf = ByteArray(frameBytes)
        val pcm = ShortArray(media.frameSamples)
        // Open the line on this worker thread so mute/unmute never blocks the UI on Java Sound.
        captureThread = thread(name = "voice-capture", isDaemon = true) {
            var id = preferredId
            var line = JvmVoiceDevices.openCapture(id, media.frameSamples)
            if (line == null && !id.isNullOrEmpty()) {
                inputDeviceId = null
                onDeviceLost?.invoke(true)
                id = null
                line = JvmVoiceDevices.openCapture(null, media.frameSamples)
            }
            if (line == null) {
                println("DesktopVoice capture open failed device=${id ?: "system default"} (need 48 kHz mono PCM)")
                return@thread
            }
            if (session.get() !== media || Thread.currentThread().isInterrupted) {
                runCatching { line.stop(); line.close() }
                return@thread
            }
            captureLine = line
            println(
                "DesktopVoice capture format=PCM16LE rate=48000 ch=1 " +
                    "frameSamples=${media.frameSamples} bytesPerFrame=$frameBytes device=${id ?: "system default"}",
            )
            try {
                while (!Thread.currentThread().isInterrupted && session.get() === media) {
                    var read = 0
                    while (read < frameBytes) {
                        val n = line.read(buf, read, frameBytes - read)
                        if (n < 0) return@thread
                        read += n
                    }
                    for (i in pcm.indices) {
                        val lo = buf[i * 2].toInt() and 0xff
                        val hi = buf[i * 2 + 1].toInt()
                        pcm[i] = ((hi shl 8) or lo).toShort()
                    }
                    kotlinx.coroutines.runBlocking { media.offerCapturePcm(pcm) }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { line.stop(); line.close() }
                if (captureLine === line) captureLine = null
            }
        }
    }

    private fun startDeviceWatch() {
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() != null) {
                delay(1_500)
                val media = session.get() ?: break
                if (!JvmVoiceDevices.isAvailable(inputDeviceId, target = true)) {
                    inputDeviceId = null
                    onDeviceLost?.invoke(true)
                    stopCapture()
                    syncCapture(media)
                }
                if (!JvmVoiceDevices.isAvailable(outputDeviceId, target = false)) {
                    outputDeviceId = null
                    onDeviceLost?.invoke(false)
                    openPlayback()
                }
            }
        }
    }

    private fun closeCaptureLine() {
        runCatching { captureLine?.stop(); captureLine?.close() }
        captureLine = null
    }

    private fun closePlaybackLine() {
        runCatching { playbackLine?.stop(); playbackLine?.close() }
        playbackLine = null
    }
}
