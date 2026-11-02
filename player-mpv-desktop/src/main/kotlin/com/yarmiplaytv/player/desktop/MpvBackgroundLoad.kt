package com.yarmiplaytv.player.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates short-lived secondary libmpv instances (seek scrub, list thumbnails, skip-detect
 * audio) so they do not fight the main player's GL→CPU readback — especially on local high-res
 * files where `auto-copy` already taxes a mid-range GPU.
 */
object MpvBackgroundLoad {
    private val busy = AtomicInteger(0)
    private val playerActive = AtomicBoolean(false)

    /** Main [DesktopMpvPlayer] has a file loaded (local or stream). */
    fun setPlayerActive(active: Boolean) {
        playerActive.set(active)
    }

    fun isPlayerActive(): Boolean = playerActive.get()

    /** True when another background grab/decode already holds the GPU/CPU. */
    fun isBusy(): Boolean = busy.get() > 0

    /**
     * Runs [block] if no other background load is in flight. Returns null when skipped because
     * another grab is already running (seek scrub should not pile up).
     */
    fun <T> tryRun(block: () -> T): T? {
        if (!busy.compareAndSet(0, 1)) return null
        return try {
            block()
        } finally {
            busy.set(0)
        }
    }

    /** Runs [block] after waiting for the single background slot (thumbnails / skip detect). */
    fun <T> runExclusive(block: () -> T): T {
        while (!busy.compareAndSet(0, 1)) {
            Thread.sleep(20)
        }
        return try {
            block()
        } finally {
            busy.set(0)
        }
    }
}
