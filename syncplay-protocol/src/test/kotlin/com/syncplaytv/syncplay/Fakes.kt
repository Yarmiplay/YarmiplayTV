package com.syncplaytv.syncplay

class FakeClock(var time: Double = 1_000.0) : Clock {
    override fun now(): Double = time
    fun advance(seconds: Double) {
        time += seconds
    }
}

/** A player whose position advances with [clock] while playing. */
class FakePlayer(private val clock: Clock, override var duration: Double = 1440.0) : PlayerAdapter {
    override var isFileLoaded: Boolean = true
    private var basePosition = 0.0
    private var baseTime = clock.now()
    private var paused = true
    var speed = 1.0
        private set
    val seeks = mutableListOf<Double>()

    override val position: Double
        get() = if (paused) basePosition else basePosition + (clock.now() - baseTime) * speed
    override val isPaused: Boolean get() = paused

    override fun setPaused(paused: Boolean) {
        rebase()
        this.paused = paused
    }

    override fun seek(position: Double) {
        seeks += position
        basePosition = position
        baseTime = clock.now()
    }

    override fun setSpeed(speed: Double) {
        rebase()
        this.speed = speed
    }

    private fun rebase() {
        basePosition = position
        baseTime = clock.now()
    }
}

class RecordingHost(
    override var username: String = "tv",
    override var readinessSupported: Boolean = true,
    override var isReady: Boolean? = false,
    override var currentFileDuration: Double = 1440.0,
    var othersReady: Boolean = true,
) : SyncEngine.Host {
    data class SentState(val position: Double?, val paused: Boolean?, val doSeek: Boolean, val stateChange: Boolean)

    val sent = mutableListOf<SentState>()
    val readyChanges = mutableListOf<Pair<Boolean, Boolean>>()
    val notifications = mutableListOf<String>()
    var advanceChecks = 0
    var lastPlaylistChangeAge = 100.0

    override fun areAllOtherUsersReady() = othersReady
    override fun notJustChangedPlaylist() = lastPlaylistChangeAge > Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD
    override fun sendState(position: Double?, paused: Boolean?, doSeek: Boolean, latencyCalculation: Double?, stateChange: Boolean) {
        sent += SentState(position, paused, doSeek, stateChange)
    }
    override fun changeReadyState(ready: Boolean, manuallyInitiated: Boolean) {
        isReady = ready
        readyChanges += ready to manuallyInitiated
    }
    override fun notify(message: String) {
        notifications += message
    }
    override fun advancePlaylistCheck() {
        advanceChecks++
    }
}
