package com.yarmiplaytv.syncplay

/**
 * What the sync logic needs from a media player. Reads must be cheap and thread-safe;
 * they are polled every [Constants.PLAYER_ASK_DELAY_MS].
 *
 * Reads must reflect commands immediately: after [setPaused] or [seek], [isPaused] and
 * [position] return the requested values even if the player applies them asynchronously.
 */
interface PlayerAdapter {
    val isFileLoaded: Boolean
    /** Seconds, 0 when unknown. */
    val duration: Double
    /** Seconds. */
    val position: Double
    val isPaused: Boolean

    fun setPaused(paused: Boolean)
    fun seek(position: Double)
    fun setSpeed(speed: Double)
}

fun interface Clock {
    /** Seconds; only differences matter. */
    fun now(): Double

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() / 1000.0 }
    }
}
