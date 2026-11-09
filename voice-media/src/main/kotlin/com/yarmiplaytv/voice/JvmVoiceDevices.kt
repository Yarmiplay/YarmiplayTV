package com.yarmiplaytv.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.BooleanControl
import javax.sound.sampled.CompoundControl
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** A Java Sound mixer that can capture or play 48 kHz PCM. */
data class JvmAudioDevice(val id: String, val label: String)

/**
 * Java Sound mic/speaker enumeration for desktop (Windows, Linux, macOS).
 * IDs are mixer names from [AudioSystem.getMixerInfo]; empty id is the system default.
 */
object JvmVoiceDevices {
    val pcm48Mono: AudioFormat = AudioFormat(48_000f, 16, 1, true, false)
    val pcm48Stereo: AudioFormat = AudioFormat(48_000f, 16, 2, true, false)

    fun listInputs(): List<JvmAudioDevice> = listMixers(target = true)

    fun listOutputs(): List<JvmAudioDevice> = listMixers(target = false)

    fun openCapture(deviceId: String?, frameSamples: Int): TargetDataLine? =
        openCapture(deviceId, frameSamples, channels = 1)

    fun openCapture(deviceId: String?, frameSamples: Int, channels: Int): TargetDataLine? {
        if (channels >= 2) {
            openCaptureFormat(deviceId, frameSamples, pcm48Stereo)?.let { return it }
        }
        return openCaptureFormat(deviceId, frameSamples, pcm48Mono)
    }

    private fun openCaptureFormat(deviceId: String?, frameSamples: Int, format: AudioFormat): TargetDataLine? {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = openLine(deviceId, info, target = true) as? TargetDataLine ?: return null
        return runCatching {
            val ch = format.channels.coerceAtLeast(1)
            line.open(format, frameSamples * ch * 2 * 8)
            disableDriverProcessingControls(line)
            line.start()
            line
        }.getOrElse {
            runCatching { line.close() }
            null
        }
    }

    fun openPlayback(deviceId: String?, frameSamples: Int): SourceDataLine? =
        openPlayback(deviceId, frameSamples, channels = 1)

    fun openPlayback(deviceId: String?, frameSamples: Int, channels: Int): SourceDataLine? {
        if (channels >= 2) {
            openPlaybackFormat(deviceId, frameSamples, pcm48Stereo)?.let { return it }
        }
        return openPlaybackFormat(deviceId, frameSamples, pcm48Mono)
    }

    private fun openPlaybackFormat(deviceId: String?, frameSamples: Int, format: AudioFormat): SourceDataLine? {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = openLine(deviceId, info, target = false) as? SourceDataLine ?: return null
        return runCatching {
            val ch = format.channels.coerceAtLeast(1)
            line.open(format, frameSamples * ch * 2 * 8)
            line.start()
            line
        }.getOrElse {
            runCatching { line.close() }
            null
        }
    }

    /**
     * Best-effort check to disable any driver/mixer-exposed noise suppression, echo cancellation,
     * or automatic gain boolean controls on the capture line so they cannot stack with our chain.
     */
    internal fun disableDriverProcessingControls(line: TargetDataLine) {
        val controls = runCatching { line.controls }.getOrNull() ?: return
        for (control in controls) {
            disableDriverControlRecursive(control)
        }
    }

    private fun disableDriverControlRecursive(control: Control) {
        when (control) {
            is CompoundControl -> {
                for (member in runCatching { control.memberControls }.getOrNull().orEmpty()) {
                    disableDriverControlRecursive(member)
                }
            }
            is BooleanControl -> {
                val name = control.type?.toString()?.lowercase().orEmpty()
                if (
                    name.contains("noise") ||
                    name.contains("suppress") ||
                    name.contains("echo") ||
                    name.contains("aec") ||
                    name.contains("agc") ||
                    name.contains("automatic gain")
                ) {
                    runCatching { control.value = false }
                }
            }
        }
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
        val format = pcm48Mono
        val want = if (target) {
            DataLine.Info(TargetDataLine::class.java, format)
        } else {
            DataLine.Info(SourceDataLine::class.java, format)
        }
        return AudioSystem.getMixerInfo().filter { mi ->
            val mixer = runCatching { AudioSystem.getMixer(mi) }.getOrNull() ?: return@filter false
            runCatching { mixer.isLineSupported(want) }.getOrDefault(false) ||
                runCatching {
                    val lines = if (target) mixer.targetLineInfo else mixer.sourceLineInfo
                    lines.any { want.matches(it) || it.matches(want) }
                }.getOrDefault(false)
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
        return runCatching { AudioSystem.getLine(info) }.getOrNull()
            ?: runCatching {
                val mi = mixersSupporting(target).firstOrNull() ?: return null
                AudioSystem.getMixer(mi).getLine(info)
            }.getOrNull()
    }
}
