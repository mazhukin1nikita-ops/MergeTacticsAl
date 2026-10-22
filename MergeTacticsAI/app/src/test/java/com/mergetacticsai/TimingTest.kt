package com.mergetacticsai

import com.mergetacticsai.automation.Timing
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimingTest {
    @Before fun reset() = Timing.reset()

    @Test fun startsAtFastestLevel() {
        assertEquals(0, Timing.level)
        assertEquals(Timing.levels[0].dragMs, Timing.dragMs)
        assertTrue(Timing.dragMs <= 150)
    }

    @Test fun levelsGetSlower() {
        for (i in 1 until Timing.levels.size) {
            assertTrue(Timing.levels[i].dragMs > Timing.levels[i - 1].dragMs)
            assertTrue(Timing.levels[i].holdMs >= Timing.levels[i - 1].holdMs)
            assertTrue(Timing.levels[i].settleMs >= Timing.levels[i - 1].settleMs)
        }
    }

    @Test fun failureSlowsDownAndStopsAtSafest() {
        repeat(10) { Timing.fail() }
        assertEquals(Timing.levels.lastIndex, Timing.level)
        assertEquals(Timing.levels.last().dragMs, Timing.dragMs)
    }

    @Test fun successesSpeedUpAgain() {
        Timing.fail(); Timing.fail()
        assertEquals(2, Timing.level)
        repeat(Timing.FASTER_AFTER) { Timing.ok() }
        assertEquals(1, Timing.level)
        repeat(Timing.FASTER_AFTER) { Timing.ok() }
        assertEquals(0, Timing.level)
        repeat(20) { Timing.ok() }
        assertEquals(0, Timing.level)
    }

    @Test fun failureResetsTheSuccessStreak() {
        Timing.fail()
        repeat(Timing.FASTER_AFTER - 1) { Timing.ok() }
        Timing.fail()
        repeat(Timing.FASTER_AFTER - 1) { Timing.ok() }
        assertEquals(2, Timing.level)
    }

    @Test fun pausesAreShort() {
        assertTrue(Timing.LOOP_MS <= 30 && Timing.AFTER_MS <= 50 && Timing.ENGINE_GAP_MS <= 100)
    }
}
