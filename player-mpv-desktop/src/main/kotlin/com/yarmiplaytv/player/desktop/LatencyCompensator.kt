package com.yarmiplaytv.player.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * mpv thinks a frame is on screen when its render call returns, but it only appears after the hand-off to the
 * UI and the next display refresh. This delays the audio by that measured latency (on top of the user's own
 * audio delay) so picture and sound line up on screen. It only adjusts when the estimate moves by more than
 * [THRESHOLD_MS], e.g. after moving the window to a display with another refresh rate, since every change
 * makes mpv resync the audio.
 */
class LatencyCompensator(
    private val core: MpvCore,
    private val renderer: VideoRenderer,
    private val refreshRateHz: () -> Double,
) {
    /** The user's own audio delay in seconds; the compensation is added to it. */
    @Volatile var userAudioDelay: Double = 0.0
        set(value) {
            field = value
            apply(appliedMs, force = true)
        }

    /** The compensation currently applied, in milliseconds. */
    @Volatile var appliedMs: Double = 0.0
        private set

    /**
     * Off for audio-only files. Delaying the sound to line up with a picture would put the spectrum
     * ahead of the music, and there is no picture to line up.
     */
    @Volatile var compensate: Boolean = true

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(1_000)
                if (!compensate) {
                    if (appliedMs != 0.0) apply(0.0, force = true)
                    continue
                }
                val handoff = renderer.recentHandoffMs() ?: continue
                val estimate = (handoff + 1000.0 / refreshRateHz().coerceIn(20.0, 500.0))
                    .coerceIn(0.0, MAX_COMPENSATION_MS)
                if (abs(estimate - appliedMs) > THRESHOLD_MS || appliedMs == 0.0) apply(estimate, force = false)
            }
        }
    }

    private fun apply(ms: Double, force: Boolean) {
        val capped = ms.coerceIn(0.0, MAX_COMPENSATION_MS)
        if (!force && abs(capped - appliedMs) < 0.5) return
        appliedMs = capped
        core.setDouble("audio-delay", userAudioDelay + capped / 1000)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val THRESHOLD_MS = 4.0
        /** Avoid runaway audio lag when readback stalls (e.g. GPU hiccup on mid-range cards). */
        const val MAX_COMPENSATION_MS = 80.0
    }
}
