package com.yarmiplaytv.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusSignal
import kotlin.math.roundToInt

/**
 * 48 kHz mono Opus encode/decode for 20 ms frames (960 samples).
 *
 * Tuned for Discord-grade low-latency VoIP:
 * - Application: [OpusApplication.OPUS_APPLICATION_VOIP]
 * - Signal: [OpusSignal.OPUS_SIGNAL_VOICE]
 * - Complexity: 10 on desktop, 8–10 on Android scaled by CPU core count
 * - VBR: on (with constrained VBR)
 * - In-band FEC: on, but [packetLossPercent] stays 0 until a receiver actually reports loss.
 *   A fake loss hint spends the 64 kbps budget on LBRR and thins a clean loopback.
 * - DTX: off (never drops frames into silence gaps)
 * - Bitrate: adapts dynamically to measured receiver packet loss
 * - Bitrate range [MIN_BITRATE]..[MAX_BITRATE] (24–128 kbps) and 1 or 2 channels, so a later
 *   stereo / 128 kbps session can construct the same encoder without a second code path
 */
class OpusCodec(
    bitrate: Int = 48_000,
    private val fec: Boolean = true,
    dtx: Boolean = false,
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    complexity: Int = defaultComplexity(),
) {
    val frameSamples: Int = sampleRate / 50 // 20 ms
    val baseBitrate: Int = bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)

    /** Live target. Packet-loss adaptation scales this; [setBitrate] replaces it. */
    @Volatile private var targetBitrate: Int = baseBitrate

    @Volatile
    var reportedLossPercent: Int = 0
        private set

    private val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP).also {
        it.bitrate = baseBitrate
        it.useInbandFEC = fec
        it.useDTX = dtx
        it.complexity = complexity.coerceIn(1, 10)
        it.packetLossPercent = 0
        it.signalType = OpusSignal.OPUS_SIGNAL_VOICE
        it.useVBR = true
        it.useConstrainedVBR = true
    }
    private val decoder = OpusDecoder(sampleRate, channels)
    private val encBuf = ByteArray(512)
    private val decBuf = ShortArray(frameSamples * channels)

    val currentBitrate: Int
        get() = synchronized(encoder) { encoder.bitrate }

    /** Requested bitrate before packet-loss adaptation. */
    val effectiveBitrate: Int get() = targetBitrate

    val effectiveChannels: Int get() = channels

    /** Replace the target bitrate. Channel-count changes still need a new [OpusCodec]. */
    fun setBitrate(bitrate: Int) {
        val next = bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
        targetBitrate = next
        synchronized(encoder) {
            encoder.bitrate = adaptBitrateForLoss(next, reportedLossPercent)
        }
    }

    val complexity: Int
        get() = synchronized(encoder) { encoder.complexity }

    val application: OpusApplication
        get() = synchronized(encoder) { encoder.application }

    val signalType: OpusSignal
        get() = synchronized(encoder) { encoder.signalType }

    val vbr: Boolean
        get() = synchronized(encoder) { encoder.useVBR }

    val constrainedVbr: Boolean
        get() = synchronized(encoder) { encoder.useConstrainedVBR }

    val inbandFec: Boolean
        get() = synchronized(encoder) { encoder.useInbandFEC }

    val dtx: Boolean
        get() = synchronized(encoder) { encoder.useDTX }

    val packetLossPercent: Int
        get() = synchronized(encoder) { encoder.packetLossPercent }

    /**
     * Feed measured receiver packet-loss percentage (`0..100`) into the Opus encoder.
     * Updates [OpusEncoder.setPacketLossPercent] for in-band FEC LBRR decisions and adapts
     * [OpusEncoder.setBitrate] so congestion loss steps bitrate toward the SILK LBRR sweet spot.
     */
    fun updatePacketLoss(lossPercent: Int) {
        val clamped = lossPercent.coerceIn(0, 100)
        reportedLossPercent = clamped
        synchronized(encoder) {
            // 0% measured loss must not keep LBRR armed. FEC stays enabled and engages
            // only when this percent is above zero.
            encoder.packetLossPercent = if (fec) clamped else 0
            encoder.bitrate = adaptBitrateForLoss(targetBitrate, clamped)
        }
    }

    fun encode(pcm: ShortArray, offset: Int = 0): ByteArray? {
        val available = pcm.size - offset
        val needed = frameSamples * channels
        val inputPcm: ShortArray
        val inputOffset: Int
        when {
            available >= needed -> {
                inputPcm = pcm
                inputOffset = offset
            }
            channels == 2 && available >= frameSamples -> {
                // Stereo sessions (128 kbps voice) still accept a mono 20 ms frame.
                inputPcm = VoicePcm.monoToStereo(pcm, offset, frameSamples)
                inputOffset = 0
            }
            else -> return null
        }
        return synchronized(encoder) {
            try {
                val n = encoder.encode(inputPcm, inputOffset, frameSamples, encBuf, 0, encBuf.size)
                if (n <= 0) null else encBuf.copyOf(n)
            } catch (_: OpusException) {
                null
            }
        }
    }

    fun decode(opus: ByteArray, fec: Boolean = false): ShortArray? {
        return synchronized(decoder) {
            try {
                val n = decoder.decode(opus, 0, opus.size, decBuf, 0, frameSamples, fec)
                if (n <= 0) null else decBuf.copyOf(n * channels)
            } catch (_: OpusException) {
                null
            }
        }
    }

    fun decodePlc(): ShortArray? = synchronized(decoder) {
        try {
            val n = decoder.decode(null, 0, 0, decBuf, 0, frameSamples, false)
            if (n <= 0) null else decBuf.copyOf(n * channels)
        } catch (_: OpusException) {
            null
        }
    }

    /** Decode with in-band FEC using the next packet's LBRR bits for the missing frame. */
    fun decodeFec(nextPacket: ByteArray): ShortArray? {
        if (nextPacket.isEmpty()) return decodePlc()
        return decode(nextPacket, fec = true)
    }

    companion object {
        const val MIN_BITRATE = 24_000
        const val MAX_BITRATE = 128_000
        /** Opus plaintext that still fits a `yv1` packet (header + GCM tag). */
        const val MAX_PAYLOAD_BYTES = Yv1Packet.MAX_PACKET - Yv1Packet.HEADER_LEN - Yv1Packet.TAG_LEN
        /**
         * Loss hint before any receiver report, and the value kept when measured loss is 0.
         * FEC stays switched on; these stay 0 so a clean mic test or LAN call is not pre-degraded.
         */
        const val MIN_FEC_LOSS_PERCENT = 0
        const val INITIAL_FEC_LOSS_PERCENT = 0

        fun isAndroidRuntime(): Boolean {
            val vm = System.getProperty("java.vm.name").orEmpty()
            val vendor = System.getProperty("java.vendor").orEmpty()
            val runtime = System.getProperty("java.runtime.name").orEmpty()
            return vm.contains("Dalvik", ignoreCase = true) ||
                vendor.contains("Android", ignoreCase = true) ||
                runtime.contains("Android", ignoreCase = true)
        }

        /**
         * Complexity 10 on desktop; 8–10 on Android scaled by available CPU cores.
         */
        fun defaultComplexity(
            isAndroid: Boolean = isAndroidRuntime(),
            cpuCores: Int = Runtime.getRuntime().availableProcessors(),
        ): Int {
            if (!isAndroid) return 10
            return when {
                cpuCores >= 8 -> 10
                cpuCores >= 6 -> 9
                else -> 8
            }
        }

        /**
         * Adapt target bitrate from [baseBitrate] according to measured [lossPercent]:
         * - 0–2% loss: full [baseBitrate]
         * - 3–10% loss: gentle step-down so SILK LBRR gets priority over high-band CELT
         * - >10% loss: progressive step-down toward [MIN_BITRATE] (keeping LBRR active above 22 kbps)
         */
        fun adaptBitrateForLoss(baseBitrate: Int, lossPercent: Int): Int {
            val base = baseBitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
            val loss = lossPercent.coerceIn(0, 100)
            if (loss <= 2) return base
            val factor = when {
                loss <= 10 -> 1.0 - (loss - 2) * 0.015 // up to -12% at 10% loss
                loss <= 25 -> 0.88 - (loss - 10) * 0.018 // down to ~61% at 25% loss
                else -> (0.61 - (loss - 25) * 0.005).coerceAtLeast(0.45)
            }
            return (base * factor).roundToInt().coerceIn(MIN_BITRATE, base)
        }
    }
}
