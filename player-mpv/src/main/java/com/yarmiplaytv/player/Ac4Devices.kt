package com.yarmiplaytv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.os.Build

/**
 * What this device can do with AC-4, read from the platform and nothing else.
 * A connected output that lists [AudioFormat.ENCODING_AC4], or a direct-playback query that accepts it,
 * can take the bitstream. A codec whose type is [Ac4.MIME] can decode it. Either answer is cached:
 * plugging in a receiver after launch is picked up the next time the process starts.
 */
internal data class Ac4Capability(val direct: Boolean, val decoder: Boolean) {
    val route: Ac4Route get() = Ac4.route(direct, decoder)

    companion object {
        val NONE = Ac4Capability(direct = false, decoder = false)
    }
}

internal object Ac4Devices {
    @Volatile private var cached: Ac4Capability? = null

    fun capability(context: Context): Ac4Capability = cached ?: probe(context).also { cached = it }

    private fun probe(context: Context): Ac4Capability = runCatching {
        Ac4Capability(direct = directPlayback() || outputEncodesAc4(context), decoder = platformDecoder())
    }.getOrDefault(Ac4Capability.NONE)

    private fun directPlayback(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return MASKS.any { mask ->
            val format = runCatching {
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_AC4)
                    .setSampleRate(48_000)
                    .setChannelMask(mask)
                    .build()
            }.getOrNull() ?: return@any false
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AudioManager.getDirectPlaybackSupport(format, attributes) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                } else {
                    AudioTrack.isDirectPlaybackSupported(format, attributes)
                }
            }.getOrDefault(false)
        }
    }

    private fun outputEncodesAc4(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            AudioFormat.ENCODING_AC4 in device.encodings
        }
    }

    private fun platformDecoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(Ac4.MIME, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private val MASKS = intArrayOf(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
}
