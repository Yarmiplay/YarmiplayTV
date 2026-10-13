package com.yarmiplaytv.tracking.detect

import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.sync.NowPlaying
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A made-up season of [count] episodes of [duration] seconds, each its own music with the same opening song at
 * [openingAt] and the same ending song at [endingAt] seconds, each [songSeconds] long. Episodes are at
 * "file:///show/Show - NN.mkv", or on a server ("http://server/NN") when [remote].
 */
class FakeSeason(
    val count: Int = 4,
    val duration: Double = 1440.0,
    val songSeconds: Double = 90.0,
    val remote: Boolean = false,
    val openingAt: (Int) -> Double = { n -> 10.0 * n },
    val endingAt: (Int) -> Double = { 1300.0 },
) {
    /** Each read: the episode and where it started. */
    val reads = CopyOnWriteArrayList<Pair<Int, Double>>()
    /** While set, reading waits until cancelled (for the tests of cancelling). */
    @Volatile var hang = false
    @Volatile var sawCancel = false

    fun url(n: Int) = if (remote) "http://server/%02d".format(n) else "file:///show/Show - %02d.mkv".format(n)
    fun name(n: Int) = "Show - %02d.mkv".format(n)
    private fun numberOf(url: String) = Regex("(\\d{2})(\\.mkv)?$").find(url)!!.groupValues[1].toInt()

    fun file(n: Int) = EpisodeFile(n, url(n), name(n), 1000L + n, duration.takeIf { remote }, remote)

    val siblings = SiblingFinder { np ->
        val n = numberOf(np.url)
        Siblings(n, EpisodeSiblings.nearestFirst(n, (1..count).filter { it != n }.map(::file)))
    }

    fun nowPlaying(n: Int) =
        NowPlaying("Show $n", name(n), 1000L + n, duration, url(n), episode = EpisodeInfo("Made Up Show", 1, n, seriesKey = "file/made up show"))

    val decoder = object : AudioDecoder {
        override fun duration(uri: String): Double = duration

        override fun decode(
            uri: String, start: Double, seconds: Double, languages: List<String>,
            sink: (ShortArray, Int) -> Unit, cancelled: () -> Boolean,
        ): Double? {
            val n = numberOf(uri)
            reads += n to start
            while (hang) {
                if (cancelled()) {
                    sawCancel = true
                    return null
                }
                Thread.sleep(10)
            }
            val rate = Synth.RATE
            val first = (start * rate).toLong()
            val last = ((start + seconds).coerceAtMost(duration) * rate).toLong()
            val song = (songSeconds * rate).toLong()
            val op = (openingAt(n) * rate).toLong()
            val ed = (endingAt(n) * rate).toLong()
            var i = first
            while (i < last) {
                if (cancelled()) return null
                val chunk = minOf(CHUNK.toLong(), last - i).toInt()
                val pcm = ShortArray(chunk)
                var k = 0
                // Each stretch is the opening, the ending or the episode's own music.
                while (k < chunk) {
                    val at = i + k
                    val (seed, from, until) = when (at) {
                        in op until op + song -> Triple(OPENING_SEED, at - op, op + song)
                        in ed until ed + song -> Triple(ENDING_SEED, at - ed, ed + song)
                        else -> Triple(100 + n, at, listOf(op, ed, i + chunk).filter { it > at }.min())
                    }
                    val take = minOf(until - at, (chunk - k).toLong()).toInt()
                    Synth.musicAt(seed, from, take).copyInto(pcm, k)
                    k += take
                }
                sink(pcm, chunk)
                i += chunk
            }
            return start
        }
    }

    companion object {
        private const val CHUNK = 8192
        private const val OPENING_SEED = 7
        private const val ENDING_SEED = 8
    }
}
