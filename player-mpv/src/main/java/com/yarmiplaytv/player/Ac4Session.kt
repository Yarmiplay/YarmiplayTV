package com.yarmiplaytv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Whether the picture clock is moving, and how fast. Read on the audio thread. */
internal data class Ac4Transport(val playing: Boolean, val speed: Float)

/**
 * Plays one AC-4 track through the platform, in step with [clock] (mpv's position, in seconds).
 *
 * Passthrough writes the compressed frames to an [AudioTrack] in [AudioFormat.ENCODING_AC4], which is
 * what a receiver on HDMI gets. Decode asks the platform `audio/ac4` codec for PCM and plays that.
 * The thread owns the extractor, the codec and the track. [close] only asks it to stop.
 */
internal class Ac4Session(
    private val context: Context,
    private val url: String,
    private val ordinal: Int,
    private val capability: Ac4Capability,
    private val clock: () -> Double,
    private val transport: () -> Ac4Transport,
    private val onFailed: (Throwable) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val pendingSeekUs = AtomicLong(NONE)
    private var resyncUs = NONE
    private var ignoreBehindUntil = 0L
    private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null

    fun open() {
        worker = thread(name = "ac4-audio", isDaemon = true) {
            try {
                run()
            } catch (error: Throwable) {
                if (!stopped.get()) {
                    Log.w(TAG, "AC-4 playback stopped: ${error.message}")
                    onFailed(error)
                }
            }
        }
    }

    fun seekTo(seconds: Double) {
        pendingSeekUs.set((seconds.coerceAtLeast(0.0) * 1_000_000.0).toLong())
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) {
            worker?.join(JOIN_MS)
            return
        }
        runCatching {
            track?.pause()
            track?.flush()
        }
        worker?.join(JOIN_MS)
    }

    private fun run() {
        val extractor = Ac4Source.open(context, url) ?: error("the platform demuxer cannot open this file")
        var codec: MediaCodec? = null
        var audio: AudioTrack? = null
        try {
            val index = ac4Index(extractor, ordinal) ?: error("the platform demuxer has no AC-4 track $ordinal")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val opened = openTrack(format)
            audio = opened.track
            track = opened.track
            if (!opened.compressed) codec = decoder(format)
            codec?.start()
            jumped(extractor, codec, opened.track, (clock() * 1_000_000.0).toLong())
            if (opened.compressed) passthrough(extractor, opened.track) else decode(extractor, codec ?: error("no AC-4 decoder"), opened.track)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { audio?.pause() }
            runCatching { audio?.flush() }
            runCatching { audio?.release() }
            track = null
            runCatching { extractor.release() }
        }
    }

    private fun openTrack(format: MediaFormat): OpenedAudio {
        if (capability.direct && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bitstream(format)?.let { return OpenedAudio(it, compressed = true) }
        }
        if (capability.decoder) return OpenedAudio(pcmTrack(format), compressed = false)
        error("this device cannot play AC-4")
    }

    private class OpenedAudio(val track: AudioTrack, val compressed: Boolean)

    private fun bitstream(format: MediaFormat): AudioTrack? {
        val rate = integer(format, MediaFormat.KEY_SAMPLE_RATE, 48_000)
        val channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT, 6)
        val masks = intArrayOf(channelMask(channels), AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
        return masks.distinct().firstNotNullOfOrNull { mask -> audioTrack(AudioFormat.ENCODING_AC4, rate, mask) }
    }

    private fun pcmTrack(format: MediaFormat): AudioTrack {
        val rate = integer(format, MediaFormat.KEY_SAMPLE_RATE, 48_000)
        val channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
        return audioTrack(AudioFormat.ENCODING_PCM_16BIT, rate, channelMask(channels))
            ?: error("no PCM track for the AC-4 decoder")
    }

    private fun decoder(format: MediaFormat): MediaCodec {
        val codec = MediaCodec.createDecoderByType(Ac4.MIME)
        codec.configure(format, null, null, 0)
        return codec
    }

    private fun passthrough(extractor: MediaExtractor, audio: AudioTrack) {
        var buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
        ignoreBehindUntil = SystemClock.elapsedRealtime() + START_GRACE_MS
        while (!stopped.get()) {
            takeSeek()?.let { at -> jumped(extractor, codec = null, audio, at) }
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                hold()
                continue
            }
            if (size > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(size)
                continue
            }
            val pts = extractor.sampleTime.coerceAtLeast(0L)
            if (!pace(audio, pts)) {
                consumeResync(extractor, codec = null, audio)
                continue
            }
            buffer.position(0)
            buffer.limit(size)
            val written = audio.write(buffer, size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) error("AudioTrack rejected the AC-4 frame ($written)")
            extractor.advance()
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, audio: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        ignoreBehindUntil = SystemClock.elapsedRealtime() + START_GRACE_MS
        var current = audio
        while (!stopped.get()) {
            takeSeek()?.let { at ->
                jumped(extractor, codec, current, at)
                inputDone = false
            }
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(WAIT_US)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex) ?: error("no decoder input")
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(info, WAIT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val replacement = pcmTrack(codec.outputFormat)
                    current.pause()
                    current.flush()
                    current.release()
                    current = replacement
                    track = replacement
                    if (transport().playing) current.play()
                }
                else -> if (outIndex >= 0) {
                    val output = codec.getOutputBuffer(outIndex)
                    val pts = info.presentationTimeUs
                    val write = output != null && info.size > 0 && pts >= 0 && pace(current, pts)
                    if (write && output != null) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val written = current.write(output, info.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) error("AudioTrack rejected decoded audio ($written)")
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (consumeResync(extractor, codec, current)) inputDone = false
                    else if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) hold()
                }
            }
        }
    }

    /**
     * Holds the frame until mpv's clock reaches it. A clock that has run far ahead means the picture
     * was seeked or the frames stalled: [resyncUs] is set and the caller drops this frame, then seeks.
     * Returns false when this frame must not be written.
     */
    private fun pace(audio: AudioTrack, ptsUs: Long): Boolean {
        while (!stopped.get()) {
            takeSeek()?.let { at ->
                resyncUs = at
                return false
            }
            val now = transport()
            applySpeed(audio, now.speed)
            if (!now.playing) {
                if (audio.playState == AudioTrack.PLAYSTATE_PLAYING) audio.pause()
                sleep()
                continue
            }
            if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
            val master = (clock() * 1_000_000.0).toLong()
            val ahead = ptsUs - master
            if (ahead > AHEAD_US) {
                sleep()
                continue
            }
            if (master - ptsUs > BEHIND_US && SystemClock.elapsedRealtime() > ignoreBehindUntil) {
                resyncUs = master
                return false
            }
            return true
        }
        return false
    }

    private fun consumeResync(extractor: MediaExtractor, codec: MediaCodec?, audio: AudioTrack): Boolean {
        val at = resyncUs
        if (at == NONE) return false
        resyncUs = NONE
        jumped(extractor, codec, audio, at)
        return true
    }

    private fun hold() {
        while (!stopped.get() && pendingSeekUs.get() == NONE) sleep()
    }

    private fun takeSeek(): Long? {
        val at = pendingSeekUs.getAndSet(NONE)
        return at.takeIf { it != NONE }
    }

    private fun jumped(extractor: MediaExtractor, codec: MediaCodec?, audio: AudioTrack, atUs: Long) {
        extractor.seekTo(atUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        runCatching { codec?.flush() }
        runCatching {
            audio.pause()
            audio.flush()
        }
        ignoreBehindUntil = SystemClock.elapsedRealtime() + START_GRACE_MS
        if (transport().playing) runCatching { audio.play() }
    }

    private fun applySpeed(audio: AudioTrack, speed: Float) {
        val wanted = speed.coerceIn(0.25f, 4f)
        runCatching {
            if (audio.playbackParams.speed == wanted) return
            audio.playbackParams = PlaybackParams().setSpeed(wanted)
        }
    }

    private fun sleep() {
        try {
            Thread.sleep(SLICE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        private const val TAG = "Ac4"
        private const val NONE = Long.MIN_VALUE
        private const val JOIN_MS = 1_000L
        private const val BUFFER_BYTES = 512 * 1024
        private const val WAIT_US = 10_000L
        private const val AHEAD_US = 80_000L
        private const val BEHIND_US = 300_000L
        private const val START_GRACE_MS = 500L
        private const val SLICE_MS = 20L

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        fun ac4Index(extractor: MediaExtractor, ordinal: Int): Int? {
            var seen = 0
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.equals(Ac4.MIME, ignoreCase = true)) continue
                if (seen == ordinal) return index
                seen++
            }
            return null
        }

        fun audioTrack(encoding: Int, sampleRate: Int, channelMask: Int): AudioTrack? {
            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (min <= 0) return null
            val built = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(ATTRIBUTES)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(min * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return null
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                built.release()
                return null
            }
            return built
        }

        fun channelMask(channels: Int): Int = when {
            channels >= 8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            channels >= 6 -> AudioFormat.CHANNEL_OUT_5POINT1
            channels == 1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        fun integer(format: MediaFormat, key: String, fallback: Int): Int =
            if (format.containsKey(key)) format.getInteger(key) else fallback
    }
}
