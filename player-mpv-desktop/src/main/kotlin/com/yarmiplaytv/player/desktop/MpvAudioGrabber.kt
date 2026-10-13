package com.yarmiplaytv.player.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video's sound for finding openings and endings by listening: a short-lived libmpv instance of its own, separate
 * from the player's, decodes part of the audio as fast as it can into raw mono 16-bit samples at the rate asked for
 * (mpv's PCM file output), which are then handed over a piece at a time.
 */
object MpvAudioGrabber {
    private const val TIMEOUT_SECONDS = 300L
    private const val POLL_MILLIS = 100L
    private const val CHUNK_SAMPLES = 8192

    /** How long the video at [path] (a file or a URL) is in seconds; null when mpv can't open it. */
    fun duration(path: String): Double? {
        val core = MpvCore(probeOptions(), logLevel = "no")
        core.use {
            val loaded = CountDownLatch(1)
            core.startEvents { event -> if (event == MpvEvent.FileLoaded || event is MpvEvent.EndFile || event == MpvEvent.Shutdown) loaded.countDown() }
            if (core.command("loadfile", path) < 0) return null
            if (!loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
            return core.getDouble("duration")?.takeIf { it > 0 }
        }
    }

    /**
     * Decodes [seconds] of the sound of [path] from [start] into mono [rate] Hz 16-bit samples for [sink], from the
     * audio track in the first of [languages] (mpv's `alang`, comma-separated) the video has. False when there is no
     * sound to be had, or when [cancelled] said to stop.
     */
    fun grab(
        path: String,
        start: Double,
        seconds: Double,
        rate: Int,
        languages: String,
        sink: (ShortArray, Int) -> Unit,
        cancelled: () -> Boolean,
    ): Boolean {
        val out = File.createTempFile("yarmiplaytv-sound", ".pcm")
        try {
            val core = MpvCore(options(out, start, seconds, rate, languages), logLevel = "no")
            var ended: MpvEvent.EndFile? = null
            core.use {
                val done = CountDownLatch(1)
                core.startEvents { event ->
                    if (event is MpvEvent.EndFile) ended = event
                    if (event is MpvEvent.EndFile || event == MpvEvent.Shutdown) done.countDown()
                }
                if (core.command("loadfile", path) < 0) return false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
                while (!done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (cancelled() || System.nanoTime() > deadline) return false
                }
            }
            // An error ending (no audio, can't open) leaves nothing worth hearing.
            if ((ended?.error ?: 0) < 0 || out.length() < 2) return false
            out.inputStream().buffered().use { input ->
                val bytes = ByteArray(CHUNK_SAMPLES * 2)
                val samples = ShortArray(CHUNK_SAMPLES)
                var carry = -1
                while (true) {
                    if (cancelled()) return false
                    var n = 0
                    if (carry >= 0) {
                        bytes[0] = carry.toByte()
                        n = 1
                        carry = -1
                    }
                    val read = input.read(bytes, n, bytes.size - n)
                    if (read < 0) break
                    n += read
                    if (n % 2 == 1) carry = bytes[--n].toInt() and 0xff
                    ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, n / 2)
                    sink(samples, n / 2)
                }
            }
            return true
        } finally {
            out.delete()
        }
    }

    private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private fun options(out: File, start: Double, seconds: Double, rate: Int, languages: String) = mapOf(
        "vid" to "no",
        "sid" to "no",
        "ao" to "pcm",
        "ao-pcm-file" to out.absolutePath,
        "ao-pcm-waveheader" to "no",
        "audio-channels" to "mono",
        "audio-samplerate" to rate.toString(),
        "audio-format" to "s16",
        "alang" to languages,
        "start" to seconds(start),
        "length" to seconds(seconds),
        "hr-seek" to "yes",
        "untimed" to "yes",
        "keep-open" to "no",
        "load-scripts" to "no",
        "ytdl" to "no",
        "audio-display" to "no",
        "replaygain" to "no",
    )

    /** Opened and paused: with no track at all mpv would drop the file before saying how long it is. */
    private fun probeOptions() = mapOf(
        "vid" to "no",
        "sid" to "no",
        "ao" to "null",
        "pause" to "yes",
        "keep-open" to "yes",
        "load-scripts" to "no",
        "ytdl" to "no",
    )
}
