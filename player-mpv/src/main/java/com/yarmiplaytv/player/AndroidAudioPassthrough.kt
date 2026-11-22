package com.yarmiplaytv.player

import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Which compressed formats this device will take straight to HDMI. The stock libmpv AudioTrack output sends
 * those as IEC 61937 when `audio-spdif` names them. A device that only accepts raw AC3 (not IEC) still gets
 * the codec name; decoding to multichannel PCM remains the fallback.
 */
internal object AndroidAudioPassthrough {
    @Suppress("DEPRECATION")
    fun supportedEncodings(context: Context): List<String> {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val encodings = listOf(
            "IEC61937" to AudioFormat.ENCODING_IEC61937,
            "AC3" to AudioFormat.ENCODING_AC3,
            "E-AC3" to AudioFormat.ENCODING_E_AC3,
            "DTS" to AudioFormat.ENCODING_DTS,
            "DTS-HD" to AudioFormat.ENCODING_DTS_HD,
            "TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD,
        )
        val formatBuilder = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        val hdmiEncodings = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val hdmi = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            if (hdmi?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1) {
                hdmi.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)?.toSet().orEmpty()
            } else {
                emptySet()
            }
        } else {
            emptySet()
        }
        return encodings.filter { (_, encoding) ->
            val format = formatBuilder.setEncoding(encoding).build()
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    (AudioManager.getDirectPlaybackSupport(format, audioAttributes) and
                        AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    AudioTrack.isDirectPlaybackSupported(format, audioAttributes)
                else -> encoding in hdmiEncodings
            }
        }.map { (name, _) -> name }
    }

    fun mpvSpdifCodecs(supportedEncodings: Collection<String>): List<String> {
        val supported = supportedEncodings.toSet()
        if ("IEC61937" in supported) return ALL_IEC61937_CODECS
        return RAW_CODECS.filter { (encoding, _) -> encoding in supported }.map { (_, codec) -> codec }
    }

    private val RAW_CODECS = listOf(
        "AC3" to "ac3",
        "E-AC3" to "eac3",
        "DTS" to "dts",
    )

    private val ALL_IEC61937_CODECS = listOf("ac3", "dts", "eac3", "truehd", "dts-hd")

    private const val SAMPLE_RATE = 48_000
}
