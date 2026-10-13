package com.yarmiplaytv.tracking.detect

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import com.yarmiplaytv.Logger
import java.nio.ByteOrder
import java.util.Locale

/**
 * Videos' sound for listening for openings and endings, read with the platform's own extractor and decoders (not the
 * player's mpv, which is busy playing): files on this device and servers' streams. Sound in a format the device has
 * no decoder for (DTS or Dolby on many phones) can't be read.
 */
class AndroidAudioDecoder(context: Context) : AudioDecoder {
    private val context = context.applicationContext

    override fun duration(uri: String): Double? {
        val extractor = MediaExtractor()
        return try {
            open(extractor, uri)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .mapNotNull { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else null }
                .maxOrNull()?.takeIf { it > 0 }?.let { it / 1e6 }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    override fun decode(
        uri: String,
        start: Double,
        seconds: Double,
        languages: List<String>,
        sink: (ShortArray, Int) -> Unit,
        cancelled: () -> Boolean,
    ): Double? {
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        // Not to take time from playback; the thread is the IO pool's, so it gets its priority back after.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor, uri)
            val track = audioTrack(extractor, languages) ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val endUs = ((start + seconds) * 1e6).toLong()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var firstUs = -1L
            var resampler: Resampler? = null
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var done = false
            while (!done) {
                if (cancelled()) return null
                // Every free input buffer filled, every finished output taken; only waiting when neither moved.
                var moved = false
                while (!inputDone) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index < 0) break
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0 || extractor.sampleTime > endUs) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    moved = true
                }
                while (!done) {
                    val index = decoder.dequeueOutputBuffer(info, if (moved) 0 else TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (index < 0) break
                    moved = true
                    if (info.size > 0) {
                        val out = decoder.outputFormat
                        val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val r = resampler ?: Resampler(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels, sink).also { resampler = it }
                        if (firstUs < 0) firstUs = info.presentationTimeUs
                        val buffer = decoder.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.nativeOrder())
                        val float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (float) {
                            val n = info.size / 4
                            if (floats.size < n) floats = FloatArray(n)
                            buffer.asFloatBuffer().get(floats, 0, n)
                            r.push(floats, n / channels)
                        } else {
                            val n = info.size / 2
                            if (shorts.size < n) shorts = ShortArray(n)
                            buffer.asShortBuffer().get(shorts, 0, n)
                            r.push(shorts, n / channels)
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs
                }
            }
            resampler?.flush() ?: return null
            return firstUs / 1e6
        } catch (e: Exception) {
            Logger.w(TAG, "Can't read the sound of $uri: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            runCatching { Process.setThreadPriority(tid, priority) }
        }
    }

    private fun open(extractor: MediaExtractor, uri: String) {
        if (uri.startsWith("http://") || uri.startsWith("https://")) extractor.setDataSource(uri, emptyMap<String, String>())
        else extractor.setDataSource(context, Uri.parse(uri), null)
    }

    /** The audio track in the first of [languages] the video has, else its first; null when it has none. */
    private fun audioTrack(extractor: MediaExtractor, languages: List<String>): Int? {
        val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val wanted = languages.map(::iso3)
        return wanted.firstNotNullOfOrNull { lang ->
            tracks.firstOrNull { t -> extractor.getTrackFormat(t).getString(MediaFormat.KEY_LANGUAGE)?.let(::iso3) == lang }
        } ?: tracks.firstOrNull()
    }

    private fun iso3(code: String): String = runCatching { Locale(code).isO3Language }.getOrNull()?.ifEmpty { null } ?: code.lowercase()

    private companion object {
        const val TAG = "AndroidAudioDecoder"
        const val TIMEOUT_US = 10_000L
    }
}
