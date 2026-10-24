package com.yarmiplaytv.desktop

import com.yarmiplaytv.syncplay.VoiceMediaParams
import com.yarmiplaytv.voice.VoiceCaptureKind
import com.yarmiplaytv.voice.VoiceEngine
import com.yarmiplaytv.voice.VoiceMediaSession
import com.yarmiplaytv.voice.VoiceSound
import com.yarmiplaytv.voice.participantSenderId
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Desktop/macOS capture + `yv1` Opus media (UDP, TLS TCP fallback).
 * Prefers a non-default mixer that looks like a headset when enumerating inputs.
 */
class DesktopVoiceEngine : VoiceEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clip: Clip? = null
    private val session = AtomicReference<VoiceMediaSession?>(null)
    private var captureThread: Thread? = null
    private var captureLine: TargetDataLine? = null
    private var playbackLine: SourceDataLine? = null
    private var inputDeviceId: String? = null
    private var outputDeviceId: String? = null
    private var playbackJob: Job? = null

    override suspend fun detectCapture(): VoiceCaptureKind {
        return runCatching {
            val format = AudioFormat(48000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                val stereo = AudioFormat(48000f, 16, 2, true, false)
                if (!AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, stereo))) {
                    return VoiceCaptureKind.NONE
                }
            }
            VoiceCaptureKind.OPEN
        }.getOrDefault(VoiceCaptureKind.NONE)
    }

    override suspend fun connect(params: VoiceMediaParams): Boolean {
        disconnect()
        val media = VoiceMediaSession(scope)
        if (!media.connect(params)) return false
        session.set(media)
        openPlayback(media.frameSamples)
        startCapture(media)
        startMixPump(media)
        return true
    }

    override fun disconnect() {
        captureThread?.interrupt()
        captureThread = null
        playbackJob?.cancel()
        playbackJob = null
        session.getAndSet(null)?.disconnect()
        runCatching { captureLine?.stop(); captureLine?.close() }
        captureLine = null
        runCatching { playbackLine?.stop(); playbackLine?.close() }
        playbackLine = null
        runCatching { clip?.close() }
        clip = null
    }

    override fun setTransmitting(on: Boolean) {
        session.get()?.setTransmitting(on)
    }

    override fun setSelfMuted(muted: Boolean) {
        session.get()?.setSelfMuted(muted)
    }

    override fun setInputGain(gain: Float) {
        session.get()?.setInputGain(gain)
    }

    override fun setOutputGain(gain: Float) {
        session.get()?.setOutputGain(gain)
    }

    override fun setPeerMuted(participantId: String, muted: Boolean) {
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerMuted(id, muted)
    }

    override fun setPeerVolume(participantId: String, volume: Float) {
        val id = participantSenderId(participantId) ?: return
        session.get()?.setPeerVolume(id, volume)
    }

    override fun setInputDeviceId(id: String?) {
        inputDeviceId = id
    }

    override fun setOutputDeviceId(id: String?) {
        outputDeviceId = id
    }

    override fun playSound(kind: VoiceSound, volume: Float) {
        val name = when (kind) {
            VoiceSound.JOIN -> "voice_join.wav"
            VoiceSound.LEAVE -> "voice_leave.wav"
            VoiceSound.MIC_ON -> "voice_mic_on.wav"
            VoiceSound.MIC_OFF -> "voice_mic_off.wav"
        }
        val stream = listOf(
            "/composeResources/com.yarmiplaytv.shared.generated.resources/files/$name",
            "/files/$name",
            "/$name",
        ).firstNotNullOfOrNull { path -> javaClass.getResourceAsStream(path) } ?: return
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
        }
    }

    private fun openPlayback(frameSamples: Int) {
        val format = AudioFormat(48000f, 16, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = (AudioSystem.getLine(info) as SourceDataLine)
        line.open(format, frameSamples * 2 * 8)
        line.start()
        playbackLine = line
    }

    private fun startMixPump(media: VoiceMediaSession) {
        val out = ShortArray(media.frameSamples)
        val bytes = ByteArray(out.size * 2)
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.get() === media) {
                val n = media.pollMixedPcm(out)
                if (n > 0) {
                    for (i in 0 until n) {
                        val s = out[i].toInt()
                        bytes[i * 2] = (s and 0xff).toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    runCatching { playbackLine?.write(bytes, 0, n * 2) }
                } else {
                    // Idle: write a short silence so the device stays open.
                    bytes.fill(0)
                    runCatching { playbackLine?.write(bytes, 0, minOf(bytes.size, 480)) }
                    Thread.sleep(5)
                }
            }
        }
    }

    private fun startCapture(media: VoiceMediaSession) {
        val format = AudioFormat(48000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = runCatching { AudioSystem.getLine(info) as TargetDataLine }.getOrNull() ?: return
        line.open(format, media.frameSamples * 2 * 8)
        line.start()
        captureLine = line
        val frameBytes = media.frameSamples * 2
        val buf = ByteArray(frameBytes)
        val pcm = ShortArray(media.frameSamples)
        captureThread = thread(name = "voice-capture", isDaemon = true) {
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
                    // offerCapturePcm is suspend; run blocking on this IO thread via runBlocking-free launch.
                    kotlinx.coroutines.runBlocking { media.offerCapturePcm(pcm) }
                }
            } catch (_: Exception) {
            }
        }
    }
}
