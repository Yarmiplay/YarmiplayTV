package com.yarmiplaytv.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusSignal

/** 48 kHz mono/stereo Opus encode/decode for 20 ms frames (960 samples per channel). */
class OpusCodec(
    bitrate: Int = 48_000,
    fec: Boolean = true,
    dtx: Boolean = false,
    private val sampleRate: Int = 48_000,
    val channels: Int = 1,
) {
    val frameSamples: Int = sampleRate / 50 // 20 ms (960 samples per channel at 48 kHz)
    val effectiveChannels: Int = channels.coerceIn(1, 2)
    val effectiveBitrate: Int = bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)

    private val encoder = OpusEncoder(sampleRate, effectiveChannels, OpusApplication.OPUS_APPLICATION_VOIP).also {
        it.bitrate = effectiveBitrate
        it.useInbandFEC = fec
        it.useDTX = dtx
        it.complexity = 8
        it.packetLossPercent = if (fec) 20 else 0
        it.signalType = OpusSignal.OPUS_SIGNAL_VOICE
        it.useVBR = true
        it.useConstrainedVBR = true
    }
    private val decoder = OpusDecoder(sampleRate, effectiveChannels)
    private val encBuf = ByteArray(MAX_PAYLOAD_BYTES)
    private val decBuf = ShortArray(frameSamples * effectiveChannels)

    fun encode(pcm: ShortArray, offset: Int = 0): ByteArray? {
        val available = pcm.size - offset
        val needed = frameSamples * effectiveChannels
        val inputPcm: ShortArray
        val inputOffset: Int
        when {
            available >= needed -> {
                inputPcm = pcm
                inputOffset = offset
            }
            effectiveChannels == 2 && available >= frameSamples -> {
                // Upmix 20 ms mono frame into interleaved stereo when caller feeds mono samples.
                inputPcm = VoicePcm.monoToStereo(pcm, offset, frameSamples)
                inputOffset = 0
            }
            else -> return null
        }
        return try {
            val n = encoder.encode(inputPcm, inputOffset, frameSamples, encBuf, 0, encBuf.size)
            if (n <= 0) null else encBuf.copyOf(n)
        } catch (_: OpusException) {
            null
        }
    }

    fun decode(opus: ByteArray, fec: Boolean = false): ShortArray? {
        return try {
            val n = decoder.decode(opus, 0, opus.size, decBuf, 0, frameSamples, fec)
            if (n <= 0) null else decBuf.copyOf(n * effectiveChannels)
        } catch (_: OpusException) {
            null
        }
    }

    fun decodePlc(): ShortArray? = decode(ByteArray(0), fec = false)

    /** Decode with in-band FEC using the next packet's FEC bits for the missing frame. */
    fun decodeFec(nextPacket: ByteArray): ShortArray? = decode(nextPacket, fec = true)

    companion object {
        const val MIN_BITRATE = 24_000
        const val MAX_BITRATE = 128_000
        /** Max Opus payload bytes that fit inside [Yv1Packet.MAX_PACKET] (1200 - 56 = 1144). */
        const val MAX_PAYLOAD_BYTES = Yv1Packet.MAX_PACKET - Yv1Packet.MIN_PACKET
    }
}
