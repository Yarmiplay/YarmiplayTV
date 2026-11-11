package com.yarmiplaytv.player.desktop

/**
 * How large a frame the GPU readback can keep up with.
 *
 * Copying a 1080p or 4K frame off the GPU on the render thread, and waiting for that copy,
 * is what makes a weak machine hitch. After a short warmup, a readback that stays slower than
 * [slowNs] steps the longest edge down (1920, 1600, 1280, 960, 854) and one that stays faster
 * than [fastNs] steps it back up, never above [ceiling] and at most once per [cooldownNs].
 */
internal class RenderBudget(
    private val ceiling: Int,
    private val floor: Int = minOf(854, ceiling.coerceAtLeast(2)),
    private val slowNs: Long = 12_000_000L,
    private val fastNs: Long = 5_000_000L,
    private val warmup: Int = 30,
    private val slowStreakNeeded: Int = 6,
    private val fastStreakNeeded: Int = 90,
    private val cooldownNs: Long = 2_000_000_000L,
) {
    private val steps: IntArray = intArrayOf(1920, 1600, 1280, 960, 854)
        .filter { it in floor..ceiling }
        .ifEmpty { listOf(ceiling.coerceAtLeast(floor)) }
        .toIntArray()
    private var index = 0
    private var samples = 0
    private var slow = 0
    private var fast = 0
    private var lastChangeNs = Long.MIN_VALUE / 4

    /** The edge to switch to, or null to keep the current one. [readbackNs] is one finished copy. */
    fun observe(readbackNs: Long, nowNs: Long): Int? {
        samples++
        if (samples <= warmup) return null
        if (readbackNs > slowNs) {
            slow++
            fast = 0
        } else if (readbackNs < fastNs) {
            fast++
            slow = 0
        } else {
            slow = 0
            fast = 0
        }
        if (nowNs - lastChangeNs < cooldownNs) return null
        if (slow >= slowStreakNeeded && steps[index] > floor) {
            val next = (index + 1).coerceAtMost(steps.lastIndex)
            if (steps[next] < steps[index]) {
                index = next
                lastChangeNs = nowNs
                slow = 0
                return steps[index]
            }
        }
        if (fast >= fastStreakNeeded && index > 0) {
            index--
            lastChangeNs = nowNs
            fast = 0
            return steps[index]
        }
        return null
    }
}
