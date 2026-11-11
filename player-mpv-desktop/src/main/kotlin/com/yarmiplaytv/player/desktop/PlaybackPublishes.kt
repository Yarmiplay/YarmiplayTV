package com.yarmiplaytv.player.desktop

/**
 * Which mpv observations are worth a new [com.yarmiplaytv.player.PlaybackState].
 *
 * `time-pos`, `avsync` and the demuxer cache update about once per frame. Copying them into the
 * state on every update wakes every collector (the window, the taskbar, sync, skip detection)
 * at the video's frame rate. The position still moves: the seek bar reads [com.yarmiplaytv.player.Player.currentPosition],
 * which extrapolates from the last published sample. A jump of [jumpSeconds] or more (a seek)
 * is published immediately, and the first sample of a file is too.
 */
internal class PlaybackPublishes(
    private val intervalNs: Long = 250_000_000L,
    private val jumpSeconds: Double = 1.0,
) {
    private var lastPosNs = Long.MIN_VALUE
    private var lastPos = Double.NaN
    private var lastCache = Double.NaN
    private var lastAvNs = Long.MIN_VALUE

    /** True when [value] should be copied into the playback state. Records it when it should. */
    fun position(nowNs: Long, value: Double): Boolean {
        val jump = !lastPos.isNaN() && kotlin.math.abs(value - lastPos) >= jumpSeconds
        if (lastPos.isNaN() || jump || nowNs - lastPosNs >= intervalNs) {
            notePosition(nowNs, value)
            return true
        }
        return false
    }

    /** A position that was written into the state by something other than [position] (load, seek, pause). */
    fun notePosition(nowNs: Long, value: Double) {
        lastPos = value
        lastPosNs = nowNs
    }

    /** True when the cache readout moved enough to show. [fine] while buffering, where the number is on screen. */
    fun cache(value: Double, fine: Boolean): Boolean {
        val min = if (fine) 0.2 else 0.5
        if (lastCache.isNaN() || kotlin.math.abs(value - lastCache) >= min) {
            lastCache = value
            return true
        }
        return false
    }

    /** True at most twice a second. The A/V offset is a diagnostic, not a clock. */
    fun avSync(nowNs: Long): Boolean {
        if (lastAvNs == Long.MIN_VALUE || nowNs - lastAvNs >= 500_000_000L) {
            lastAvNs = nowNs
            return true
        }
        return false
    }

    fun reset() {
        lastPosNs = Long.MIN_VALUE
        lastPos = Double.NaN
        lastCache = Double.NaN
        lastAvNs = Long.MIN_VALUE
    }
}
