package com.yarmiplaytv.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** A Java Sound mixer that can capture or play 48 kHz mono PCM. */
data class JvmAudioDevice(val id: String, val label: String)

/**
 * Java Sound mic/speaker enumeration for desktop (Windows, Linux, macOS).
 * IDs are mixer names from [AudioSystem.getMixerInfo]; empty id is the system default.
 */
object JvmVoiceDevices {
    val pcm48Mono: AudioFormat = AudioFormat(48_000f, 16, 1, true, false)

    fun listInputs(): List<JvmAudioDevice> = listMixers(target = true)

    fun listOutputs(): List<JvmAudioDevice> = listMixers(target = false)

    fun openCapture(deviceId: String?, frameSamples: Int): TargetDataLine? {
        val format = pcm48Mono
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = openLine(deviceId, info, target = true) as? TargetDataLine ?: return null
        return runCatching {
            line.open(format, frameSamples * 2 * 8)
            line.start()
            line
        }.getOrElse {
            runCatching { line.close() }
            null
        }
    }

    fun openPlayback(deviceId: String?, frameSamples: Int): SourceDataLine? {
        val format = pcm48Mono
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = openLine(deviceId, info, target = false) as? SourceDataLine ?: return null
        return runCatching {
            line.open(format, frameSamples * 2 * 8)
            line.start()
            line
        }.getOrElse {
            runCatching { line.close() }
            null
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
        if (!deviceId.isNullOrEmpty()) {
            val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceId }
            if (mixerInfo != null) {
                val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull()
                val fromMixer = mixer?.let { runCatching { it.getLine(info) }.getOrNull() }
                if (fromMixer != null) return fromMixer
            }
        }
        return runCatching { AudioSystem.getLine(info) }.getOrNull()
            ?: runCatching {
                val mi = mixersSupporting(target).firstOrNull() ?: return null
                AudioSystem.getMixer(mi).getLine(info)
            }.getOrNull()
    }
}
