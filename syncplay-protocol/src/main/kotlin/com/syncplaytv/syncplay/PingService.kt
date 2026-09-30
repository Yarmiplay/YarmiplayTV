package com.syncplaytv.syncplay

/** Port of Syncplay's PingService: round-trip time and one-way delay estimation. */
class PingService(private val clock: Clock) {
    private var rtt = 0.0
    private var forwardDelay = 0.0
    private var avgRtt = 0.0

    fun newTimestamp(): Double = clock.now()

    fun receiveMessage(timestamp: Double?, senderRtt: Double) {
        if (timestamp == null || timestamp == 0.0) return
        rtt = clock.now() - timestamp
        if (rtt < 0 || senderRtt < 0) return
        if (avgRtt == 0.0) avgRtt = rtt
        avgRtt = avgRtt * Constants.PING_MOVING_AVERAGE_WEIGHT + rtt * (1 - Constants.PING_MOVING_AVERAGE_WEIGHT)
        forwardDelay = if (senderRtt < rtt) avgRtt / 2 + (rtt - senderRtt) else avgRtt / 2
    }

    fun getRtt(): Double = rtt
    fun getLastForwardDelay(): Double = forwardDelay
}
