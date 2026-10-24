package com.yarmiplaytv.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException

/** 48 kHz mono Opus encode/decode for 20 ms frames (960 samples). */
class OpusCodec(
    bitrate: Int = 32_000,
    fec: Boolean = true,
    dtx: Boolean = true,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
) {
    val frameSamples: Int = sampleRate / 50 // 20 ms
    private val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP).also {
        it.bitrate = bitrate.coerceIn(6_000, 128_000)
        it.useInbandFEC = fec
        it.useDTX = dtx
        it.complexity = 5
    }
    private val decoder = OpusDecoder(sampleRate, channels)
    private val encBuf = ByteArray(400)
    private val decBuf = ShortArray(frameSamples * channels)

    fun encode(pcm: ShortArray, offset: Int = 0): ByteArray? {
        if (pcm.size - offset < frameSamples) return null
        return try {
            val n = encoder.encode(pcm, offset, frameSamples, encBuf, 0, encBuf.size)
            if (n <= 0) null else encBuf.copyOf(n)
        } catch (_: OpusException) {
            null
        }
    }

    fun decode(opus: ByteArray, fec: Boolean = false): ShortArray? {
        return try {
            val n = decoder.decode(opus, 0, opus.size, decBuf, 0, frameSamples, fec)
            if (n <= 0) null else decBuf.copyOf(n * channels)
        } catch (_: OpusException) {
            null
        }
    }

    fun decodePlc(): ShortArray? = decode(ByteArray(0), fec = true)
}
