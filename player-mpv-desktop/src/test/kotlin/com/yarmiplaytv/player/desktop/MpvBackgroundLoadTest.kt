package com.yarmiplaytv.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvBackgroundLoadTest {
    @Test
    fun tryRunSkipsWhenBusy() {
        MpvBackgroundLoad.setPlayerActive(false)
        var nested: Int? = -1
        val outer = MpvBackgroundLoad.tryRun {
            nested = MpvBackgroundLoad.tryRun { 1 }
            2
        }
        assertEquals(2, outer)
        assertNull(nested)
    }

    @Test
    fun runExclusiveSerializes() {
        val order = mutableListOf<Int>()
        val first = Thread {
            MpvBackgroundLoad.runExclusive {
                order += 1
                Thread.sleep(40)
                order += 2
            }
        }
        val second = Thread {
            Thread.sleep(10)
            MpvBackgroundLoad.runExclusive {
                order += 3
            }
        }
        first.start()
        second.start()
        first.join()
        second.join()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun playerActiveFlag() {
        MpvBackgroundLoad.setPlayerActive(true)
        assertTrue(MpvBackgroundLoad.isPlayerActive())
        MpvBackgroundLoad.setPlayerActive(false)
        assertFalse(MpvBackgroundLoad.isPlayerActive())
    }
}
