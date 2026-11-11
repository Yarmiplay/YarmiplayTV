package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetTest {
    @Test
    fun aSlowReadbackStepsDownFrom1080p() {
        val budget = RenderBudget(1920)
        val start = 10_000_000_000L
        var stepped: Int? = null
        repeat(40) { i ->
            val next = budget.observe(20_000_000L, start + i)
            if (next != null && stepped == null) stepped = next
        }
        assertEquals(1600, stepped)
    }

    @Test
    fun aFastReadbackStaysAtTheCeiling() {
        val budget = RenderBudget(1920)
        val start = 10_000_000_000L
        repeat(200) { i ->
            assertNull(budget.observe(3_000_000L, start + i * 1_000_000L))
        }
    }

    @Test
    fun theLowEndCeilingIsNeverExceeded() {
        val budget = RenderBudget(1280)
        val seen = mutableListOf<Int>()
        var now = 10_000_000_000L
        repeat(4) {
            repeat(40) { i ->
                budget.observe(20_000_000L, now + i)?.let { seen += it }
            }
            now += 3_000_000_000L
        }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it <= 1280 })
        assertEquals(960, seen.first())
    }
}
