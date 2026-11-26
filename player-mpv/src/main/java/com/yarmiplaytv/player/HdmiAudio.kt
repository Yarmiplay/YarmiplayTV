package com.yarmiplaytv.player

import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Which compressed audio the device says it can take, mapped to mpv's `audio-spdif` names.
 *
 * Our bundled libmpv builds `ao_audiotrack` with raw bitstream passthrough for AC-3, E-AC-3 and DTS
 * (`ENCODING_AC3` / `ENCODING_E_AC3` / `ENCODING_DTS`) plus multichannel IEC 61937 for TrueHD and
 * DTS-HD MA. AC-4 is not accepted by mpv's `audio-spdif`, so it is left out here and played by
 * [Ac4Bridge] when the device itself can take it.
 */
internal object HdmiAudio {
    fun codecsFor(encodings: Set<Int>): List<String> {
        if (AudioFormat.ENCODING_IEC61937 in encodings) return IEC_CODECS
        // Without IEC 61937 only AC-3, E-AC-3 and DTS can be unwrapped into raw AudioTrack encodings;
        // TrueHD and DTS-HD MA require the 8-channel IEC 61937 carrier.
        return RAW.mapNotNull { (encoding, codec) -> codec.takeIf { encoding in encodings } }
    }

    @Suppress("DEPRECATION")
    fun reportedEncodings(context: Context): Set<Int> {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val hdmi = runCatching {
                context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            }.getOrNull()
            if (hdmi?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) != 1) return emptySet()
            val advertised = hdmi.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)?.toSet().orEmpty()
            return PROBES.map { it.encoding }.filterTo(LinkedHashSet()) { it in advertised }
        }
        return PROBES.filter { probe ->
            probe.channelMasks.any { mask ->
                val format = runCatching {
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(mask)
                        .setEncoding(probe.encoding)
                        .build()
                }.getOrNull() ?: return@any false
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        (AudioManager.getDirectPlaybackSupport(format, attributes) and
                            AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
                    else -> AudioTrack.isDirectPlaybackSupported(format, attributes)
                }
            }
        }.mapTo(LinkedHashSet()) { it.encoding }
    }

    fun describe(encodings: Set<Int>): String =
        PROBES.filter { it.encoding in encodings }.joinToString(",") { it.label }.ifEmpty { "none" }

    private data class Probe(val encoding: Int, val label: String, val channelMasks: IntArray)

    private val RAW = listOf(
        AudioFormat.ENCODING_AC3 to "ac3",
        AudioFormat.ENCODING_E_AC3 to "eac3",
        AudioFormat.ENCODING_DTS to "dts",
    )

    private val IEC_CODECS = listOf("ac3", "eac3", "dts", "truehd", "dts-hd")

    // IEC 61937 carries AC3/E-AC3/DTS in a 2-channel stereo container and HBR (TrueHD/DTS-HD) in 8 channels.
    // Raw AC3/E-AC3/DTS HALs vary between advertising stereo and 5.1 channel masks, so probe both.
    private val PROBES = listOf(
        Probe(
            AudioFormat.ENCODING_IEC61937,
            "IEC61937",
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
        ),
        Probe(
            AudioFormat.ENCODING_AC3,
            "AC3",
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_5POINT1),
        ),
        Probe(
            AudioFormat.ENCODING_E_AC3,
            "E-AC3",
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_5POINT1),
        ),
        Probe(
            AudioFormat.ENCODING_DTS,
            "DTS",
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_5POINT1),
        ),
        Probe(
            AudioFormat.ENCODING_DTS_HD,
            "DTS-HD",
            intArrayOf(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_5POINT1),
        ),
        Probe(
            AudioFormat.ENCODING_DOLBY_TRUEHD,
            "TrueHD",
            intArrayOf(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_5POINT1),
        ),
    )

    private const val SAMPLE_RATE = 48_000
}
