package com.yarmiplaytv.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.roundToInt

/** A Java Sound mixer that can capture or play PCM16 voice audio. */
data class JvmAudioDevice(val id: String, val label: String)

/**
 * Low-latency desktop mic/speaker enumeration and line opener (Windows WASAPI/DirectAudio,
 * macOS CoreAudio, Linux PipeWire/PulseAudio/ALSA).
 *
 * - Prefers 48 kHz mono PCM16 so devices that support 48 kHz run with zero resampling.
 * - Falls back to 44.1 kHz / 32 kHz / 96 kHz / 16 kHz when hardware is locked to a non-48 kHz clock
 *   (caller resamples once via [StreamingResampler]).
 * - Requests a 2-frame (~40 ms) hardware buffer for low mouth-to-ear latency, falling back to
 *   4 frames (~80 ms) or the driver default if a device rejects small buffers.
 */
object JvmVoiceDevices {
    val pcm48Mono: AudioFormat = AudioFormat(48_000f, 16, 1, true, false)
    val preferredRates: IntArray = intArrayOf(48_000, 44_100, 32_000, 96_000, 16_000)

    /** Target low-latency line buffer in 20 ms frame multiples (2 frames = 40 ms). */
    const val LOW_LATENCY_BUFFER_FRAMES = 2
    const val FALLBACK_BUFFER_FRAMES = 4

    fun formatForRate(sampleRate: Int, channels: Int = 1): AudioFormat =
        AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    fun listInputs(): List<JvmAudioDevice> = listMixers(target = true)

    fun listOutputs(): List<JvmAudioDevice> = listMixers(target = false)

    fun openCapture(deviceId: String?, frameSamples: Int): TargetDataLine? =
        openPcmLine(deviceId, frameSamples, target = true) as? TargetDataLine

    fun openPlayback(deviceId: String?, frameSamples: Int): SourceDataLine? =
        openPcmLine(deviceId, frameSamples, target = false) as? SourceDataLine

    /**
     * A Java Sound line cannot be reopened after [DataLine.close]. Each buffer size therefore
     * gets a fresh line, so a rejected 40 ms buffer still falls through to 80 ms and the
     * driver default instead of failing the device.
     */
    private fun openPcmLine(deviceId: String?, frameSamples: Int, target: Boolean): Any? {
        for (rate in preferredRates) {
            val format = formatForRate(rate)
            val frameBytes = frameSamplesAtRate(rate, frameSamples) * 2
            val buffers = intArrayOf(
                frameBytes * LOW_LATENCY_BUFFER_FRAMES,
                frameBytes * FALLBACK_BUFFER_FRAMES,
                0, // driver default
            )
            for (bufBytes in buffers) {
                val info = if (target) {
                    DataLine.Info(TargetDataLine::class.java, format)
                } else {
                    DataLine.Info(SourceDataLine::class.java, format)
                }
                val line = openLine(deviceId, info, target) ?: continue
                val opened = runCatching {
                    when (line) {
                        is TargetDataLine -> {
                            if (bufBytes > 0) line.open(format, bufBytes) else line.open(format)
                            line.start()
                        }
                        is SourceDataLine -> {
                            if (bufBytes > 0) line.open(format, bufBytes) else line.open(format)
                            line.start()
                        }
                        else -> return@runCatching false
                    }
                    true
                }.getOrDefault(false)
                if (opened) return line
                runCatching {
                    when (line) {
                        is TargetDataLine -> line.close()
                        is SourceDataLine -> line.close()
                    }
                }
            }
        }
        return null
    }

    /** True when [deviceId] is still present (empty/null always counts as available). */
    fun isAvailable(deviceId: String?, target: Boolean): Boolean {
        if (deviceId.isNullOrEmpty()) return true
        return mixersSupporting(target).any { it.name == deviceId }
    }

    private fun listMixers(target: Boolean): List<JvmAudioDevice> {
        val devices = mutableListOf(JvmAudioDevice("", "System default"))
        val seen = mutableSetOf<String>()
        for (info in mixersSupporting(target)) {
            val id = info.name
            if (!seen.add(id)) continue
            val label = info.name.trim().ifEmpty { info.description.trim() }.ifEmpty { "Audio device" }
            devices += JvmAudioDevice(id, label)
        }
        return devices
    }

    private fun mixersSupporting(target: Boolean): List<Mixer.Info> {
        return AudioSystem.getMixerInfo().filter { mi ->
            val mixer = runCatching { AudioSystem.getMixer(mi) }.getOrNull() ?: return@filter false
            preferredRates.any { rate ->
                val format = formatForRate(rate)
                val want = if (target) {
                    DataLine.Info(TargetDataLine::class.java, format)
                } else {
                    DataLine.Info(SourceDataLine::class.java, format)
                }
                runCatching { mixer.isLineSupported(want) }.getOrDefault(false) ||
                    runCatching {
                        val lines = if (target) mixer.targetLineInfo else mixer.sourceLineInfo
                        lines.any { want.matches(it) || it.matches(want) }
                    }.getOrDefault(false)
            }
        }.sortedByDescending { lowLatencyMixerPriority(it) }
    }

    /**
     * Rank low-latency native audio backends ahead of legacy software mixers when selecting default.
     */
    private fun lowLatencyMixerPriority(info: Mixer.Info): Int {
        val text = "${info.name} ${info.description} ${info.vendor}".lowercase()
        return when {
            "java sound audio engine" in text -> -10
            "port " in text -> -5
            "wasapi" in text || "coreaudio" in text || "pipewire" in text -> 20
            "default" in text || "primary" in text || "direct" in text || "pulse" in text -> 10
            else -> 0
        }
    }

    private fun openLine(deviceId: String?, info: DataLine.Info, target: Boolean): Any? {
        // A named mixer that is gone must fail. Callers clear the saved id and reopen the
        // system default. Substituting the default here leaves the stale id selected.
        if (!deviceId.isNullOrEmpty()) {
            val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceId } ?: return null
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return null
            return runCatching { mixer.getLine(info) }.getOrNull()
        }
        // Try low-latency ranked mixer first, then AudioSystem fallback.
        for (mi in mixersSupporting(target)) {
            val line = runCatching { AudioSystem.getMixer(mi).getLine(info) }.getOrNull()
            if (line != null) return line
        }
        return runCatching { AudioSystem.getLine(info) }.getOrNull()
    }

    /** Compute how many samples one 20 ms frame has at [sampleRate]. */
    fun frameSamplesAtRate(sampleRate: Int, baseFrameSamples48k: Int = 960): Int =
        ((sampleRate.toDouble() * baseFrameSamples48k) / 48_000.0).roundToInt().coerceAtLeast(160)
}
