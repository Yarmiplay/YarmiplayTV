package com.yarmiplaytv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Which compressed audio the device says it can take, mapped to mpv's `audio-spdif` names.
 * mpv wraps those codecs as IEC 61937. AC-4 is not one of the names stock mpv accepts, so it is left out.
 */
internal object HdmiAudio {
    fun codecsFor(encodings: Set<Int>): List<String> {
        if (AudioFormat.ENCODING_IEC61937 in encodings) return IEC_CODECS
        return RAW.mapNotNull { (encoding, codec) -> codec.takeIf { encoding in encodings } }
    }

    fun reportedEncodings(context: Context): Set<Int> {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val format = AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
        return PROBES.filter { encoding ->
            val built = runCatching { format.setEncoding(encoding).build() }.getOrNull() ?: return@filter false
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    (AudioManager.getDirectPlaybackSupport(built, attributes) and
                        AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    AudioTrack.isDirectPlaybackSupported(built, attributes)
                else -> false
            }
        }.toSet()
    }

    private val RAW = listOf(
        AudioFormat.ENCODING_AC3 to "ac3",
        AudioFormat.ENCODING_E_AC3 to "eac3",
        AudioFormat.ENCODING_DTS to "dts",
        AudioFormat.ENCODING_DTS_HD to "dts-hd",
        AudioFormat.ENCODING_DOLBY_TRUEHD to "truehd",
    )

    private val IEC_CODECS = listOf("ac3", "eac3", "dts", "truehd", "dts-hd")

    private val PROBES = listOf(
        AudioFormat.ENCODING_IEC61937,
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        AudioFormat.ENCODING_DOLBY_TRUEHD,
    )
}
