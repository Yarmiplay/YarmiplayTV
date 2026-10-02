package com.syncplaytv.syncplay

import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps the local player in step with the room. This is a port of the playback-state half of
 * Syncplay's client.py (updatePlayerStatus, updateGlobalState, getLocalState and helpers), so the
 * TV behaves the same way desktop users are used to.
 *
 * Not thread-safe: all calls must come from one thread/dispatcher.
 */
class SyncEngine(
    private val player: PlayerAdapter,
    private val clock: Clock,
    var settings: SyncSettings,
    private val host: Host,
) {
    interface Host {
        val username: String
        val readinessSupported: Boolean
        val isReady: Boolean?
        /** Duration of the file we reported to the server, 0 when none. */
        val currentFileDuration: Double
        fun areAllOtherUsersReady(): Boolean
        fun notJustChangedPlaylist(): Boolean
        fun sendState(position: Double?, paused: Boolean?, doSeek: Boolean, latencyCalculation: Double?, stateChange: Boolean)
        fun changeReadyState(ready: Boolean, manuallyInitiated: Boolean)
        fun notify(message: String)
        fun advancePlaylistCheck()
    }

    data class LocalState(val position: Double, val paused: Boolean, val doSeek: Boolean, val stateChange: Boolean)

    private var globalPosition = 0.0
    private var globalPaused = true
    private var lastGlobalUpdate: Double? = null

    private var playerPosition = 0.0
    private var playerPaused = true
    private var lastPlayerUpdate: Double? = null

    private var speedChanged = false
    private var lastRewindTime: Double? = null
    private var lastAdvanceTime: Double? = null
    /** After a reset-to-start file switch the player reports position 0 until this time, like Syncplay's mpv wrapper. */
    private var resetIgnoreUntil: Double? = null
    /**
     * False between [onFileLoading] and [onFileLoaded]. The player already reports the new file
     * (paused at 0) before the room knows about it, which would otherwise look like a local pause + seek.
     */
    private var fileReady = true
    private val playerReady: Boolean get() = fileReady && player.isFileLoaded

    val hasGlobalState: Boolean get() = lastGlobalUpdate != null

    fun onDisconnected() {
        lastGlobalUpdate = null
        lastPlayerUpdate = null
        if (speedChanged) {
            player.setSpeed(1.0)
            speedChanged = false
        }
    }

    // --- Player side ---------------------------------------------------------

    private fun readPlayerPosition(): Double {
        val now = clock.now()
        resetIgnoreUntil?.let { if (now < it) return 0.0 else resetIgnoreUntil = null }
        return if (playerReady) max(player.position, 0.0) else getGlobalPosition()
    }

    private fun readPlayerPaused(): Boolean =
        if (playerReady) player.isPaused else getGlobalPaused()

    /** Call every [Constants.PLAYER_ASK_DELAY_MS] while connected. */
    fun poll() {
        updatePlayerStatus(readPlayerPaused(), readPlayerPosition())
    }

    private fun determinePlayerStateChange(paused: Boolean, position: Double): Pair<Boolean, Boolean> {
        val pauseChange = getPlayerPaused() != paused && getGlobalPaused() != paused
        val playerDiff = abs(getPlayerPosition() - position)
        val globalDiff = abs(getGlobalPosition() - position)
        val seeked = playerDiff > Constants.SEEK_THRESHOLD && globalDiff > Constants.SEEK_THRESHOLD
        return pauseChange to seeked
    }

    private fun updatePlayerStatus(paused: Boolean, position: Double) {
        var (pauseChange, seeked) = determinePlayerStateChange(paused, position)
        playerPosition = position
        playerPaused = paused
        val length = host.currentFileDuration
        val nearEnd = abs(position - length) < Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD
        if (pauseChange && paused && length > Constants.PLAYLIST_LOAD_NEXT_FILE_MINIMUM_LENGTH && nearEnd) {
            host.advancePlaylistCheck()
        } else if (pauseChange && host.readinessSupported) {
            if (length <= 0 || !(!host.notJustChangedPlaylist() && nearEnd)) {
                pauseChange = toggleReady(pauseChange, paused)
            }
        }
        if (lastGlobalUpdate != null) {
            lastPlayerUpdate = clock.now()
            if (pauseChange || seeked) {
                if (recentlyRewound() || recentlyAdvanced()) {
                    host.sendState(globalPosition, getPlayerPaused(), false, null, true)
                    return
                }
                if (seeked) host.notify("You jumped to ${formatTime(position)}")
                host.sendState(getPlayerPosition(), getPlayerPaused(), seeked, null, true)
            }
        }
    }

    private fun toggleReady(pauseChange: Boolean, paused: Boolean): Boolean {
        if (recentlyRewound() && globalPaused && !recentlyAdvanced()) {
            setPlayerPaused(globalPaused)
            playerPaused = globalPaused
            return false
        }
        if (!paused && !instaplayConditionsMet()) {
            setPlayerPaused(true)
            playerPaused = true
            host.changeReadyState(true, manuallyInitiated = true)
            host.notify("You are ready. Waiting for the others; press play again to start anyway.")
            return false
        }
        host.changeReadyState(!getPlayerPaused(), manuallyInitiated = false)
        return pauseChange
    }

    private fun instaplayConditionsMet(): Boolean = when {
        host.isReady == true || settings.unpauseMode == UnpauseMode.ALWAYS -> true
        settings.unpauseMode == UnpauseMode.IF_OTHERS_READY && host.areAllOtherUsersReady() -> true
        else -> false
    }

    /** Reply payload for a server State message. Null until the first server State arrives. */
    fun getLocalState(): LocalState? {
        if (lastGlobalUpdate == null) return null
        val paused = getPlayerPaused()
        val position = getPlayerPosition()
        val (pauseChange, seeked) = determinePlayerStateChange(paused, position)
        return LocalState(position, paused, seeked, pauseChange)
    }

    // --- Room side ------------------------------------------------------------

    fun updateGlobalState(position: Double, paused: Boolean, doSeek: Boolean, setBy: String?, messageAge: Double) {
        val adjusted = if (!paused) position + messageAge else position
        val madeChangeOnPlayer = changePlayerStateAccordingToGlobalState(adjusted, paused, doSeek, setBy)
        // Re-read the player straight away (Syncplay's askPlayer) so the reply to this State
        // carries the state we just applied instead of the stale one from the last poll.
        if (madeChangeOnPlayer) poll()
    }

    private fun changePlayerStateAccordingToGlobalState(position: Double, paused: Boolean, doSeek: Boolean, setBy: String?): Boolean {
        var madeChange = false
        val pauseChanged = paused != getGlobalPaused() || paused != getPlayerPaused()
        val diff = getPlayerPosition() - position
        if (lastGlobalUpdate == null) madeChange = initPlayerState(position, paused)
        globalPaused = paused
        globalPosition = position
        lastGlobalUpdate = clock.now()
        if (doSeek) madeChange = serverSeeked(position, setBy) || madeChange
        if (diff > settings.rewindThreshold && !doSeek && settings.rewindOnDesync) {
            madeChange = rewindDueToTimeDifference(position, setBy) || madeChange
        }
        if (!doSeek && !paused && settings.slowOnDesync) madeChange = slowDownToCoverTimeDifference(diff, setBy) || madeChange
        if (!paused && pauseChanged) madeChange = serverUnpaused(setBy) || madeChange
        else if (paused && pauseChanged) madeChange = serverPaused(setBy) || madeChange
        return madeChange
    }

    private fun initPlayerState(position: Double, paused: Boolean): Boolean {
        if (!playerReady) return false
        setPosition(position)
        setPlayerPaused(paused)
        return true
    }

    private fun serverSeeked(position: Double, setBy: String?): Boolean {
        if (host.username == setBy) return false
        val before = getPlayerPosition()
        setPosition(position)
        // A new file starting at 0 also arrives as a seek; "jumped from 00:00 to 00:00" would only be noise.
        if (abs(before - position) > Constants.SEEK_THRESHOLD) {
            host.notify("${setBy ?: "Someone"} jumped from ${formatTime(before)} to ${formatTime(position)}")
        }
        return true
    }

    private fun rewindDueToTimeDifference(position: Double, setBy: String?): Boolean {
        if (host.username == setBy) return false
        setPosition(position)
        host.notify("Rewinded due to time difference with ${setBy ?: "the room"}")
        return true
    }

    private fun slowDownToCoverTimeDifference(diff: Double, setBy: String?): Boolean {
        if (settings.slowdownThreshold < diff && !speedChanged) {
            if (host.username == setBy) return false
            player.setSpeed(Constants.SLOWDOWN_RATE)
            speedChanged = true
            return true
        } else if (speedChanged && diff < Constants.SLOWDOWN_RESET_THRESHOLD) {
            player.setSpeed(1.0)
            speedChanged = false
            return true
        }
        return false
    }

    private fun serverUnpaused(setBy: String?): Boolean {
        setPlayerPaused(false)
        if (setBy != null && setBy != host.username) host.notify("$setBy unpaused")
        return true
    }

    private fun serverPaused(setBy: String?): Boolean {
        if (host.username != setBy) setPosition(getGlobalPosition())
        setPlayerPaused(true)
        if (setBy != null && setBy != host.username) host.notify("$setBy paused at ${formatTime(getGlobalPosition())}")
        return true
    }

    // --- File switching -------------------------------------------------------

    /**
     * Call once the player has loaded a new file.
     * With [resetPosition] playback starts from 0 (a playlist switch); otherwise it jumps to the room's position.
     */
    fun onFileLoaded(resetPosition: Boolean) {
        fileReady = true
        val now = clock.now()
        if (resetPosition) {
            resetIgnoreUntil = now + Constants.NEWFILE_IGNORE_TIME
            player.seek(0.0)
            lastRewindTime = now
            playerPosition = 0.0
            if (lastGlobalUpdate != null) setPlayerPaused(globalPaused)
        } else if (lastGlobalUpdate != null) {
            player.seek(getGlobalPosition())
            setPlayerPaused(getGlobalPaused())
        }
        if (lastPlayerUpdate != null) lastPlayerUpdate = now
    }

    /** Call when the player starts opening a new file; it's ignored until [onFileLoaded]. */
    fun onFileLoading() {
        fileReady = false
    }

    fun markAdvanced() {
        lastAdvanceTime = clock.now()
    }

    // --- Helpers ----------------------------------------------------------------

    private fun setPosition(position: Double) {
        val now = clock.now()
        if (lastPlayerUpdate != null) lastPlayerUpdate = now
        val rewound = lastRewindTime
        if (rewound != null && abs(now - rewound) < 1.0 && position > 5) return
        if (playerReady) player.seek(max(position, 0.0))
    }

    private fun setPlayerPaused(paused: Boolean) {
        if (!playerReady) return
        if (lastPlayerUpdate != null && !paused) lastPlayerUpdate = clock.now()
        if (paused && speedChanged) {
            player.setSpeed(1.0)
            speedChanged = false
        }
        player.setPaused(paused)
    }

    private fun recentlyRewound(): Boolean {
        val t = lastRewindTime ?: return false
        return abs(clock.now() - t) < Constants.RECENT_REWIND_THRESHOLD
    }

    private fun recentlyAdvanced(): Boolean {
        val t = lastAdvanceTime ?: return false
        return clock.now() - t < Constants.AUTOPLAY_DELAY + 5
    }

    fun getPlayerPosition(): Double {
        val lpu = lastPlayerUpdate ?: return if (lastGlobalUpdate != null) getGlobalPosition() else 0.0
        var position = playerPosition
        if (!playerPaused) position += clock.now() - lpu
        return position
    }

    fun getPlayerPaused(): Boolean {
        if (lastPlayerUpdate == null) return if (lastGlobalUpdate != null) getGlobalPaused() else true
        return playerPaused
    }

    fun getGlobalPosition(): Double {
        val lgu = lastGlobalUpdate ?: return 0.0
        var position = globalPosition
        if (!globalPaused) position += clock.now() - lgu
        return position
    }

    fun getGlobalPaused(): Boolean = if (lastGlobalUpdate == null) true else globalPaused
}

fun formatTime(seconds: Double): String {
    val total = max(seconds, 0.0).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
